//! Owned ray-query resources recorded on Minecraft's borrowed command buffers.
use ash::vk::Handle;
use ash::{vk, Device, Instance};
use std::collections::VecDeque;
use std::ffi::CString;
use std::mem::size_of;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use std::time::Instant;

pub fn validate_scene(values: &[f32]) -> Result<(), String> {
    if values.is_empty()
        || !values.len().is_multiple_of(24)
        || values.len() / 24 > 1_000_000
        || values.iter().any(|v| !v.is_finite())
    {
        return Err("Invalid triangle scene ABI or geometry budget".into());
    }
    Ok(())
}
pub fn output_bytes(width: u32, height: u32) -> Result<u64, String> {
    let count = (width as u64) * (height as u64);
    if count == 0 || count > 4_194_304 {
        return Err("RT output dimensions exceed budget".into());
    }
    Ok((count * 8).div_ceil(16) * 16 + count * 24)
}
fn error(e: vk::Result) -> String {
    format!("Vulkan RT: {e:?}")
}
struct Buffer {
    device: Device,
    handle: vk::Buffer,
    memory: vk::DeviceMemory,
    size: u64,
}
impl Drop for Buffer {
    fn drop(&mut self) {
        unsafe {
            self.device.destroy_buffer(self.handle, None);
            self.device.free_memory(self.memory, None);
        }
    }
}
impl Buffer {
    unsafe fn new(
        device: &Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        size: u64,
        usage: vk::BufferUsageFlags,
        host: bool,
    ) -> Result<Self, String> {
        let handle = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(size)
                    .usage(usage)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(error)?;
        let req = device.get_buffer_memory_requirements(handle);
        let flags = if host {
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT
        } else {
            vk::MemoryPropertyFlags::DEVICE_LOCAL
        };
        let index = (0..memory.memory_type_count).find(|&i| {
            req.memory_type_bits & (1 << i) != 0
                && memory.memory_types[i as usize]
                    .property_flags
                    .contains(flags)
        });
        let Some(index) = index else {
            device.destroy_buffer(handle, None);
            return Err("No RT buffer memory type".into());
        };
        let mut address =
            vk::MemoryAllocateFlagsInfo::default().flags(vk::MemoryAllocateFlags::DEVICE_ADDRESS);
        let mut allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(req.size)
            .memory_type_index(index);
        if usage.contains(vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS) {
            allocate = allocate.push_next(&mut address);
        }
        let allocation = match device.allocate_memory(&allocate, None) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_buffer(handle, None);
                return Err(error(e));
            }
        };
        if let Err(e) = device.bind_buffer_memory(handle, allocation, 0) {
            device.destroy_buffer(handle, None);
            device.free_memory(allocation, None);
            return Err(error(e));
        }
        Ok(Self {
            device: device.clone(),
            handle,
            memory: allocation,
            size,
        })
    }
    unsafe fn upload<T: Copy>(&self, data: &[T]) -> Result<(), String> {
        let bytes = std::mem::size_of_val(data) as u64;
        if bytes > self.size {
            return Err("RT buffer upload overflow".into());
        }
        let dst = self
            .device
            .map_memory(self.memory, 0, bytes, vk::MemoryMapFlags::empty())
            .map_err(error)?;
        std::ptr::copy_nonoverlapping(data.as_ptr() as *const u8, dst as *mut u8, bytes as usize);
        self.device.unmap_memory(self.memory);
        Ok(())
    }
    unsafe fn address(&self) -> u64 {
        self.device
            .get_buffer_device_address(&vk::BufferDeviceAddressInfo::default().buffer(self.handle))
    }
}
struct Acceleration {
    loader: ash::khr::acceleration_structure::Device,
    handle: vk::AccelerationStructureKHR,
    _storage: Buffer,
    scratch: Buffer,
    address: u64,
}
impl Drop for Acceleration {
    fn drop(&mut self) {
        unsafe {
            self.loader
                .destroy_acceleration_structure(self.handle, None);
        }
    }
}
pub(crate) struct Scene {
    id: u64,
    _vertices: Buffer,
    metadata: Buffer,
    _instances: Buffer,
    blas: Acceleration,
    tlas: Acceleration,
    triangles: u32,
    built: AtomicBool,
}
pub struct RayState {
    device: Device,
    acceleration: ash::khr::acceleration_structure::Device,
    memory: vk::PhysicalDeviceMemoryProperties,
    scratch_align: u64,
    layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    sampler: vk::Sampler,
    uniform: Option<Buffer>,
    output: Option<Buffer>,
    scene: Option<Arc<Scene>>,
    bound_scene: u64,
    tracked: bool,
    owner: Option<Arc<PipelineOwner>>,
    flights: Option<Box<FlightPool>>,
    atlas: u64,
    surface: Option<SurfaceState>,
}
impl Drop for RayState {
    fn drop(&mut self) {
        unsafe {
            self.flights.take();
            self.surface.take();
            self.scene.take();
            self.output.take();
            self.uniform.take();
            if self.owner.is_none() && self.pipeline != vk::Pipeline::null() {
                self.device.destroy_pipeline(self.pipeline, None);
            }
            if self.owner.is_none() && self.pipeline_layout != vk::PipelineLayout::null() {
                self.device
                    .destroy_pipeline_layout(self.pipeline_layout, None);
            }
            if self.pool != vk::DescriptorPool::null() {
                self.device.destroy_descriptor_pool(self.pool, None);
            }
            if self.owner.is_none() && self.layout != vk::DescriptorSetLayout::null() {
                self.device.destroy_descriptor_set_layout(self.layout, None);
            }
            if self.owner.is_none() && self.sampler != vk::Sampler::null() {
                self.device.destroy_sampler(self.sampler, None);
            }
        }
    }
}
impl RayState {
    pub unsafe fn new(
        instance: &Instance,
        device: &Device,
        physical: vk::PhysicalDevice,
    ) -> Result<Self, String> {
        let mut props = vk::PhysicalDeviceAccelerationStructurePropertiesKHR::default();
        let mut properties = vk::PhysicalDeviceProperties2::default().push_next(&mut props);
        instance.get_physical_device_properties2(physical, &mut properties);
        let mut state = Self {
            device: device.clone(),
            acceleration: ash::khr::acceleration_structure::Device::new(instance, device),
            memory: instance.get_physical_device_memory_properties(physical),
            scratch_align: props.min_acceleration_structure_scratch_offset_alignment as u64,
            layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            set: vk::DescriptorSet::null(),
            pipeline_layout: vk::PipelineLayout::null(),
            pipeline: vk::Pipeline::null(),
            sampler: vk::Sampler::null(),
            uniform: None,
            output: None,
            scene: None,
            bound_scene: 0,
            tracked: false,
            owner: None,
            flights: None,
            atlas: 0,
            surface: None,
        };
        let types = [
            vk::DescriptorType::ACCELERATION_STRUCTURE_KHR,
            vk::DescriptorType::STORAGE_BUFFER,
            vk::DescriptorType::STORAGE_BUFFER,
            vk::DescriptorType::UNIFORM_BUFFER,
            vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
        ];
        let bindings: Vec<_> = types
            .iter()
            .enumerate()
            .map(|(i, &t)| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(i as u32)
                    .descriptor_type(t)
                    .descriptor_count(1)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE)
            })
            .collect();
        state.layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(error)?;
        let sizes = [
            vk::DescriptorPoolSize {
                ty: types[0],
                descriptor_count: 1,
            },
            vk::DescriptorPoolSize {
                ty: types[1],
                descriptor_count: 2,
            },
            vk::DescriptorPoolSize {
                ty: types[3],
                descriptor_count: 1,
            },
            vk::DescriptorPoolSize {
                ty: types[4],
                descriptor_count: 1,
            },
        ];
        state.pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .max_sets(1)
                    .pool_sizes(&sizes),
                None,
            )
            .map_err(error)?;
        let layouts = [state.layout];
        state.set = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(state.pool)
                    .set_layouts(&layouts),
            )
            .map_err(error)?[0];
        state.pipeline_layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts),
                None,
            )
            .map_err(error)?;
        let binary = include_bytes!("../shaders/world_rt.spv");
        let words: Vec<u32> = binary
            .chunks_exact(4)
            .map(|v| u32::from_le_bytes(v.try_into().unwrap()))
            .collect();
        let module = device
            .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
            .map_err(error)?;
        let entry = CString::new("main").unwrap();
        let pipeline = vk::ComputePipelineCreateInfo::default()
            .layout(state.pipeline_layout)
            .stage(
                vk::PipelineShaderStageCreateInfo::default()
                    .stage(vk::ShaderStageFlags::COMPUTE)
                    .module(module)
                    .name(&entry),
            );
        let created = device.create_compute_pipelines(vk::PipelineCache::null(), &[pipeline], None);
        device.destroy_shader_module(module, None);
        state.pipeline = match created {
            Ok(p) => p[0],
            Err((pipelines, e)) => {
                for p in pipelines {
                    device.destroy_pipeline(p, None);
                }
                return Err(error(e));
            }
        };
        state.sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .mag_filter(vk::Filter::NEAREST)
                    .min_filter(vk::Filter::NEAREST)
                    .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .max_lod(0.),
                None,
            )
            .map_err(error)?;
        state.uniform = Some(Buffer::new(
            device,
            &state.memory,
            320,
            vk::BufferUsageFlags::UNIFORM_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?);
        state.bind_buffer(
            3,
            state.uniform.as_ref().unwrap(),
            vk::DescriptorType::UNIFORM_BUFFER,
        );
        state.owner = Some(Arc::new(PipelineOwner {
            device: device.clone(),
            layout: state.layout,
            pipeline_layout: state.pipeline_layout,
            pipeline: state.pipeline,
            sampler: state.sampler,
        }));
        Ok(state)
    }
    unsafe fn bind_buffer(&self, binding: u32, buffer: &Buffer, ty: vk::DescriptorType) {
        let info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.handle)
            .offset(0)
            .range(buffer.size)];
        self.device.update_descriptor_sets(
            &[vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(binding)
                .descriptor_type(ty)
                .buffer_info(&info)],
            &[],
        );
    }
    pub fn scene_factory(&self) -> SceneFactory {
        SceneFactory {
            device: self.device.clone(),
            acceleration: self.acceleration.clone(),
            memory: self.memory,
            scratch_align: self.scratch_align,
        }
    }
    pub unsafe fn scene(&mut self, values: &[f32]) -> Result<(), String> {
        validate_scene(values)?;
        rt_wait_idle(&self.device)?;
        self.scene = Some(self.scene_factory().prepare(values)?);
        self.bind_scene();
        if let Some(surface) = self.surface.as_mut() {
            surface.scene_handle = 0;
        }
        Ok(())
    }
    unsafe fn bind_scene(&mut self) {
        let scene = self.scene.as_ref().unwrap();
        self.bind_buffer(1, &scene.metadata, vk::DescriptorType::STORAGE_BUFFER);
        let handles = [scene.tlas.handle];
        let mut accelerations = vk::WriteDescriptorSetAccelerationStructureKHR::default()
            .acceleration_structures(&handles);
        self.device.update_descriptor_sets(
            &[vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
                .descriptor_count(1)
                .push_next(&mut accelerations)],
            &[],
        );
        self.bound_scene = scene.id;
    }
    unsafe fn barrier(&self, command: vk::CommandBuffer) {
        self.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::DependencyFlags::empty(),
            &[vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
                .dst_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)],
            &[],
            &[],
        );
    }
    unsafe fn build(&self, command: vk::CommandBuffer, scene: &Scene) {
        let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
            .vertex_format(vk::Format::R32G32B32_SFLOAT)
            .vertex_data(vk::DeviceOrHostAddressConstKHR {
                device_address: scene._vertices.address(),
            })
            .vertex_stride(16)
            .max_vertex(scene.triangles * 3 - 1)
            .index_type(vk::IndexType::NONE_KHR);
        let geometry = [vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
            .geometry(vk::AccelerationStructureGeometryDataKHR { triangles })];
        let scratch = scene.blas.scratch.address();
        let aligned = scratch.div_ceil(self.scratch_align) * self.scratch_align;
        let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
            .ty(vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL)
            .flags(vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE)
            .mode(vk::BuildAccelerationStructureModeKHR::BUILD)
            .dst_acceleration_structure(scene.blas.handle)
            .geometries(&geometry)
            .scratch_data(vk::DeviceOrHostAddressKHR {
                device_address: aligned,
            });
        let range = [vk::AccelerationStructureBuildRangeInfoKHR {
            primitive_count: scene.triangles,
            ..Default::default()
        }];
        self.acceleration
            .cmd_build_acceleration_structures(command, &[info], &[&range]);
        self.barrier(command);
        let geometry = [vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::INSTANCES)
            .geometry(vk::AccelerationStructureGeometryDataKHR {
                instances: vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
                    vk::DeviceOrHostAddressConstKHR {
                        device_address: scene._instances.address(),
                    },
                ),
            })];
        let scratch = scene.tlas.scratch.address();
        let aligned = scratch.div_ceil(self.scratch_align) * self.scratch_align;
        let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
            .ty(vk::AccelerationStructureTypeKHR::TOP_LEVEL)
            .flags(vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE)
            .mode(vk::BuildAccelerationStructureModeKHR::BUILD)
            .dst_acceleration_structure(scene.tlas.handle)
            .geometries(&geometry)
            .scratch_data(vk::DeviceOrHostAddressKHR {
                device_address: aligned,
            });
        let range = [vk::AccelerationStructureBuildRangeInfoKHR {
            primitive_count: 1,
            ..Default::default()
        }];
        self.acceleration
            .cmd_build_acceleration_structures(command, &[info], &[&range]);
        self.barrier(command);
    }
    pub unsafe fn record(
        &mut self,
        command: vk::CommandBuffer,
        frame: &[f32],
        width: u32,
        height: u32,
        images: [vk::Image; 3],
        atlas: u64,
    ) -> Result<(), String> {
        if let Some(pool) = self.flights.as_mut() {
            if let Some(i) = pool.active {
                let slot = &mut pool.slots[i];
                let was_unbuilt = slot
                    .state
                    .scene
                    .as_ref()
                    .is_some_and(|s| !s.built.load(Ordering::Acquire));
                let result = slot
                    .state
                    .record(command, frame, width, height, images, atlas);
                if was_unbuilt
                    && slot
                        .state
                        .scene
                        .as_ref()
                        .is_some_and(|s| s.built.load(Ordering::Acquire))
                {
                    slot.build_recorded = true;
                }
                return result;
            }
        }

        if frame.len() != 80
            || frame.iter().any(|v| !v.is_finite())
            || images.iter().any(|i| *i == vk::Image::null())
            || atlas == 0
        {
            return Err("Invalid RT frame".into());
        }
        let bytes = output_bytes(width, height)?;
        if self.scene.is_none() {
            return Err("RT scene is unavailable".into());
        }
        if self.output.as_ref().map(|b| b.size) != Some(bytes)
            || self.atlas != atlas
            || self.bound_scene != self.scene.as_ref().unwrap().id
        {
            if !self.tracked {
                rt_wait_idle(&self.device)?;
            }
            if self.bound_scene != self.scene.as_ref().unwrap().id {
                self.bind_scene();
            }
            if self.output.as_ref().map(|b| b.size) != Some(bytes) {
                let buffer = Buffer::new(
                    &self.device,
                    &self.memory,
                    bytes,
                    vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_SRC,
                    false,
                )?;
                self.bind_buffer(2, &buffer, vk::DescriptorType::STORAGE_BUFFER);
                self.output = Some(buffer);
            }
            if self.atlas != atlas {
                let image = [vk::DescriptorImageInfo::default()
                    .sampler(self.sampler)
                    .image_view(vk::ImageView::from_raw(atlas))
                    .image_layout(vk::ImageLayout::GENERAL)];
                self.device.update_descriptor_sets(
                    &[vk::WriteDescriptorSet::default()
                        .dst_set(self.set)
                        .dst_binding(4)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .image_info(&image)],
                    &[],
                );
                self.atlas = atlas;
            }
        }
        self.barrier(command);
        if !self.scene.as_ref().unwrap().built.load(Ordering::Acquire) {
            self.build(command, self.scene.as_ref().unwrap());
            self.scene
                .as_ref()
                .unwrap()
                .built
                .store(true, Ordering::Release);
        }
        self.device.cmd_update_buffer(
            command,
            self.uniform.as_ref().unwrap().handle,
            0,
            std::slice::from_raw_parts(frame.as_ptr() as *const u8, 320),
        );
        self.barrier(command);
        self.device
            .cmd_bind_pipeline(command, vk::PipelineBindPoint::COMPUTE, self.pipeline);
        self.device.cmd_bind_descriptor_sets(
            command,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.set],
            &[],
        );
        self.device
            .cmd_dispatch(command, width.div_ceil(8), height.div_ceil(8), 1);
        self.barrier(command);
        let count = (width as u64) * (height as u64);
        let normal = (count * 8).div_ceil(16) * 16;
        let offsets = [0, normal, normal + count * 16];
        for (i, &image) in images.iter().enumerate() {
            let region = vk::BufferImageCopy::default()
                .buffer_offset(offsets[i])
                .image_subresource(
                    vk::ImageSubresourceLayers::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .layer_count(1),
                )
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                });
            self.device.cmd_copy_buffer_to_image(
                command,
                self.output.as_ref().unwrap().handle,
                image,
                vk::ImageLayout::GENERAL,
                &[region],
            );
        }
        self.barrier(command);
        Ok(())
    }
    pub unsafe fn reset_scene(&mut self) -> Result<(), String> {
        rt_wait_idle(&self.device)?;
        self.scene = None;
        if let Some(surface) = self.surface.as_mut() {
            surface.scene_handle = 0;
            surface.views = [0; 3];
            surface.skin = 0;
        }
        self.atlas = 0;
        Ok(())
    }
}

