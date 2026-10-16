//! Explicit headless GPU acceptance; no Minecraft handles or installed artifacts.
use super::*;
use ash::Entry;
use std::ffi::CStr;
static GPU_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

struct Host {
    _entry: Entry,
    instance: Instance,
    device: Device,
    physical: vk::PhysicalDevice,
    memory: vk::PhysicalDeviceMemoryProperties,
    queue: vk::Queue,
    pool: vk::CommandPool,
    command: vk::CommandBuffer,
}
impl Host {
    unsafe fn new() -> Self {
        let entry = Entry::load().expect("Vulkan loader");
        let app = vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_2);
        let instance = entry
            .create_instance(
                &vk::InstanceCreateInfo::default().application_info(&app),
                None,
            )
            .unwrap();
        let physical = instance
            .enumerate_physical_devices()
            .unwrap()
            .into_iter()
            .find(|&p| {
                let props = instance.get_physical_device_properties(p);
                CStr::from_ptr(props.device_name.as_ptr())
                    .to_string_lossy()
                    .contains("RX 9070 XT")
            })
            .expect("Acceptance requires actual RX 9070 XT");
        let mut driver = vk::PhysicalDeviceDriverProperties::default();
        let mut props = vk::PhysicalDeviceProperties2::default().push_next(&mut driver);
        instance.get_physical_device_properties2(physical, &mut props);
        println!(
            "surface_gpu device={} driver={} info={}",
            CStr::from_ptr(props.properties.device_name.as_ptr()).to_string_lossy(),
            CStr::from_ptr(driver.driver_name.as_ptr()).to_string_lossy(),
            CStr::from_ptr(driver.driver_info.as_ptr()).to_string_lossy()
        );
        let family = instance
            .get_physical_device_queue_family_properties(physical)
            .iter()
            .position(|p| {
                p.queue_flags
                    .contains(vk::QueueFlags::GRAPHICS | vk::QueueFlags::COMPUTE)
            })
            .unwrap() as u32;
        let priority = [1.0];
        let queue_info = [vk::DeviceQueueCreateInfo::default()
            .queue_family_index(family)
            .queue_priorities(&priority)];
        let extensions = [
            ash::khr::acceleration_structure::NAME.as_ptr(),
            ash::khr::deferred_host_operations::NAME.as_ptr(),
            ash::khr::ray_query::NAME.as_ptr(),
        ];
        let mut acceleration = vk::PhysicalDeviceAccelerationStructureFeaturesKHR::default()
            .acceleration_structure(true);
        let mut ray = vk::PhysicalDeviceRayQueryFeaturesKHR::default().ray_query(true);
        let mut v12 = vk::PhysicalDeviceVulkan12Features::default().buffer_device_address(true);
        let device = instance
            .create_device(
                physical,
                &vk::DeviceCreateInfo::default()
                    .queue_create_infos(&queue_info)
                    .enabled_extension_names(&extensions)
                    .push_next(&mut acceleration)
                    .push_next(&mut ray)
                    .push_next(&mut v12),
                None,
            )
            .unwrap();
        let queue = device.get_device_queue(family, 0);
        let pool = device
            .create_command_pool(
                &vk::CommandPoolCreateInfo::default()
                    .queue_family_index(family)
                    .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                None,
            )
            .unwrap();
        let command = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .unwrap()[0];
        let memory = instance.get_physical_device_memory_properties(physical);
        Self {
            _entry: entry,
            instance,
            device,
            physical,
            memory,
            queue,
            pool,
            command,
        }
    }
    unsafe fn begin(&self) {
        self.device
            .reset_command_buffer(self.command, vk::CommandBufferResetFlags::empty())
            .unwrap();
        self.device
            .begin_command_buffer(self.command, &vk::CommandBufferBeginInfo::default())
            .unwrap();
    }
    unsafe fn submit(&self) {
        self.device.end_command_buffer(self.command).unwrap();
        let commands = [self.command];
        self.device
            .queue_submit(
                self.queue,
                &[vk::SubmitInfo::default().command_buffers(&commands)],
                vk::Fence::null(),
            )
            .unwrap();
        self.device.queue_wait_idle(self.queue).unwrap();
    }
}
impl Drop for Host {
    fn drop(&mut self) {
        unsafe {
            let _ = self.device.device_wait_idle();
            self.device.destroy_command_pool(self.pool, None);
            self.device.destroy_device(None);
            self.instance.destroy_instance(None);
        }
    }
}
struct Image {
    device: Device,
    handle: vk::Image,
    view: vk::ImageView,
    memory: vk::DeviceMemory,
}
impl Image {
    unsafe fn new(host: &Host, width: u32, height: u32, format: vk::Format, sampled: bool) -> Self {
        let mut usage = vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::TRANSFER_SRC;
        if sampled {
            usage |= vk::ImageUsageFlags::SAMPLED;
        }
        let handle = host
            .device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(format)
                    .extent(vk::Extent3D {
                        width,
                        height,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(1)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(usage)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .unwrap();
        let req = host.device.get_image_memory_requirements(handle);
        let index = (0..host.memory.memory_type_count)
            .find(|&i| {
                req.memory_type_bits & (1 << i) != 0
                    && host.memory.memory_types[i as usize]
                        .property_flags
                        .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
            })
            .unwrap();
        let memory = host
            .device
            .allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(req.size)
                    .memory_type_index(index),
                None,
            )
            .unwrap();
        host.device.bind_image_memory(handle, memory, 0).unwrap();
        let view = if sampled {
            host.device
                .create_image_view(
                    &vk::ImageViewCreateInfo::default()
                        .image(handle)
                        .view_type(vk::ImageViewType::TYPE_2D)
                        .format(format)
                        .subresource_range(range()),
                    None,
                )
                .unwrap()
        } else {
            vk::ImageView::null()
        };
        Self {
            device: host.device.clone(),
            handle,
            view,
            memory,
        }
    }
    unsafe fn initialize(&self, command: vk::CommandBuffer, color: [f32; 4]) {
        self.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::TOP_OF_PIPE,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &[vk::ImageMemoryBarrier::default()
                .image(self.handle)
                .subresource_range(range())
                .old_layout(vk::ImageLayout::UNDEFINED)
                .new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)],
        );
        self.clear(command, color);
    }
    unsafe fn clear(&self, command: vk::CommandBuffer, color: [f32; 4]) {
        self.device.cmd_clear_color_image(
            command,
            self.handle,
            vk::ImageLayout::GENERAL,
            &vk::ClearColorValue { float32: color },
            &[range()],
        );
    }
}
impl Drop for Image {
    fn drop(&mut self) {
        unsafe {
            if self.view != vk::ImageView::null() {
                self.device.destroy_image_view(self.view, None);
            }
            self.device.destroy_image(self.handle, None);
            self.device.free_memory(self.memory, None);
        }
    }
}
fn range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .level_count(1)
        .layer_count(1)
}
fn triangle(points: [[f32; 3]; 3], emission: f32) -> [f32; 24] {
    let mut result = [0.0; 24];
    for (i, point) in points.iter().enumerate() {
        result[i * 4..i * 4 + 3].copy_from_slice(point);
    }
    result[12..18].fill(0.5);
    result[19] = emission;
    result[20..23].copy_from_slice(&[0.5, 1.0, 0.25]);
    result
}
fn half(value: u16) -> f32 {
    let sign = if value & 0x8000 == 0 { 1.0 } else { -1.0 };
    let exponent = (value >> 10) & 31;
    let mantissa = value & 1023;
    if exponent == 0 {
        sign * mantissa as f32 * 2f32.powi(-24)
    } else if exponent == 31 {
        if mantissa == 0 {
            sign * f32::INFINITY
        } else {
            f32::NAN
        }
    } else {
        sign * (1.0 + mantissa as f32 / 1024.0) * 2f32.powi(exponent as i32 - 15)
    }
}
unsafe fn pixels(buffer: &Buffer, count: usize) -> Vec<[f32; 4]> {
    let mapped = buffer
        .device
        .map_memory(buffer.memory, 0, buffer.size, vk::MemoryMapFlags::empty())
        .unwrap();
    let values = std::slice::from_raw_parts(mapped as *const u16, count * 4);
    let result = values
        .chunks_exact(4)
        .map(|p| [half(p[0]), half(p[1]), half(p[2]), half(p[3])])
        .collect();
    buffer.device.unmap_memory(buffer.memory);
    result
}
unsafe fn readback(
    host: &Host,
    ray: &mut RayState,
    frame: &[f32; 80],
    outputs: &[Image; 2],
    views: [u64; 3],
    width: u32,
    height: u32,
) -> [Vec<[f32; 4]>; 2] {
    let count = width as usize * height as usize;
    let buffers = [
        Buffer::new(
            &host.device,
            &host.memory,
            count as u64 * 8,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
        )
        .unwrap(),
        Buffer::new(
            &host.device,
            &host.memory,
            count as u64 * 8,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
        )
        .unwrap(),
    ];
    host.begin();
    ray.barrier(host.command);
    for image in outputs {
        image.clear(host.command, [-3.0; 4]);
    }
    ray.record_surface(
        host.command,
        frame,
        width,
        height,
        [outputs[0].handle, outputs[1].handle],
        views,
    )
    .unwrap();
    for (i, image) in outputs.iter().enumerate() {
        host.device.cmd_copy_image_to_buffer(
            host.command,
            image.handle,
            vk::ImageLayout::GENERAL,
            buffers[i].handle,
            &[vk::BufferImageCopy::default()
                .image_subresource(
                    vk::ImageSubresourceLayers::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .layer_count(1),
                )
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                })],
        );
    }
    host.device.cmd_pipeline_barrier(
        host.command,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::HOST,
        vk::DependencyFlags::empty(),
        &[vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
            .dst_access_mask(vk::AccessFlags::HOST_READ)],
        &[],
        &[],
    );
    host.submit();
    [pixels(&buffers[0], count), pixels(&buffers[1], count)]
}

