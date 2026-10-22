//! Meshes contributed by other renderers (e.g. moving block grids, model batches).
//! Each mesh owns a BLAS built once; surface RT rebuilds a small instance TLAS per
//! recording so per-frame motion only costs an instance upload and a TLAS build.
use super::*;

pub const MAX_EXTERNAL_INSTANCES: usize = 512;
pub const MAX_EXTERNAL_MESH_TRIANGLES: usize = 262_144;
pub const EXTERNAL_TRANSFORM_FLOATS: usize = 12;
/// One 16-byte entry per TLAS custom index; entry 0 is the scanned world.
pub const EXTERNAL_TABLE_BYTES: u64 = ((MAX_EXTERNAL_INSTANCES + 1) * 16) as u64;
const INSTANCE_BYTES: usize = size_of::<vk::AccelerationStructureInstanceKHR>();

pub(crate) struct ExternalMesh {
    vertices: Buffer,
    metadata: Buffer,
    blas: Acceleration,
    triangles: u32,
    pub(crate) built: AtomicBool,
}
impl ExternalMesh {
    pub(crate) fn bytes(&self) -> u64 {
        self.vertices.size + self.metadata.size + self.blas._storage.size + self.blas.scratch.size
    }
}

#[derive(Clone)]
pub(crate) struct ExternalInstance {
    pub(crate) mesh: Arc<ExternalMesh>,
    /// Row-major 3x4 mesh-to-scene transform (VkTransformMatrixKHR layout).
    pub(crate) transform: [f32; EXTERNAL_TRANSFORM_FLOATS],
}

pub(crate) struct ExternalTlas {
    instances: Buffer,
    tlas: Acceleration,
}

pub fn validate_external_transform(t: &[f32]) -> Result<(), String> {
    if t.len() != EXTERNAL_TRANSFORM_FLOATS || t.iter().any(|v| !v.is_finite()) {
        return Err("Invalid external instance transform".into());
    }
    let determinant = t[0] * (t[5] * t[10] - t[6] * t[9]) - t[1] * (t[4] * t[10] - t[6] * t[8])
        + t[2] * (t[4] * t[9] - t[5] * t[8]);
    if !determinant.is_finite() || determinant.abs() < 1e-12 {
        return Err("Singular external instance transform".into());
    }
    Ok(())
}

pub fn validate_external_mesh(values: &[f32]) -> Result<(), String> {
    validate_scene(values)?;
    if values.len() / 24 > MAX_EXTERNAL_MESH_TRIANGLES {
        return Err("External mesh exceeds its triangle budget".into());
    }
    Ok(())
}

/// TLAS records: custom index 0 is the world BLAS, external i uses custom index i + 1.
pub(crate) fn instance_records(
    world_blas: u64,
    frame: &[ExternalInstance],
) -> Vec<vk::AccelerationStructureInstanceKHR> {
    let flags = vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE.as_raw() as u8;
    let record = |matrix: [f32; 12], custom: u32, address: u64| {
        vk::AccelerationStructureInstanceKHR {
            transform: vk::TransformMatrixKHR { matrix },
            instance_custom_index_and_mask: vk::Packed24_8::new(custom, 255),
            instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(0, flags),
            acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                device_handle: address,
            },
        }
    };
    let mut out = Vec::with_capacity(frame.len() + 1);
    out.push(record(
        [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.],
        0,
        world_blas,
    ));
    for (i, instance) in frame.iter().enumerate() {
        out.push(record(
            instance.transform,
            i as u32 + 1,
            instance.mesh.blas.address,
        ));
    }
    out
}

/// Shader table: (address.lo, address.hi, triangles, flags) per custom index.
pub(crate) unsafe fn table_entries(frame: &[ExternalInstance]) -> Vec<u32> {
    let mut out = vec![0u32; (frame.len() + 1) * 4];
    for (i, instance) in frame.iter().enumerate() {
        let address = instance.mesh.metadata.address();
        let entry = &mut out[(i + 1) * 4..(i + 2) * 4];
        entry[0] = address as u32;
        entry[1] = (address >> 32) as u32;
        entry[2] = instance.mesh.triangles;
    }
    out
}

impl SceneFactory {
    pub(crate) unsafe fn prepare_mesh(&self, values: &[f32]) -> Result<Arc<ExternalMesh>, String> {
        validate_external_mesh(values)?;
        let count = (values.len() / 24) as u32;
        let mut positions = Vec::<f32>::with_capacity(count as usize * 12);
        for t in values.chunks_exact(24) {
            positions.extend_from_slice(&t[..12]);
        }
        let vertices = Buffer::new(
            &self.device,
            &self.memory,
            (positions.len() * 4) as u64,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            true,
        )?;
        vertices.upload(&positions)?;
        // Read through buffer_reference, so it needs a device address unlike world metadata.
        let metadata = Buffer::new(
            &self.device,
            &self.memory,
            (values.len() * 4) as u64,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            true,
        )?;
        metadata.upload(values)?;
        let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
            .vertex_format(vk::Format::R32G32B32_SFLOAT)
            .vertex_data(vk::DeviceOrHostAddressConstKHR {
                device_address: vertices.address(),
            })
            .vertex_stride(16)
            .max_vertex(count * 3 - 1)
            .index_type(vk::IndexType::NONE_KHR);
        let geometry = vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
            .geometry(vk::AccelerationStructureGeometryDataKHR { triangles });
        let blas = self.acceleration(
            vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
            geometry,
            count,
        )?;
        Ok(Arc::new(ExternalMesh {
            vertices,
            metadata,
            blas,
            triangles: count,
            built: AtomicBool::new(false),
        }))
    }
    pub(crate) unsafe fn external_tlas(&self) -> Result<ExternalTlas, String> {
        // Device-local and written with vkCmdUpdateBuffer, so earlier submissions that
        // still trace this TLAS are ordered by the recorded barriers, not host timing.
        let instances = Buffer::new(
            &self.device,
            &self.memory,
            ((MAX_EXTERNAL_INSTANCES + 1) * INSTANCE_BYTES) as u64,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?;
        let geometry = vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::INSTANCES)
            .geometry(vk::AccelerationStructureGeometryDataKHR {
                instances: vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
                    vk::DeviceOrHostAddressConstKHR {
                        device_address: instances.address(),
                    },
                ),
            });
        let tlas = self.acceleration(
            vk::AccelerationStructureTypeKHR::TOP_LEVEL,
            geometry,
            (MAX_EXTERNAL_INSTANCES + 1) as u32,
        )?;
        Ok(ExternalTlas { instances, tlas })
    }
}