pub const MAX_ACTORS: usize = 16;
pub const ACTOR_FLOATS: usize = 4 + MAX_ACTORS * 72;
pub const MAX_EXTENDED_ACTORS: usize = 64;
pub const EXTENDED_ACTOR_FLOATS: usize = 4 + MAX_EXTENDED_ACTORS * 72;
const EMPTY_ACTORS: [f32; ACTOR_FLOATS] = [0.; ACTOR_FLOATS];

pub fn validate_surface_actors(data: &[f32]) -> Result<usize, String> {
    validate_actor_payload(data, MAX_ACTORS, ACTOR_FLOATS)
}
pub fn validate_surface_actors_extended(data: &[f32]) -> Result<usize, String> {
    validate_actor_payload(data, MAX_EXTENDED_ACTORS, EXTENDED_ACTOR_FLOATS)
}
pub fn expand_surface_actors(data: &[f32]) -> Result<[f32; EXTENDED_ACTOR_FLOATS], String> {
    validate_surface_actors(data)?;
    let mut full = [0.; EXTENDED_ACTOR_FLOATS];
    full[..ACTOR_FLOATS].copy_from_slice(data);
    Ok(full)
}
fn validate_actor_payload(data: &[f32], max_actors: usize, floats: usize) -> Result<usize, String> {
    if data.len() != floats
        || data.iter().any(|v| !v.is_finite())
        || data[0] < 0.
        || data[0] > max_actors as f32
        || data[0].fract() != 0.
        || data[1..4] != [0.; 3]
    {
        return Err("Invalid surface actor payload".into());
    }
    let count = data[0] as usize;
    for actor in data[4..4 + count * 72].chunks_exact(72) {
        let determinant = actor[0] * (actor[5] * actor[10] - actor[9] * actor[6])
            - actor[4] * (actor[1] * actor[10] - actor[9] * actor[2])
            + actor[8] * (actor[1] * actor[6] - actor[5] * actor[2]);
        if actor[3].abs() > 1e-5
            || actor[7].abs() > 1e-5
            || actor[11].abs() > 1e-5
            || (actor[15] - 1.).abs() > 1e-5
            || !determinant.is_finite()
            || determinant.abs() < 1e-12
            || (0..3).any(|axis| actor[16 + axis] >= actor[20 + axis])
        {
            return Err("Invalid surface actor bounds or affine inverse".into());
        }
    }
    Ok(count)
}