unsafe fn upload_capture(host: &Host, image: &Image, width: u32, height: u32, data: &[[f32; 4]]) {
    assert_eq!(data.len(), (width * height) as usize);
    let staging = Buffer::new(
        &host.device,
        &host.memory,
        std::mem::size_of_val(data) as u64,
        vk::BufferUsageFlags::TRANSFER_SRC,
        true,
    )
    .unwrap();
    staging.upload(data).unwrap();
    host.begin();
    host.device.cmd_copy_buffer_to_image(
        host.command,
        staging.handle,
        image.handle,
        vk::ImageLayout::GENERAL,
        &[vk::BufferImageCopy::default()
            .image_subresource(
                vk::ImageSubresourceLayers::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .layer_count(1),
            )
            .image_extent(vk::Extent3D {
                width,
                height,
                depth: 1,
            })],
    );
    host.device.cmd_pipeline_barrier(
        host.command,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::COMPUTE_SHADER,
        vk::DependencyFlags::empty(),
        &[vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ)],
        &[],
        &[],
    );
    host.submit();
}

fn identity_frame(width: u32, height: u32) -> [f32; 80] {
    let mut frame = [0.0; 80];
    for matrix in 0..4 {
        for axis in 0..4 {
            frame[matrix * 16 + axis * 5] = 1.0;
        }
    }
    frame[68] = width as f32;
    frame[69] = height as f32;
    frame[70] = 1.0;
    frame[74] = -1.0;
    frame[75] = 1.0;
    frame[76] = 0.2;
    frame
}