impl RayState {
    pub(crate) fn set_external_frame(&mut self, frame: Vec<ExternalInstance>) -> Result<(), String> {
        if frame.len() > MAX_EXTERNAL_INSTANCES {
            return Err("External instance budget exceeded".into());
        }
        for instance in &frame {
            validate_external_transform(&instance.transform)?;
        }
        self.external_frame = frame;
        Ok(())
    }
    unsafe fn build_external_mesh(&self, command: vk::CommandBuffer, mesh: &ExternalMesh) {
        let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
            .vertex_format(vk::Format::R32G32B32_SFLOAT)
            .vertex_data(vk::DeviceOrHostAddressConstKHR {
                device_address: mesh.vertices.address(),
            })
            .vertex_stride(16)
            .max_vertex(mesh.triangles * 3 - 1)
            .index_type(vk::IndexType::NONE_KHR);
        let geometry = [vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
            .geometry(vk::AccelerationStructureGeometryDataKHR { triangles })];
        let scratch = mesh.blas.scratch.address();
        let aligned = scratch.div_ceil(self.scratch_align) * self.scratch_align;
        let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
            .ty(vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL)
            .flags(vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE)
            .mode(vk::BuildAccelerationStructureModeKHR::BUILD)
            .dst_acceleration_structure(mesh.blas.handle)
            .geometries(&geometry)
            .scratch_data(vk::DeviceOrHostAddressKHR {
                device_address: aligned,
            });
        let range = [vk::AccelerationStructureBuildRangeInfoKHR {
            primitive_count: mesh.triangles,
            ..Default::default()
        }];
        self.acceleration
            .cmd_build_acceleration_structures(command, &[info], &[&range]);
    }
    /// Records pending mesh BLAS builds, the instance upload and the TLAS rebuild.
    /// None means no external instances: trace the world scene's own TLAS.
    pub(crate) unsafe fn prepare_external(
        &mut self,
        command: vk::CommandBuffer,
        scene: &Scene,
    ) -> Result<Option<(vk::AccelerationStructureKHR, Vec<u32>)>, String> {
        if self.external_frame.is_empty() {
            return Ok(None);
        }
        if self.external_tlas.is_none() {
            self.external_tlas = Some(self.scene_factory().external_tlas()?);
        }
        let mut built_any = false;
        for instance in &self.external_frame {
            if !instance.mesh.built.load(Ordering::Acquire) {
                self.build_external_mesh(command, &instance.mesh);
                instance.mesh.built.store(true, Ordering::Release);
                self.external_built.push(instance.mesh.clone());
                built_any = true;
            }
        }
        if built_any {
            self.barrier(command);
        }
        let records = instance_records(scene.blas.address, &self.external_frame);
        let external = self.external_tlas.as_ref().unwrap();
        self.device.cmd_update_buffer(
            command,
            external.instances.handle,
            0,
            std::slice::from_raw_parts(
                records.as_ptr() as *const u8,
                records.len() * INSTANCE_BYTES,
            ),
        );
        self.barrier(command);
        let geometry = [vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::INSTANCES)
            .geometry(vk::AccelerationStructureGeometryDataKHR {
                instances: vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
                    vk::DeviceOrHostAddressConstKHR {
                        device_address: external.instances.address(),
                    },
                ),
            })];
        let scratch = external.tlas.scratch.address();
        let aligned = scratch.div_ceil(self.scratch_align) * self.scratch_align;
        let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
            .ty(vk::AccelerationStructureTypeKHR::TOP_LEVEL)
            .flags(vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE)
            .mode(vk::BuildAccelerationStructureModeKHR::BUILD)
            .dst_acceleration_structure(external.tlas.handle)
            .geometries(&geometry)
            .scratch_data(vk::DeviceOrHostAddressKHR {
                device_address: aligned,
            });
        let range = [vk::AccelerationStructureBuildRangeInfoKHR {
            primitive_count: records.len() as u32,
            ..Default::default()
        }];
        self.acceleration
            .cmd_build_acceleration_structures(command, &[info], &[&range]);
        self.barrier(command);
        Ok(Some((external.tlas.handle, table_entries(&self.external_frame))))
    }
    /// A cancelled recording never ran its BLAS builds; later recordings must rebuild them.
    pub(crate) fn cancel_external(&mut self) {
        for mesh in self.external_built.drain(..) {
            mesh.built.store(false, Ordering::Release);
        }
        self.external_frame.clear();
    }
    pub(crate) fn external_instances(&self) -> usize {
        self.external_frame.len()
    }
    pub(crate) fn release_external(&mut self) {
        self.external_built.clear();
        self.external_frame.clear();
    }
}