pub struct SurfaceOutputLayout {
    pub reflection_offset: u64,
    pub bytes: u64,
}
pub fn surface_output_layout(width: u32, height: u32) -> Result<SurfaceOutputLayout, String> {
    output_bytes(width, height)?;
    let image_bytes = width as u64 * height as u64 * 8;
    let reflection_offset = image_bytes.div_ceil(16) * 16;
    Ok(SurfaceOutputLayout {
        reflection_offset,
        bytes: reflection_offset + image_bytes,
    })
}
pub fn surface_descriptor_types() -> [vk::DescriptorType; 9] {
    use vk::DescriptorType as D;
    [
        D::ACCELERATION_STRUCTURE_KHR,
        D::STORAGE_BUFFER,
        D::STORAGE_BUFFER,
        D::UNIFORM_BUFFER,
        D::COMBINED_IMAGE_SAMPLER,
        D::COMBINED_IMAGE_SAMPLER,
        D::COMBINED_IMAGE_SAMPLER,
        D::STORAGE_BUFFER,
        D::COMBINED_IMAGE_SAMPLER,
    ]
}

// Owns only compute resources. Geometry and acceleration structures stay in RayState.
struct SurfaceState {
    device: Device,
    memory: vk::PhysicalDeviceMemoryProperties,
    layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    sampler: vk::Sampler,
    uniform: Option<Buffer>,
    output: Option<Buffer>,
    actors: Option<Buffer>,
    skin: u64,
    views: [u64; 3],
    scene_handle: u64,
    tracked: bool,
    owner: Option<Arc<PipelineOwner>>,
}
impl Drop for SurfaceState {
    fn drop(&mut self) {
        unsafe {
            self.output.take();
            self.actors.take();
            self.uniform.take();
            if self.owner.is_none() && self.pipeline != vk::Pipeline::null() {
                self.device.destroy_pipeline(self.pipeline, None);
            }
            if self.owner.is_none() && self.pipeline_layout != vk::PipelineLayout::null() {
                self.device
                    .destroy_pipeline_layout(self.pipeline_layout, None);
            }
            if self.pool != vk::DescriptorPool::null() {
                self.device.destroy_descriptor_pool(self.pool, None);
            }
            if self.owner.is_none() && self.layout != vk::DescriptorSetLayout::null() {
                self.device.destroy_descriptor_set_layout(self.layout, None);
            }
            if self.owner.is_none() && self.sampler != vk::Sampler::null() {
                self.device.destroy_sampler(self.sampler, None);
            }
        }
    }
}
impl SurfaceState {
    unsafe fn new(
        device: &Device,
        memory: vk::PhysicalDeviceMemoryProperties,
    ) -> Result<Self, String> {
        let mut state = Self {
            device: device.clone(),
            memory,
            layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            set: vk::DescriptorSet::null(),
            pipeline_layout: vk::PipelineLayout::null(),
            pipeline: vk::Pipeline::null(),
            sampler: vk::Sampler::null(),
            uniform: None,
            output: None,
            actors: None,
            skin: 0,
            views: [0; 3],
            scene_handle: 0,
            tracked: false,
            owner: None,
        };
        let types = surface_descriptor_types();
        let bindings: Vec<_> = types
            .iter()
            .enumerate()
            .map(|(i, &t)| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(i as u32)
                    .descriptor_type(t)
                    .descriptor_count(1)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE)
            })
            .collect();
        state.layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(error)?;
        let sizes = [
            vk::DescriptorPoolSize {
                ty: types[0],
                descriptor_count: 1,
            },
            vk::DescriptorPoolSize {
                ty: types[1],
                descriptor_count: 3,
            },
            vk::DescriptorPoolSize {
                ty: types[3],
                descriptor_count: 1,
            },
            vk::DescriptorPoolSize {
                ty: types[4],
                descriptor_count: 4,
            },
        ];
        state.pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .max_sets(1)
                    .pool_sizes(&sizes),
                None,
            )
            .map_err(error)?;
        let layouts = [state.layout];
        state.set = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(state.pool)
                    .set_layouts(&layouts),
            )
            .map_err(error)?[0];
        state.pipeline_layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts),
                None,
            )
            .map_err(error)?;
        let binary = include_bytes!("../shaders/surface_rt.spv");
        let words: Vec<u32> = binary
            .chunks_exact(4)
            .map(|v| u32::from_le_bytes(v.try_into().unwrap()))
            .collect();
        let module = device
            .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
            .map_err(error)?;
        let entry = CString::new("main").unwrap();
        let pipeline = vk::ComputePipelineCreateInfo::default()
            .layout(state.pipeline_layout)
            .stage(
                vk::PipelineShaderStageCreateInfo::default()
                    .stage(vk::ShaderStageFlags::COMPUTE)
                    .module(module)
                    .name(&entry),
            );
        let created = device.create_compute_pipelines(vk::PipelineCache::null(), &[pipeline], None);
        device.destroy_shader_module(module, None);
        state.pipeline = match created {
            Ok(p) => p[0],
            Err((pipelines, e)) => {
                for p in pipelines {
                    device.destroy_pipeline(p, None);
                }
                return Err(error(e));
            }
        };
        state.sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .mag_filter(vk::Filter::NEAREST)
                    .min_filter(vk::Filter::NEAREST)
                    .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .max_lod(0.),
                None,
            )
            .map_err(error)?;
        state.uniform = Some(Buffer::new(
            device,
            &state.memory,
            320,
            vk::BufferUsageFlags::UNIFORM_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?);
        state.bind_buffer(
            3,
            state.uniform.as_ref().unwrap(),
            vk::DescriptorType::UNIFORM_BUFFER,
        );
        state.init_actors()?;
        state.owner = Some(Arc::new(PipelineOwner {
            device: device.clone(),
            layout: state.layout,
            pipeline_layout: state.pipeline_layout,
            pipeline: state.pipeline,
            sampler: state.sampler,
        }));
        Ok(state)
    }
    unsafe fn init_actors(&mut self) -> Result<(), String> {
        let buffer = Buffer::new(
            &self.device,
            &self.memory,
            (EXTENDED_ACTOR_FLOATS * 4) as u64,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?;
        self.bind_buffer(7, &buffer, vk::DescriptorType::STORAGE_BUFFER);
        self.actors = Some(buffer);
        Ok(())
    }
    unsafe fn bind_buffer(&self, binding: u32, buffer: &Buffer, ty: vk::DescriptorType) {
        let info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.handle)
            .offset(0)
            .range(buffer.size)];
        self.device.update_descriptor_sets(
            &[vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(binding)
                .descriptor_type(ty)
                .buffer_info(&info)],
            &[],
        );
    }
}