#[test]
#[ignore = "Explicit RX 9070 XT capture/output reconstruction acceptance"]
fn surface_gpu_half_resolution_uses_sloped_capture_texel_centres() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut failures = Vec::new();
        for (source_width, source_height, width, height) in [(8, 6, 4, 3), (9, 7, 5, 4)] {
            let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
            ray.init_surface().unwrap();
            let normals = Image::new(
                &host,
                source_width,
                source_height,
                vk::Format::R32G32B32A32_SFLOAT,
                true,
            );
            let albedo = Image::new(
                &host,
                source_width,
                source_height,
                vk::Format::R16G16B16A16_SFLOAT,
                true,
            );
            let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
            let full_outputs = [
                Image::new(
                    &host,
                    source_width,
                    source_height,
                    vk::Format::R16G16B16A16_SFLOAT,
                    false,
                ),
                Image::new(
                    &host,
                    source_width,
                    source_height,
                    vk::Format::R16G16B16A16_SFLOAT,
                    false,
                ),
            ];
            let outputs = [
                Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
                Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
            ];
            let normal_length = (1.0f32 + 0.08 * 0.08 + 0.06 * 0.06).sqrt();
            let normal = [
                0.08 / normal_length,
                0.06 / normal_length,
                -1.0 / normal_length,
            ];
            let capture: Vec<_> = (0..source_height)
                .flat_map(|y| {
                    (0..source_width).map(move |x| {
                        let px = (x as f32 + 0.5) * 2.0 / source_width as f32 - 1.0;
                        let py = (y as f32 + 0.5) * 2.0 / source_height as f32 - 1.0;
                        [
                            normal[0],
                            normal[1],
                            normal[2],
                            0.35 + 0.08 * px + 0.06 * py,
                        ]
                    })
                })
                .collect();
            host.begin();
            normals.initialize(host.command, [0.0; 4]);
            albedo.initialize(host.command, [0.1, 0.2, 0.3, 1.49]);
            atlas.initialize(host.command, [1.0; 4]);
            for image in full_outputs.iter().chain(outputs.iter()) {
                image.initialize(host.command, [-3.0; 4]);
            }
            ray.barrier(host.command);
            host.submit();
            upload_capture(&host, &normals, source_width, source_height, &capture);
            // Hand-selected nearest samples at output (1,1): (3,3) and (2,2).
            let (sx, sy) = if source_width == 8 { (3, 3) } else { (2, 2) };
            let x = (sx as f32 + 0.5) * 2.0 / source_width as f32 - 1.0 + normal[0] * 0.035;
            let y = (sy as f32 + 0.5) * 2.0 / source_height as f32 - 1.0 + normal[1] * 0.035;
            let radius = 0.012;
            let mut scene = triangle(
                [
                    [x - radius, y - radius, 0.0],
                    [x + radius, y - radius, 0.0],
                    [x + radius, y + radius, 0.0],
                ],
                0.0,
            )
            .to_vec();
            scene.extend_from_slice(&triangle(
                [
                    [x - radius, y - radius, 0.0],
                    [x + radius, y + radius, 0.0],
                    [x - radius, y + radius, 0.0],
                ],
                0.0,
            ));
            ray.scene(&scene).unwrap();
            let views = [
                atlas.view.as_raw(),
                normals.view.as_raw(),
                albedo.view.as_raw(),
            ];
            let full = readback(
                &host,
                &mut ray,
                &identity_frame(source_width, source_height),
                &full_outputs,
                views,
                source_width,
                source_height,
            );
            let reduced = readback(
                &host,
                &mut ray,
                &identity_frame(width, height),
                &outputs,
                views,
                width,
                height,
            );
            assert_eq!(
                full[0][(sy * source_width + sx) as usize][3],
                0.0,
                "narrow occluder must cover actual captured surfel"
            );
            assert_eq!(
                full[0].iter().filter(|p| p[3] == 0.0).count(),
                1,
                "fixture must have exactly one shadowed captured texel"
            );
            let mut mismatches = 0;
            for py in 0..height {
                for px in 0..width {
                    let sampled_x = ((px * 2 + 1) * source_width) / (width * 2);
                    let sampled_y = ((py * 2 + 1) * source_height) / (height * 2);
                    let source_index = (sampled_y * source_width + sampled_x) as usize;
                    let output_index = (py * width + px) as usize;
                    for attachment in 0..2 {
                        if reduced[attachment][output_index] != full[attachment][source_index] {
                            mismatches += 1;
                        }
                    }
                }
            }
            println!("surface_gpu capture={}x{} output={}x{} sloped_depth_range={:?} target_visibility={} expected=0 mismatches={}",source_width,source_height,width,height,[capture[0][3],capture.last().unwrap()[3]],reduced[0][(width+1) as usize][3],mismatches);
            if mismatches != 0 {
                failures.push((source_width, source_height, width, height, mismatches));
            }
        }
        assert!(
            failures.is_empty(),
            "reduced rays must use actual sampled capture texel centres: {failures:?}"
        );
    }
}