impl RayState {
    pub unsafe fn init_surface(&mut self) -> Result<(), String> {
        if self.surface.is_none() {
            self.surface = Some(SurfaceState::new(&self.device, self.memory)?);
        }
        Ok(())
    }
    pub unsafe fn record_surface(
        &mut self,
        command: vk::CommandBuffer,
        frame: &[f32],
        width: u32,
        height: u32,
        images: [vk::Image; 2],
        views: [u64; 3],
    ) -> Result<(), String> {
        self.record_surface_actors(
            command,
            frame,
            width,
            height,
            images,
            views,
            &EMPTY_ACTORS,
            0,
        )
    }
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn record_surface_actors(
        &mut self,
        command: vk::CommandBuffer,
        frame: &[f32],
        width: u32,
        height: u32,
        images: [vk::Image; 2],
        views: [u64; 3],
        actors: &[f32],
        skin: u64,
    ) -> Result<(), String> {
        let full = expand_surface_actors(actors)?;
        self.record_surface_actors_extended(
            command, frame, width, height, images, views, &full, skin,
        )
    }
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn record_surface_actors_extended(
        &mut self,
        command: vk::CommandBuffer,
        frame: &[f32],
        width: u32,
        height: u32,
        images: [vk::Image; 2],
        views: [u64; 3],
        actors: &[f32],
        skin: u64,
    ) -> Result<(), String> {
        let actor_count = validate_surface_actors_extended(actors)?;
        if actor_count > 0 && skin == 0 {
            return Err("Missing borrowed actor skin view".into());
        }
        if let Some(pool) = self.flights.as_mut() {
            if let Some(i) = pool.active {
                let slot = &mut pool.slots[i];
                let was_unbuilt = slot
                    .state
                    .scene
                    .as_ref()
                    .is_some_and(|s| !s.built.load(Ordering::Acquire));
                let result = slot.state.record_surface_actors_extended(
                    command, frame, width, height, images, views, actors, skin,
                );
                if was_unbuilt
                    && slot
                        .state
                        .scene
                        .as_ref()
                        .is_some_and(|s| s.built.load(Ordering::Acquire))
                {
                    slot.build_recorded = true;
                }
                return result;
            }
        }

        validate_surface_frame(frame, width, height)?;
        if self.surface.is_none() || self.scene.is_none() {
            return Err("Surface RT pipeline or shared scene is unavailable".into());
        }
        self.barrier(command);
        if !self.scene.as_ref().unwrap().built.load(Ordering::Acquire) {
            self.build(command, self.scene.as_ref().unwrap());
            self.scene
                .as_ref()
                .unwrap()
                .built
                .store(true, Ordering::Release);
        }
        self.surface.as_mut().unwrap().record(
            command,
            self.scene.as_ref().unwrap(),
            frame,
            width,
            height,
            images,
            views,
            actors,
            if actor_count == 0 { views[0] } else { skin },
        )?;
        self.barrier(command);
        Ok(())
    }
}