#[test]
#[ignore = "Explicit RX 9070 XT layered cutout sun acceptance"]
fn surface_gpu_sun_ignores_cutout_layers_and_stops_at_accepted_blocker() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let (width, height) = (7, 5);
        let normals = Image::new(&host, width, height, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = [
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
        ];
        host.begin();
        normals.initialize(host.command, [0.0, 0.0, -1.0, 0.5]);
        albedo.initialize(host.command, [0.1, 0.2, 0.3, 1.49]);
        atlas.initialize(host.command, [1.0, 1.0, 1.0, 0.0]);
        for image in &outputs {
            image.initialize(host.command, [-3.0; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        let views = [
            atlas.view.as_raw(),
            normals.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let frame = identity_frame(width, height);
        let mut transparent = Vec::new();
        for z in [0.4, 0.3, 0.2, 0.1] {
            let mut layer = triangle([[-4.0, -4.0, z], [4.0, -4.0, z], [0.0, 4.0, z]], 0.0);
            layer[18] = 1.0;
            transparent.extend_from_slice(&layer);
        }
        ray.scene(&transparent).unwrap();
        let clear = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert!(
            clear[0]
                .iter()
                .all(|p| p[3] == 1.0 && (p[0] - 1.2).abs() < 0.002),
            "rejected alpha candidates must not terminate sun queries"
        );
        let opaque = triangle([[-4.0, -4.0, 0.0], [4.0, -4.0, 0.0], [0.0, 4.0, 0.0]], 0.0);
        for opaque_first in [false, true] {
            let scene = if opaque_first {
                [opaque.as_slice(), transparent.as_slice()].concat()
            } else {
                [transparent.as_slice(), opaque.as_slice()].concat()
            };
            ray.scene(&scene).unwrap();
            let blocked = readback(&host, &mut ray, &frame, &outputs, views, width, height);
            assert!(blocked[0].iter().all(|p| p[3] == 0.0 && (p[0]-0.2).abs()<0.002),"accepted opaque candidate must block sun through transparent layers regardless of primitive order");
        }
        // An alpha-tested layer becomes a real blocker when the atlas alpha is opaque.
        host.begin();
        atlas.clear(host.command, [1.0; 4]);
        ray.barrier(host.command);
        host.submit();
        ray.scene(&transparent).unwrap();
        let cutout_blocked = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert!(cutout_blocked[0]
            .iter()
            .all(|p| p[3] == 0.0 && (p[0] - 0.2).abs() < 0.002));
        println!("surface_gpu layered_alpha layers=4 transparent_visibility=1 opaque_and_cutout_visibility=0 primitive_orders=2 pixels={}",width*height);
    }
}

#[test]
#[ignore = "Explicit RX 9070 XT headless Vulkan acceptance"]
fn surface_gpu_offscreen_reflection_shadow_odd_dimensions_and_scene_reset() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let (width, height) = (7, 5);
        let capture_normal =
            Image::new(&host, width, height, vk::Format::R32G32B32A32_SFLOAT, true);
        let capture_albedo =
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = [
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
        ];
        host.begin();
        let s = std::f32::consts::FRAC_1_SQRT_2;
        capture_normal.initialize(host.command, [-s, 0.0, -s, 0.5]);
        capture_albedo.initialize(host.command, [0.1, 0.2, 0.3, 4.05]);
        atlas.initialize(host.command, [0.8, 0.2, 0.1, 1.0]);
        for image in &outputs {
            image.initialize(host.command, [-3.0; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        let mut frame = [0f32; 80];
        for matrix in 0..4 {
            for axis in 0..4 {
                frame[matrix * 16 + axis * 5] = 1.0;
            }
        }
        frame[68] = width as f32;
        frame[69] = height as f32;
        frame[70] = 1.0;
        frame[72] = -s;
        frame[73] = s;
        frame[75] = 1.0;
        frame[76] = 0.2;
        let views = [
            atlas.view.as_raw(),
            capture_normal.view.as_raw(),
            capture_albedo.view.as_raw(),
        ];
        let target = triangle(
            [[-3.0, -1.0, -0.5], [-3.0, 1.0, -0.5], [-3.0, 0.0, 1.5]],
            4.0,
        );
        // Identity projection has clip x in [-1,1]; every target vertex x=-3 is offscreen.
        assert!(target.chunks_exact(4).take(3).all(|v| v[0] < -1.0));
        ray.scene(&target).unwrap();
        let clear = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        let center = (height / 2 * width + width / 2) as usize;
        assert_eq!(
            clear[1][center][3], 1.0,
            "offscreen geometry must be a reflected hit"
        );
        assert_eq!(
            clear[0][center][3], 1.0,
            "sun ray misses small reflected target"
        );
        assert!(
            (clear[0][center][0] - 0.7).abs() < 0.002,
            "clear direct+ambient irradiance"
        );
        let expected = [
            ((204f32 / 255.0) * 0.5).powf(2.2) * 2.0,
            (51f32 / 255.0).powf(2.2) * 2.0,
            ((26f32 / 255.0) * 0.25).powf(2.2) * 2.0,
        ];
        for (channel, value) in expected.iter().enumerate() {
            assert!(
                (clear[1][center][channel] - value).abs() < 0.002,
                "linear atlas, tint and emission channel {channel}"
            );
        }
        for image in &clear {
            assert_eq!(image.len(), 35);
            assert!(image.iter().flatten().all(|v| v.is_finite() && *v != -3.0));
        }
        println!("surface_gpu clear center_lighting={:?} center_reflection={:?} reflected_hits={} written_pixels=35 dimensions=7x5",
        clear[0][center],clear[1][center],clear[1].iter().filter(|p|p[3]==1.0).count());
        host.begin();
        capture_albedo.clear(host.command, [0.1, 0.2, 0.3, 1.05]);
        ray.barrier(host.command);
        host.submit();
        let generic_material = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            generic_material, clear,
            "generic material class 1 with low roughness must keep model reflections"
        );
        let mut layered_reflectors = target.to_vec();
        layered_reflectors.extend_from_slice(&triangle(
            [[-5.0, -2.0, -2.0], [-5.0, 2.0, -2.0], [-5.0, 0.0, 3.0]],
            15.0,
        ));
        ray.scene(&layered_reflectors).unwrap();
        let nearest_reflection = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            nearest_reflection[1][center], clear[1][center],
            "radiance rays must select nearest reflector despite a brighter farther hit"
        );
        ray.scene(&target).unwrap();
        host.begin();
        capture_albedo.clear(host.command, [0.1, 0.2, 0.3, 4.05]);
        capture_normal.clear(host.command, [0.0, 0.0, -1.0, 0.5]);
        ray.barrier(host.command);
        host.submit();
        let changed_normal = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            changed_normal[1][center], [0.0; 4],
            "captured normal must change reflection direction"
        );
        println!(
            "surface_gpu changed_normal center_reflection={:?}",
            changed_normal[1][center]
        );
        host.begin();
        capture_normal.clear(host.command, [-s, 0.0, -s, 1.0]);
        ray.barrier(host.command);
        host.submit();
        let no_surface = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert!(
            no_surface.iter().flatten().all(|p| *p == [0.0; 4]),
            "device far depth must invalidate all captured surfels"
        );
        println!("surface_gpu far_depth zero_pixels_per_output=35");
        host.begin();
        capture_normal.clear(host.command, [-s, 0.0, -s, 0.5]);
        ray.barrier(host.command);
        host.submit();
        let blocker = triangle([[-2.0, 1.0, -0.5], [0.0, 1.0, -0.5], [-1.0, 1.0, 1.5]], 0.0);
        let mut blocked_scene = target.to_vec();
        blocked_scene.extend_from_slice(&blocker);
        ray.scene(&blocked_scene).unwrap();
        let blocked = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            blocked[0][center][3], 0.0,
            "sun occluder must lower visibility"
        );
        assert!(
            (blocked[0][center][0] - 0.2).abs() < 0.002,
            "blocked surface retains ambient only"
        );
        assert_eq!(
            blocked[1][center][3], 1.0,
            "sun occluder leaves horizontal reflection intact"
        );
        println!(
            "surface_gpu blocked center_lighting={:?} center_reflection={:?}",
            blocked[0][center], blocked[1][center]
        );
        let miss = triangle([[3.0, -1.0, -0.5], [3.0, 1.0, -0.5], [3.0, 0.0, 1.5]], 4.0);
        ray.scene(&miss).unwrap();
        let missed = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            missed[1][center], [0.0; 4],
            "reflection miss must return zero radiance and alpha"
        );
        println!("surface_gpu miss center_reflection={:?}", missed[1][center]);
        ray.reset_scene().unwrap();
        host.begin();
        assert!(
            ray.record_surface(
                host.command,
                &frame,
                width,
                height,
                [outputs[0].handle, outputs[1].handle],
                views
            )
            .is_err(),
            "reset shared scene must reject surface dispatch"
        );
        host.submit();
        ray.scene(&target).unwrap();
        let restored = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            restored[1][center][3], 1.0,
            "scene recreation must rebind child descriptors"
        );
        println!(
            "surface_gpu reset_recreated center_reflection={:?}",
            restored[1][center]
        );
        let mut gi_frame = frame;
        gi_frame[75] = 0.0;
        gi_frame[77] = 1.0;
        let bounce_points = [[-1.5, -20.0, -20.0], [-1.5, 20.0, -20.0], [-1.5, 0.0, 20.0]];
        ray.scene(&triangle(bounce_points, 0.0)).unwrap();
        let gi_dark = readback(&host, &mut ray, &gi_frame, &outputs, views, width, height);
        ray.scene(&triangle(bounce_points, 15.0)).unwrap();
        let gi_emitter = readback(&host, &mut ray, &gi_frame, &outputs, views, width, height);
        assert!(
            gi_emitter[0][center][0] > gi_dark[0][center][0] + 0.05,
            "offscreen emission must reach captured surfel through traced diffuse bounce"
        );
        println!(
            "surface_gpu gi_dark center_lighting={:?} gi_emitter center_lighting={:?}",
            gi_dark[0][center], gi_emitter[0][center]
        );
        gi_frame[77] = 0.0;
        let gi_off = readback(&host, &mut ray, &gi_frame, &outputs, views, width, height);
        assert!(
            (gi_off[0][center][0] - 0.2).abs() < 0.002,
            "GI Off must remove indirect emission"
        );
        println!("surface_gpu gi_off center_lighting={:?}", gi_off[0][center]);
    }
}

struct QueuedFlight {
    device: Device,
    fence: vk::Fence,
    buffers: [Buffer; 2],
    _outputs: [Image; 2],
}
impl QueuedFlight {
    unsafe fn values(&self, count: usize) -> [Vec<[f32; 4]>; 2] {
        self.device
            .wait_for_fences(&[self.fence], true, 10_000_000_000)
            .unwrap();
        [
            pixels(&self.buffers[0], count),
            pixels(&self.buffers[1], count),
        ]
    }
}
impl Drop for QueuedFlight {
    fn drop(&mut self) {
        unsafe {
            let _ = self.device.wait_for_fences(&[self.fence], true, u64::MAX);
            self.device.destroy_fence(self.fence, None);
        }
    }
}
unsafe fn queued_flight(
    host: &Host,
    ray: &mut RayState,
    serial: u64,
    frame: &[f32; 80],
    views: [u64; 3],
) -> QueuedFlight {
    let width = frame[68] as u32;
    let height = frame[69] as u32;
    let bytes = width as u64 * height as u64 * 8;
    assert_eq!(ray.begin_submission(serial).unwrap(), 0);
    let command = host
        .device
        .allocate_command_buffers(
            &vk::CommandBufferAllocateInfo::default()
                .command_pool(host.pool)
                .level(vk::CommandBufferLevel::PRIMARY)
                .command_buffer_count(1),
        )
        .unwrap()[0];
    let outputs = [
        Image::new(host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
        Image::new(host, width, height, vk::Format::R16G16B16A16_SFLOAT, false),
    ];
    let buffers = [
        Buffer::new(
            &host.device,
            &host.memory,
            bytes,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
        )
        .unwrap(),
        Buffer::new(
            &host.device,
            &host.memory,
            bytes,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
        )
        .unwrap(),
    ];
    host.device
        .begin_command_buffer(command, &vk::CommandBufferBeginInfo::default())
        .unwrap();
    for image in &outputs {
        image.initialize(command, [-3.0; 4]);
    }
    ray.record_surface(
        command,
        frame,
        width,
        height,
        [outputs[0].handle, outputs[1].handle],
        views,
    )
    .unwrap();
    for i in 0..2 {
        host.device.cmd_copy_image_to_buffer(
            command,
            outputs[i].handle,
            vk::ImageLayout::GENERAL,
            buffers[i].handle,
            &[vk::BufferImageCopy::default()
                .image_subresource(
                    vk::ImageSubresourceLayers::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .layer_count(1),
                )
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                })],
        );
    }
    host.device.cmd_pipeline_barrier(
        command,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::HOST,
        vk::DependencyFlags::empty(),
        &[vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
            .dst_access_mask(vk::AccessFlags::HOST_READ)],
        &[],
        &[],
    );
    host.device.end_command_buffer(command).unwrap();
    let fence = host
        .device
        .create_fence(&vk::FenceCreateInfo::default(), None)
        .unwrap();
    host.device
        .queue_submit(
            host.queue,
            &[vk::SubmitInfo::default().command_buffers(&[command])],
            fence,
        )
        .unwrap();
    assert_eq!(ray.submission_submitted(serial).unwrap(), 0);
    QueuedFlight {
        device: host.device.clone(),
        fence,
        buffers,
        _outputs: outputs,
    }
}