pub fn validate_surface_frame(frame: &[f32], width: u32, height: u32) -> Result<(), String> {
    surface_output_layout(width, height)?;
    if frame.len() != 80
        || frame.iter().any(|v| !v.is_finite())
        || frame[68] != width as f32
        || frame[69] != height as f32
    {
        return Err("Invalid surface RT frame or viewport".into());
    }
    Ok(())
}

impl SurfaceState {
    #[allow(clippy::too_many_arguments)]
    unsafe fn record(
        &mut self,
        command: vk::CommandBuffer,
        scene: &Scene,
        frame: &[f32],
        width: u32,
        height: u32,
        images: [vk::Image; 2],
        views: [u64; 3],
        actors: &[f32],
        skin: u64,
    ) -> Result<(), String> {
        if command == vk::CommandBuffer::null()
            || images.contains(&vk::Image::null())
            || images[0] == images[1]
            || views.contains(&0)
        {
            return Err("Invalid borrowed surface RT handles".into());
        }
        let layout = surface_output_layout(width, height)?;
        if self.output.as_ref().map(|b| b.size) != Some(layout.bytes)
            || self.views != views
            || self.scene_handle != scene.id
            || self.skin != skin
        {
            // Descriptor sets cannot be rewritten while previous submissions use them.
            if !self.tracked {
                rt_wait_idle(&self.device)?;
            }
            if self.skin != skin {
                // Flight leases prevent pending descriptor mutation. A second skin in the
                // same recording lease would also rewrite the first dispatch's descriptor.
                if self.tracked && self.skin != 0 {
                    return Err("Actor skin changed within a recording flight".into());
                }
                let info = [vk::DescriptorImageInfo::default()
                    .sampler(self.sampler)
                    .image_view(vk::ImageView::from_raw(skin))
                    .image_layout(vk::ImageLayout::GENERAL)];
                self.device.update_descriptor_sets(
                    &[vk::WriteDescriptorSet::default()
                        .dst_set(self.set)
                        .dst_binding(8)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .image_info(&info)],
                    &[],
                );
                self.skin = skin;
            }
            if self.output.as_ref().map(|b| b.size) != Some(layout.bytes) {
                let buffer = Buffer::new(
                    &self.device,
                    &self.memory,
                    layout.bytes,
                    vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_SRC,
                    false,
                )?;
                self.bind_buffer(2, &buffer, vk::DescriptorType::STORAGE_BUFFER);
                self.output = Some(buffer);
            }
            if self.scene_handle != scene.id {
                self.bind_buffer(1, &scene.metadata, vk::DescriptorType::STORAGE_BUFFER);
                let handles = [scene.tlas.handle];
                let mut acceleration = vk::WriteDescriptorSetAccelerationStructureKHR::default()
                    .acceleration_structures(&handles);
                self.device.update_descriptor_sets(
                    &[vk::WriteDescriptorSet::default()
                        .dst_set(self.set)
                        .dst_binding(0)
                        .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
                        .descriptor_count(1)
                        .push_next(&mut acceleration)],
                    &[],
                );
                self.scene_handle = scene.id;
            }
            if self.views != views {
                for (i, view) in views.iter().enumerate() {
                    let info = [vk::DescriptorImageInfo::default()
                        .sampler(self.sampler)
                        .image_view(vk::ImageView::from_raw(*view))
                        .image_layout(vk::ImageLayout::GENERAL)];
                    self.device.update_descriptor_sets(
                        &[vk::WriteDescriptorSet::default()
                            .dst_set(self.set)
                            .dst_binding(4 + i as u32)
                            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                            .image_info(&info)],
                        &[],
                    );
                }
                self.views = views;
            }
        }
        // vkCmdUpdateBuffer snapshots CPU bytes into the recorded command. This
        // slot's device-local buffer is not shared with any pending flight.
        self.device.cmd_update_buffer(
            command,
            self.actors.as_ref().unwrap().handle,
            0,
            std::slice::from_raw_parts(actors.as_ptr() as *const u8, EXTENDED_ACTOR_FLOATS * 4),
        );
        self.device.cmd_update_buffer(
            command,
            self.uniform.as_ref().unwrap().handle,
            0,
            std::slice::from_raw_parts(frame.as_ptr() as *const u8, 320),
        );
        self.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::MEMORY_WRITE)
                .dst_access_mask(
                    vk::AccessFlags::UNIFORM_READ
                        | vk::AccessFlags::SHADER_READ
                        | vk::AccessFlags::SHADER_WRITE,
                )],
            &[],
            &[],
        );
        self.device
            .cmd_bind_pipeline(command, vk::PipelineBindPoint::COMPUTE, self.pipeline);
        self.device.cmd_bind_descriptor_sets(
            command,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.set],
            &[],
        );
        self.device
            .cmd_dispatch(command, width.div_ceil(8), height.div_ceil(8), 1);
        self.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)],
            &[],
            &[],
        );
        for (i, image) in images.iter().enumerate() {
            let region = vk::BufferImageCopy::default()
                .buffer_offset(if i == 0 { 0 } else { layout.reflection_offset })
                .image_subresource(
                    vk::ImageSubresourceLayers::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .layer_count(1),
                )
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                });
            self.device.cmd_copy_buffer_to_image(
                command,
                self.output.as_ref().unwrap().handle,
                *image,
                vk::ImageLayout::GENERAL,
                &[region],
            );
        }
        Ok(())
    }
}

#[cfg(test)]
#[path = "surface_gpu_tests.rs"]
mod surface_gpu_tests;

static SCENE_ID: AtomicU64 = AtomicU64::new(1);
#[derive(Clone)]
pub(crate) struct SceneFactory {
    device: Device,
    acceleration: ash::khr::acceleration_structure::Device,
    memory: vk::PhysicalDeviceMemoryProperties,
    scratch_align: u64,
}
impl SceneFactory {
    unsafe fn acceleration(
        &self,
        ty: vk::AccelerationStructureTypeKHR,
        geometry: vk::AccelerationStructureGeometryKHR<'_>,
        count: u32,
    ) -> Result<Acceleration, String> {
        let geometries = [geometry];
        let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
            .ty(ty)
            .flags(vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE)
            .geometries(&geometries);
        let mut sizes = vk::AccelerationStructureBuildSizesInfoKHR::default();
        self.acceleration.get_acceleration_structure_build_sizes(
            vk::AccelerationStructureBuildTypeKHR::DEVICE,
            &info,
            &[count],
            &mut sizes,
        );
        let storage = Buffer::new(
            &self.device,
            &self.memory,
            sizes.acceleration_structure_size,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_STORAGE_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            false,
        )?;
        let handle = self
            .acceleration
            .create_acceleration_structure(
                &vk::AccelerationStructureCreateInfoKHR::default()
                    .buffer(storage.handle)
                    .size(sizes.acceleration_structure_size)
                    .ty(ty),
                None,
            )
            .map_err(error)?;
        let scratch = match Buffer::new(
            &self.device,
            &self.memory,
            sizes.build_scratch_size + self.scratch_align,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            false,
        ) {
            Ok(b) => b,
            Err(e) => {
                self.acceleration
                    .destroy_acceleration_structure(handle, None);
                return Err(e);
            }
        };
        let address = self.acceleration.get_acceleration_structure_device_address(
            &vk::AccelerationStructureDeviceAddressInfoKHR::default()
                .acceleration_structure(handle),
        );
        Ok(Acceleration {
            loader: self.acceleration.clone(),
            handle,
            _storage: storage,
            scratch,
            address,
        })
    }
    pub unsafe fn prepare(&self, values: &[f32]) -> Result<Arc<Scene>, String> {
        validate_scene(values)?;
        let count = (values.len() / 24) as u32;
        let mut vertices = Vec::<f32>::with_capacity(count as usize * 12);
        for t in values.chunks_exact(24) {
            vertices.extend_from_slice(&t[..12]);
        }
        let vertex = Buffer::new(
            &self.device,
            &self.memory,
            (vertices.len() * 4) as u64,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            true,
        )?;
        vertex.upload(&vertices)?;
        let metadata = Buffer::new(
            &self.device,
            &self.memory,
            (values.len() * 4) as u64,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            true,
        )?;
        metadata.upload(values)?;
        let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
            .vertex_format(vk::Format::R32G32B32_SFLOAT)
            .vertex_data(vk::DeviceOrHostAddressConstKHR {
                device_address: vertex.address(),
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
        let instance = vk::AccelerationStructureInstanceKHR {
            transform: vk::TransformMatrixKHR {
                matrix: [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.],
            },
            instance_custom_index_and_mask: vk::Packed24_8::new(0, 255),
            instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(
                0,
                vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE.as_raw() as u8,
            ),
            acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                device_handle: blas.address,
            },
        };
        let instances = Buffer::new(
            &self.device,
            &self.memory,
            size_of::<vk::AccelerationStructureInstanceKHR>() as u64,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            true,
        )?;
        instances.upload(&[instance])?;
        let geometry = vk::AccelerationStructureGeometryKHR::default()
            .geometry_type(vk::GeometryTypeKHR::INSTANCES)
            .geometry(vk::AccelerationStructureGeometryDataKHR {
                instances: vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
                    vk::DeviceOrHostAddressConstKHR {
                        device_address: instances.address(),
                    },
                ),
            });
        let tlas = self.acceleration(vk::AccelerationStructureTypeKHR::TOP_LEVEL, geometry, 1)?;
        let scene = Scene {
            id: SCENE_ID.fetch_add(1, Ordering::Relaxed),
            _vertices: vertex,
            metadata,
            _instances: instances,
            blas,
            tlas,
            triangles: count,
            built: AtomicBool::new(false),
        };
        Ok(Arc::new(scene))
    }
}