#[test]
#[ignore = "Explicit RX 9070 XT asynchronous generation acceptance"]
fn async_gpu_retains_generations_and_reclaims_only_completed_fifo_flights() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let (width, height) = (7, 5);
        let s = std::f32::consts::FRAC_1_SQRT_2;
        let normals = Image::new(&host, width, height, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        host.begin();
        normals.initialize(host.command, [-s, 0.0, -s, 0.5]);
        albedo.initialize(host.command, [0.1, 0.2, 0.3, 4.05]);
        atlas.initialize(host.command, [0.8, 0.2, 0.1, 1.0]);
        ray.barrier(host.command);
        host.submit();
        let mut frame = [0f32; 80];
        for m in 0..4 {
            for axis in 0..4 {
                frame[m * 16 + axis * 5] = 1.0;
            }
        }
        frame[68] = width as f32;
        frame[69] = height as f32;
        frame[70] = 1.0;
        frame[72] = -s;
        frame[73] = s;
        frame[75] = 1.0;
        frame[76] = 0.2;
        let views = [
            atlas.view.as_raw(),
            normals.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let points = [[-3.0, -1.0, -0.5], [-3.0, 1.0, -0.5], [-3.0, 0.0, 1.5]];
        let factory = ray.scene_factory();
        let prepared = std::thread::spawn(move || factory.prepare(&triangle(points, 1.0)).unwrap())
            .join()
            .unwrap();
        let old = Arc::downgrade(&prepared);
        assert_eq!(ray.publish_scene(prepared).unwrap(), 0);
        let idle_before = idle_stats()[0];
        let a = queued_flight(&host, &mut ray, 1, &frame, views);
        let factory = ray.scene_factory();
        let prepared = std::thread::spawn(move || factory.prepare(&triangle(points, 9.0)).unwrap())
            .join()
            .unwrap();
        assert_eq!(ray.publish_scene(prepared).unwrap(), 0);
        assert!(
            old.upgrade().is_some(),
            "old scene must survive publication while its flight is unreclaimed"
        );
        let b = queued_flight(&host, &mut ray, 2, &frame, views);
        let pool = ray.flights.as_ref().unwrap();
        assert!(
            pool.slots[0].state.set != pool.slots[1].state.set,
            "pending world descriptor sets cannot alias"
        );
        assert!(
            pool.slots[0].state.surface.as_ref().unwrap().set
                != pool.slots[1].state.surface.as_ref().unwrap().set,
            "pending surface descriptor sets cannot alias"
        );
        assert!(
            Arc::ptr_eq(
                pool.slots[0].state.owner.as_ref().unwrap(),
                pool.slots[1].state.owner.as_ref().unwrap()
            ),
            "flight slots share immutable pipelines"
        );
        let center = (height / 2 * width + width / 2) as usize;
        let av = a.values(35);
        let bv = b.values(35);
        assert_eq!(av[1][center][3], 1.0);
        assert_eq!(bv[1][center][3], 1.0);
        assert!(
            av[1][center][0] < 0.1 && bv[1][center][0] > 0.5,
            "flights must read their own immutable scene emission"
        );
        assert!(
            ray.submission_completed(2).is_err(),
            "out-of-order reclamation must be rejected"
        );
        assert!(old.upgrade().is_some());
        assert_eq!(ray.submission_completed(1).unwrap(), 0);
        assert!(
            old.upgrade().is_none(),
            "last completed flight must release retired scene generation"
        );
        assert_eq!(ray.submission_completed(2).unwrap(), 0);
        let mut pending = Vec::new();
        for serial in 3..=6 {
            pending.push(queued_flight(&host, &mut ray, serial, &frame, views));
        }
        assert_eq!(
            ray.begin_submission(7).unwrap(),
            1,
            "bounded pool must report Busy without waiting or reusing pending descriptors"
        );
        assert_eq!(ray.async_stats()[3], 4);
        pending[0].values(35);
        assert_eq!(ray.submission_completed(3).unwrap(), 0);
        assert_eq!(ray.begin_submission(7).unwrap(), 0);
        assert_eq!(ray.submission_cancel(7).unwrap(), 0);
        for (i, flight) in pending.iter().enumerate().skip(1) {
            flight.values(35);
            assert_eq!(ray.submission_completed(i as u64 + 3).unwrap(), 0);
        }
        assert_eq!(ray.reset_scene_async().unwrap(), 0);
        assert!(ray.scene.is_none());
        assert_eq!(ray.async_stats()[3], 0);
        assert_eq!(ray.async_stats()[4], 0);
        assert_eq!(
            idle_stats()[0],
            idle_before,
            "tracked preparation/publication/record/completion must never wait for device idle"
        );
        println!("async_gpu flights=7 bounded_slots={} Busy=1 retired_after_completion=0 idle_delta=0 old_reflection={:?} new_reflection={:?}",
        ray.async_stats()[2],av[1][center],bv[1][center]);
    }
}