struct PipelineOwner {
    device: Device,
    layout: vk::DescriptorSetLayout,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    sampler: vk::Sampler,
}
impl Drop for PipelineOwner {
    fn drop(&mut self) {
        unsafe {
            self.device.destroy_pipeline(self.pipeline, None);
            self.device
                .destroy_pipeline_layout(self.pipeline_layout, None);
            self.device.destroy_descriptor_set_layout(self.layout, None);
            self.device.destroy_sampler(self.sampler, None);
        }
    }
}
unsafe fn flight_set(
    device: &Device,
    layout: vk::DescriptorSetLayout,
    surface: bool,
) -> Result<(vk::DescriptorPool, vk::DescriptorSet), String> {
    let sizes = [
        vk::DescriptorPoolSize {
            ty: vk::DescriptorType::ACCELERATION_STRUCTURE_KHR,
            descriptor_count: 1,
        },
        vk::DescriptorPoolSize {
            ty: vk::DescriptorType::STORAGE_BUFFER,
            descriptor_count: if surface { 3 } else { 2 },
        },
        vk::DescriptorPoolSize {
            ty: vk::DescriptorType::UNIFORM_BUFFER,
            descriptor_count: 1,
        },
        vk::DescriptorPoolSize {
            ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
            descriptor_count: if surface { 4 } else { 1 },
        },
    ];
    let pool = device
        .create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .max_sets(1)
                .pool_sizes(&sizes),
            None,
        )
        .map_err(error)?;
    match device.allocate_descriptor_sets(
        &vk::DescriptorSetAllocateInfo::default()
            .descriptor_pool(pool)
            .set_layouts(&[layout]),
    ) {
        Ok(sets) => Ok((pool, sets[0])),
        Err(e) => {
            device.destroy_descriptor_pool(pool, None);
            Err(error(e))
        }
    }
}
impl SurfaceState {
    unsafe fn new_flight(&self) -> Result<Self, String> {
        let mut state = Self {
            device: self.device.clone(),
            memory: self.memory,
            layout: self.layout,
            pool: vk::DescriptorPool::null(),
            set: vk::DescriptorSet::null(),
            pipeline_layout: self.pipeline_layout,
            pipeline: self.pipeline,
            sampler: self.sampler,
            uniform: None,
            output: None,
            actors: None,
            skin: 0,
            views: [0; 3],
            scene_handle: 0,
            tracked: true,
            owner: self.owner.clone(),
        };
        (state.pool, state.set) = flight_set(&state.device, state.layout, true)?;
        state.uniform = Some(Buffer::new(
            &state.device,
            &state.memory,
            320,
            vk::BufferUsageFlags::UNIFORM_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?);
        state.bind_buffer(
            3,
            state.uniform.as_ref().unwrap(),
            vk::DescriptorType::UNIFORM_BUFFER,
        );
        state.init_actors()?;
        Ok(state)
    }
}
const MAX_FLIGHTS: usize = 4;
const MAX_RETIRED: usize = 4;
const MAX_SCENE_BYTES: u64 = 512 * 1024 * 1024;
#[derive(Clone, Copy, PartialEq, Eq)]
enum LeaseStatus {
    Free,
    Recording,
    Submitted,
}
struct Flight {
    state: RayState,
    serial: u64,
    status: LeaseStatus,
    build_recorded: bool,
}
struct FlightPool {
    slots: Vec<Flight>,
    retired: VecDeque<Arc<Scene>>,
    active: Option<usize>,
    last_serial: u64,
    completed: u64,
    publish_count: u64,
    publish_nanos: u64,
    busy_count: u64,
    cleanup_nanos: u64,
}
impl Default for FlightPool {
    fn default() -> Self {
        Self {
            slots: Vec::with_capacity(MAX_FLIGHTS),
            retired: VecDeque::with_capacity(MAX_RETIRED + 1),
            active: None,
            last_serial: 0,
            completed: 0,
            publish_count: 0,
            publish_nanos: 0,
            busy_count: 0,
            cleanup_nanos: 0,
        }
    }
}
impl Scene {
    fn bytes(&self) -> u64 {
        self._vertices.size
            + self.metadata.size
            + self._instances.size
            + self.blas._storage.size
            + self.blas.scratch.size
            + self.tlas._storage.size
            + self.tlas.scratch.size
    }
}
impl FlightPool {
    fn collect(&mut self) {
        let start = Instant::now();
        self.retired.retain(|scene| Arc::strong_count(scene) > 1);
        self.cleanup_nanos += start.elapsed().as_nanos() as u64;
    }
    fn busy(&mut self) -> i32 {
        self.busy_count += 1;
        1
    }
}
impl RayState {
    unsafe fn new_flight(&self) -> Result<Self, String> {
        let mut state = Self {
            device: self.device.clone(),
            acceleration: self.acceleration.clone(),
            memory: self.memory,
            scratch_align: self.scratch_align,
            layout: self.layout,
            pool: vk::DescriptorPool::null(),
            set: vk::DescriptorSet::null(),
            pipeline_layout: self.pipeline_layout,
            pipeline: self.pipeline,
            sampler: self.sampler,
            uniform: None,
            output: None,
            scene: None,
            bound_scene: 0,
            tracked: true,
            owner: self.owner.clone(),
            flights: None,
            atlas: 0,
            surface: None,
        };
        (state.pool, state.set) = flight_set(&state.device, state.layout, false)?;
        state.uniform = Some(Buffer::new(
            &state.device,
            &state.memory,
            320,
            vk::BufferUsageFlags::UNIFORM_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?);
        state.bind_buffer(
            3,
            state.uniform.as_ref().unwrap(),
            vk::DescriptorType::UNIFORM_BUFFER,
        );
        if let Some(surface) = self.surface.as_ref() {
            state.surface = Some(surface.new_flight()?);
        }
        Ok(state)
    }
    pub unsafe fn begin_submission(&mut self, serial: u64) -> Result<i32, String> {
        if serial == 0 {
            return Err("Invalid flight serial".into());
        }
        if self.flights.is_none() {
            self.flights = Some(Box::default());
        }
        let pool = self.flights.as_ref().unwrap();
        if serial <= pool.last_serial {
            return Err("Flight serial must increase".into());
        }
        if pool.active.is_some() {
            return Ok(self.flights.as_mut().unwrap().busy());
        }
        let index = pool
            .slots
            .iter()
            .position(|f| f.status == LeaseStatus::Free);
        let index = match index {
            Some(i) => i,
            None if pool.slots.len() < MAX_FLIGHTS => {
                let state = self.new_flight()?;
                let pool = self.flights.as_mut().unwrap();
                let i = pool.slots.len();
                pool.slots.push(Flight {
                    state,
                    serial: 0,
                    status: LeaseStatus::Free,
                    build_recorded: false,
                });
                i
            }
            None => return Ok(self.flights.as_mut().unwrap().busy()),
        };
        // A free slot has no pending GPU users. Its descriptors may now be updated.
        let surface = if self.flights.as_ref().unwrap().slots[index]
            .state
            .surface
            .is_none()
        {
            self.surface.as_ref().map(|s| s.new_flight()).transpose()?
        } else {
            None
        };
        let pool = self.flights.as_mut().unwrap();
        let slot = &mut pool.slots[index];
        if let Some(surface) = surface {
            slot.state.surface = Some(surface);
        }
        slot.state.scene = self.scene.clone();
        slot.state.bound_scene = 0;
        slot.state.atlas = 0;
        if let Some(surface) = slot.state.surface.as_mut() {
            surface.scene_handle = 0;
            surface.views = [0; 3];
            surface.skin = 0;
        }
        slot.serial = serial;
        slot.status = LeaseStatus::Recording;
        slot.build_recorded = false;
        pool.active = Some(index);
        pool.last_serial = serial;
        Ok(0)
    }
    pub fn submission_submitted(&mut self, serial: u64) -> Result<i32, String> {
        let pool = self.flights.as_mut().ok_or("Async pool is unavailable")?;
        let i = pool.active.ok_or("No recording flight")?;
        if pool.slots[i].serial != serial {
            return Err("Wrong submitted serial".into());
        }
        pool.slots[i].status = LeaseStatus::Submitted;
        pool.active = None;
        Ok(0)
    }
    pub fn submission_completed(&mut self, serial: u64) -> Result<i32, String> {
        let pool = self.flights.as_mut().ok_or("Async pool is unavailable")?;
        if serial <= pool.completed {
            return Ok(0);
        }
        let first = pool
            .slots
            .iter()
            .filter(|f| f.status == LeaseStatus::Submitted)
            .min_by_key(|f| f.serial)
            .map(|f| f.serial)
            .ok_or("No submitted flight")?;
        if serial != first {
            return Err("Completion must reclaim submitted flights in FIFO order".into());
        }
        let slot = pool
            .slots
            .iter_mut()
            .find(|f| f.serial == serial && f.status == LeaseStatus::Submitted)
            .unwrap();
        slot.state.scene = None;
        slot.status = LeaseStatus::Free;
        slot.build_recorded = false;
        pool.completed = serial;
        pool.collect();
        Ok(0)
    }
    pub fn submission_cancel(&mut self, serial: u64) -> Result<i32, String> {
        let pool = self.flights.as_mut().ok_or("Async pool is unavailable")?;
        let i = pool.active.ok_or("No recording flight to cancel")?;
        let slot = &mut pool.slots[i];
        if slot.serial != serial {
            return Err("Wrong cancelled serial".into());
        }
        if slot.build_recorded {
            if let Some(scene) = slot.state.scene.as_ref() {
                scene.built.store(false, Ordering::Release);
            }
        }
        slot.state.scene = None;
        slot.status = LeaseStatus::Free;
        slot.build_recorded = false;
        pool.active = None;
        pool.collect();
        Ok(0)
    }
    pub fn publish_scene(&mut self, scene: Arc<Scene>) -> Result<i32, String> {
        let started = Instant::now();
        if self.flights.is_none() {
            self.flights = Some(Box::default());
        }
        let pool = self.flights.as_mut().unwrap();
        pool.collect();
        if pool.active.is_some()
            || pool.retired.len() >= MAX_RETIRED
            || scene.bytes()
                + self.scene.as_ref().map_or(0, |s| s.bytes())
                + pool.retired.iter().map(|s| s.bytes()).sum::<u64>()
                > MAX_SCENE_BYTES
        {
            return Ok(pool.busy());
        }
        if let Some(old) = self.scene.replace(scene) {
            pool.retired.push_back(old);
        }
        pool.publish_count += 1;
        pool.publish_nanos += started.elapsed().as_nanos() as u64;
        Ok(0)
    }
    pub fn reset_scene_async(&mut self) -> Result<i32, String> {
        if let Some(pool) = self.flights.as_mut() {
            if pool.active.is_some() {
                return Ok(pool.busy());
            }
            if let Some(old) = self.scene.take() {
                pool.retired.push_back(old);
            }
            pool.collect();
        } else {
            self.scene = None;
        }
        Ok(0)
    }
    pub fn async_stats(&self) -> [u64; 9] {
        self.flights.as_ref().map_or([0; 9], |p| {
            [
                p.publish_count,
                p.publish_nanos,
                p.slots.len() as u64,
                p.slots
                    .iter()
                    .filter(|f| f.status != LeaseStatus::Free)
                    .count() as u64,
                p.retired.len() as u64,
                p.busy_count,
                p.retired.iter().map(|s| s.bytes()).sum(),
                self.scene.as_ref().map_or(0, |s| s.bytes()),
                p.cleanup_nanos,
            ]
        })
    }
}

static RT_IDLE_COUNT: AtomicU64 = AtomicU64::new(0);
static RT_IDLE_NANOS: AtomicU64 = AtomicU64::new(0);
unsafe fn rt_wait_idle(device: &Device) -> Result<(), String> {
    RT_IDLE_COUNT.fetch_add(1, Ordering::Relaxed);
    let start = Instant::now();
    let result = device.device_wait_idle().map_err(error);
    RT_IDLE_NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
    result
}
pub fn idle_stats() -> [u64; 2] {
    [
        RT_IDLE_COUNT.load(Ordering::Relaxed),
        RT_IDLE_NANOS.load(Ordering::Relaxed),
    ]
}
