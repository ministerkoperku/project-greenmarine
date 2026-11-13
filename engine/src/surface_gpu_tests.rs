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
        Self::new_with_ray(true)
    }
    unsafe fn new_with_ray(require_ray: bool) -> Self {
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
                if !require_ray {
                    return true;
                }
                let props = instance.get_physical_device_properties(p);
                // The owner's acceptance GPU by default; e.g. "llvmpipe" runs lavapipe locally.
                let wanted = std::env::var("GREENMARINE_GPU_TEST_DEVICE")
                    .unwrap_or_else(|_| "RX 9070 XT".into());
                CStr::from_ptr(props.device_name.as_ptr())
                    .to_string_lossy()
                    .contains(wanted.as_str())
            })
            .expect("Acceptance requires the selected GPU (RX 9070 XT by default)");
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
            .acceleration_structure(require_ray);
        let mut ray = vk::PhysicalDeviceRayQueryFeaturesKHR::default().ray_query(require_ray);
        let mut v12 =
            vk::PhysicalDeviceVulkan12Features::default().buffer_device_address(require_ray);
        let device = instance
            .create_device(
                physical,
                &vk::DeviceCreateInfo::default()
                    .queue_create_infos(&queue_info)
                    .enabled_extension_names(if require_ray { &extensions } else { &[] })
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
    levels: u32,
}
impl Image {
    unsafe fn new(host: &Host, width: u32, height: u32, format: vk::Format, sampled: bool) -> Self {
        Self::with_mips(host, width, height, format, sampled, 1)
    }
    unsafe fn with_mips(
        host: &Host,
        width: u32,
        height: u32,
        format: vk::Format,
        sampled: bool,
        levels: u32,
    ) -> Self {
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
                    .mip_levels(levels)
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
                        .subresource_range(range().level_count(levels)),
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
            levels,
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
                .subresource_range(range().level_count(self.levels))
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
            &[range().level_count(self.levels)],
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
unsafe fn readback<const N: usize>(
    host: &Host,
    ray: &mut RayState,
    frame: &[f32; 80],
    outputs: &[Image; N],
    views: [u64; 3],
    width: u32,
    height: u32,
) -> [Vec<[f32; 4]>; N] {
    readback_actors(host, ray, frame, outputs, views, width, height, None)
}
#[allow(clippy::too_many_arguments)]
unsafe fn readback_actors<const N: usize>(
    host: &Host,
    ray: &mut RayState,
    frame: &[f32; 80],
    outputs: &[Image; N],
    views: [u64; 3],
    width: u32,
    height: u32,
    actors: Option<(&[f32], u64)>,
) -> [Vec<[f32; 4]>; N] {
    let count = width as usize * height as usize;
    let buffers: [Buffer; N] = std::array::from_fn(|_| {
        Buffer::new(
            &host.device,
            &host.memory,
            count as u64 * 8,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
        )
        .unwrap()
    });
    host.begin();
    ray.barrier(host.command);
    for image in outputs {
        image.clear(host.command, [-3.0; 4]);
    }
    let images = [
        outputs[0].handle,
        outputs[1].handle,
        if N > 2 {
            outputs[2].handle
        } else {
            vk::Image::null()
        },
    ];
    match actors {
        Some((payload, skin)) => ray.record_surface_actors(
            host.command,
            frame,
            width,
            height,
            images,
            views,
            payload,
            skin,
        ),
        None => ray.record_surface(host.command, frame, width, height, images, views),
    }
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
    std::array::from_fn(|i| pixels(&buffers[i], count))
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
#[ignore = "Explicit Vulkan local emitter GI duplication regression"]
fn surface_gpu_registered_emitters_do_not_stamp_their_shape_through_gi() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let normal = Image::new(&host, 1, 1, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = std::array::from_fn::<_, 2, _>(|_| {
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false)
        });
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 1.4]);
        atlas.initialize(host.command, [1.; 4]);
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        // One fixed hemisphere ray intersects a small emitter six blocks away,
        // projecting its geometric silhouette even when its texture is averaged.
        ray.scene(&triangle(
            [[1., -2., -6.], [2., -2., -6.], [1.5, -1., -6.]],
            14.,
        ))
        .unwrap();
        let views = [
            atlas.view.as_raw(),
            normal.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let mut frame = identity_frame(1, 1);
        frame[75] = 0.;
        frame[77] = 1.;
        let unmatched = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(
            unmatched[0][0][1] > 0.5,
            "emitters absent from the local-light table still need the legacy fallback"
        );
        ray.set_surface_lights(&[1.5, -1.5, -6.5, 14., 1., 1., 1., 0.], 1)
            .unwrap();
        frame[77] = 0.;
        let direct = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(
            direct[0][0][1] > 0.22,
            "local emitter must still illuminate the receiver"
        );
        frame[77] = 1.;
        let gi = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        for channel in 0..3 {
            assert!((gi[0][0][channel]-direct[0][0][channel]).abs()<0.002,
            "a registered direct emitter must not project a second GI image: direct={:?}, GI={:?}",direct[0][0],gi[0][0]);
        }
        ray.set_surface_lights(&[1.5, -1.5, -7.5, 14., 1., 1., 1., 0.], 1)
            .unwrap();
        let unrelated = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(
            unrelated[0][0][1] > gi[0][0][1] + 0.5,
            "an unrelated local light must not suppress an unrepresented emitter"
        );
        println!(
            "unmatched={:?}; direct={:?}; registered GI={:?}",
            unmatched[0][0], direct[0][0], gi[0][0]
        );
    }
}

#[test]
#[ignore = "Explicit Vulkan zero-mip diffuse texture stability"]
fn surface_gpu_zero_mip_bounces_average_colour_without_projecting_source_uv() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let normal = Image::new(&host, 1, 1, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 4, 4, vk::Format::R32G32B32A32_SFLOAT, true);
        let outputs = std::array::from_fn::<_, 2, _>(|_| {
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false)
        });
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 4.05]);
        atlas.initialize(host.command, [0.; 4]);
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        let colours: Vec<[f32; 4]> = (0..16)
            .map(|i| {
                if i % 4 < 2 {
                    [1., 0., 0., 1.]
                } else {
                    [0., 1., 0., 1.]
                }
            })
            .collect();
        upload_capture(&host, &atlas, 4, 4, &colours);
        let mut source = triangle(
            [[-100., -100., -2.], [100., -100., -2.], [0., 100., -2.]],
            1.,
        );
        source[12..18].copy_from_slice(&[0., 0., 1., 0., 0.5, 1.]);
        source[20..23].fill(1.);
        ray.scene(&source).unwrap();
        let views = [
            atlas.view.as_raw(),
            normal.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let mut frame = identity_frame(1, 1);
        frame[75] = 0.;
        frame[77] = 1.;
        let mut phases = Vec::new();
        for offset in [-20., 20.] {
            frame[64] = offset;
            let values = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
            phases.push((values[0][0], values[1][0]));
            assert!(
                (values[0][0][0] - values[0][0][1]).abs() < 0.002 && values[0][0][0] > 0.25,
                "zero-mip GI must retain averaged colour instead of source UV detail: {phases:?}"
            );
        }
        assert!(
            phases[0].1[0] > phases[0].1[1] + 0.1 && phases[1].1[1] > phases[1].1[0] + 0.1,
            "reflection must retain detailed source UVs: {phases:?}"
        );
        println!("zero-mip GI/reflection phases={phases:?}");
    }
}

#[test]
#[ignore = "Explicit Vulkan subpixel cutout shadow coverage"]
fn surface_gpu_cutout_shadow_filters_subpixel_alpha_without_softening_opaque_blocks() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let (width, height) = (4, 4);
        let normal = Image::new(&host, width, height, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::with_mips(&host, 16, 16, vk::Format::R32G32B32A32_SFLOAT, true, 5);
        let outputs = std::array::from_fn::<_, 2, _>(|_| {
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false)
        });
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 1.4]);
        atlas.initialize(host.command, [1., 1., 1., 0.5]);
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        // Real fine alpha alternates 0/1; averaged mips represent half coverage.
        let checker: Vec<[f32; 4]> = (0..256)
            .map(|i| [1., 1., 1., ((i % 16 + i / 16) % 2) as f32])
            .collect();
        upload_capture(&host, &atlas, 16, 16, &checker);
        let mut caster = triangle([[-2., -2., 0.], [2., -2., 0.], [0., 2., 0.]], 0.);
        caster[12..18].copy_from_slice(&[0., 0., 1., 0., 0.5, 1.]);
        caster[18] = 1.25;
        ray.scene(&caster).unwrap();
        let views = [
            atlas.view.as_raw(),
            normal.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let mut frame = identity_frame(width, height);
        let mut phases = Vec::new();
        for offset in [-0.04, 0., 0.04] {
            frame[64] = offset;
            let values = readback(&host, &mut ray, &frame, &outputs, views, width, height);
            let visibility = values[0][5][3];
            phases.push(visibility);
            assert!((visibility-0.5).abs()<0.02,"subpixel checker must retain half shadow coverage during camera motion: {phases:?}");
        }
        // Transmission remains multiplicative, including partial glass-like tint.
        caster[23] = 0.5;
        ray.scene(&caster).unwrap();
        let transmitted = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert!((transmitted[0][5][3] - 0.75).abs() < 0.02);
        // Legacy translucent alpha is a threshold, not geometric coverage.
        caster[18] = 1.;
        ray.scene(&caster).unwrap();
        host.begin();
        atlas.clear(host.command, [1., 1., 1., 0.2]);
        ray.barrier(host.command);
        host.submit();
        let glass = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert!(
            (glass[0][5][3] - 0.5).abs() < 0.002,
            "legacy glass transmission must not be multiplied by its optical texture alpha"
        );
        // Fully opaque blocks bypass cutout filtering and stay completely shadowed.
        caster[18] = 0.;
        caster[23] = 0.;
        ray.scene(&caster).unwrap();
        let opaque = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(opaque[0][5][3], 0.);
        println!(
            "cutout coverage phases={phases:?}; opaque={:?}",
            opaque[0][5]
        );
    }
}

#[test]
#[ignore = "Explicit Vulkan AO continuity across native workgroups"]
fn surface_gpu_planar_occlusion_has_no_workgroup_seams() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let (width, height) = (32, 8);
        let normal = Image::new(&host, width, height, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = std::array::from_fn::<_, 3, _>(|_| {
            Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false)
        });
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 1.4]);
        atlas.initialize(host.command, [1.; 4]);
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        // A tilted infinite plane gives an affine hit distance in x for each
        // fixed hemisphere direction. AO must inherit the same constant slope.
        ray.scene(&triangle(
            [[-100., -100., 24.], [100., -100., -26.], [0., 100., -1.]],
            0.,
        ))
        .unwrap();
        let mut frame = identity_frame(width, height);
        frame[75] = 0.;
        frame[77] = 1.;
        let values = readback(
            &host,
            &mut ray,
            &frame,
            &outputs,
            [
                atlas.view.as_raw(),
                normal.view.as_raw(),
                albedo.view.as_raw(),
            ],
            width,
            height,
        );
        let row: Vec<f32> = values[2]
            .iter()
            .take(width as usize)
            .map(|p| p[0])
            .collect();
        println!("planar AO={row:?}");
        let slope = (row[29] - row[2]) / 27.;
        assert!(slope.abs() > 0.001, "fixture must have a nonzero AO slope");
        for x in 2..29 {
            assert!(
                ((row[x + 1] - row[x]) - slope).abs() < 0.0015,
                "smooth geometry must not jump at native workgroup boundary {x}: {row:?}"
            );
        }
    }
}

#[test]
#[ignore = "Explicit Vulkan averaged GI atlas mip regression"]
fn surface_gpu_diffuse_uses_coarse_atlas_mip_but_reflections_keep_detail() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let normal = Image::new(&host, 1, 1, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::with_mips(&host, 16, 16, vk::Format::R8G8B8A8_UNORM, true, 5);
        let outputs = [
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
        ];
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 4.05]);
        atlas.initialize(host.command, [0., 1., 0., 1.]);
        host.device.cmd_clear_color_image(
            host.command,
            atlas.handle,
            vk::ImageLayout::GENERAL,
            &vk::ClearColorValue {
                float32: [1., 0., 0., 1.],
            },
            &[range().base_mip_level(4)],
        );
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        ray.scene(&triangle(
            [[-100., -100., -2.], [100., -100., -2.], [0., 100., -2.]],
            1.,
        ))
        .unwrap();
        let mut frame = identity_frame(1, 1);
        frame[75] = 0.;
        frame[77] = 1.;
        let views = [
            atlas.view.as_raw(),
            normal.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let values = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(
            values[0][0][0] > values[0][0][1] + 0.05,
            "diffuse GI must use averaged red mip4 rather than project green mip0 detail: {:?}",
            values[0][0]
        );
        assert!(
            values[1][0][1] > values[1][0][0] + 0.1 && values[1][0][3] == 1.,
            "reflection must still read detailed green mip0: {:?}",
            values[1][0]
        );
        println!(
            "coarse GI={:?}, detailed reflection={:?}",
            values[0][0], values[1][0]
        );
    }
}

#[test]
#[ignore = "Explicit Vulkan cave GI and actor block-light regression"]
fn surface_gpu_cave_misses_do_not_add_sky_and_actors_shadow_local_lights() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let normal = Image::new(&host, 1, 1, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let skin = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = [
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
        ];
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 1.4]);
        atlas.initialize(host.command, [1.; 4]);
        skin.initialize(host.command, [1.; 4]);
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        // Keep the AS valid while every 12-block secondary ray misses.
        ray.scene(&triangle(
            [[100., -4., -4.], [100., 4., -4.], [100., 0., 4.]],
            0.,
        ))
        .unwrap();
        let views = [
            atlas.view.as_raw(),
            normal.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let mut frame = identity_frame(1, 1);
        frame[75] = 0.; // no direct sun
        frame[67] = 1.; // enclosed camera
        let baseline = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        frame[77] = 1.; // GI enabled
        let gi = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert_eq!(
            gi[0], baseline[0],
            "a miss is unknown geometry, not a source of sky radiance in a cave"
        );

        frame[77] = 0.;
        ray.set_surface_lights(&[0., 0., -3., 15., 1., 1., 1., 0.], 1)
            .unwrap();
        let lit = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(lit[0][0][0] > baseline[0][0][0] + 0.3);
        let mut inverse = [0.; 16];
        for axis in 0..4 {
            inverse[axis * 5] = 1.;
        }
        inverse[12] = 0.5;
        inverse[13] = 0.5;
        inverse[14] = 1.; // centred actor between receiver and lamp
        let actors = actor_box_payload(inverse, false);
        let blocked = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&actors, skin.view.as_raw())),
        );
        assert!(
            (blocked[0][0][0] - baseline[0][0][0]).abs() < 0.002,
            "opaque actor must block local light: lit={:?}, blocked={:?}",
            lit[0][0],
            blocked[0][0]
        );
        inverse[12] = 1.; // receiver-to-light segment grazes the actor's side
        let edge_actor = actor_box_payload(inverse, false);
        let edge = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&edge_actor, skin.view.as_raw())),
        );
        assert!(
            edge[0][0][0] > blocked[0][0][0] + 0.03 && edge[0][0][0] < lit[0][0][0] - 0.03,
            "finite-area local light must give a penumbra at the actor silhouette: {:?}",
            edge[0][0]
        );
        let second_light = [2., 0., -3., 15., 0.8, 0.8, 0.8, 0.];
        ray.set_surface_lights(&second_light, 1).unwrap();
        let second = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&edge_actor, skin.view.as_raw())),
        );
        let both_lights = [[0., 0., -3., 15., 1., 1., 1., 0.], second_light].concat();
        ray.set_surface_lights(&both_lights, 2).unwrap();
        let both = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&edge_actor, skin.view.as_raw())),
        );
        assert!(both[0][0][0] >= second[0][0][0] - 0.002, "a partially visible first light must not suppress a brighter visible second: both={:?}, second={:?}",both[0][0],second[0][0]);
        ray.set_surface_lights(&both_lights[..8], 1).unwrap();
        inverse[12] = 0.5;
        inverse[10] = 10.;
        inverse[14] = 28.;
        let near_emitter_actor = actor_box_payload(inverse, false);
        let near_emitter = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&near_emitter_actor, skin.view.as_raw())),
        );
        assert!((near_emitter[0][0][0] - baseline[0][0][0]).abs() < 0.002, "a thin actor inside the final 0.6 blocks before an emitter must still cast a shadow: {:?}",near_emitter[0][0]);
        host.begin();
        skin.clear(host.command, [1., 1., 1., 0.]);
        ray.barrier(host.command);
        host.submit();
        let transparent = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&actors, skin.view.as_raw())),
        );
        assert_eq!(
            transparent[0], lit[0],
            "transparent actor skin must not cast a solid local shadow"
        );
        println!(
            "cave baseline={:?} GI={:?}; local light={:?} actor shadow={:?}",
            baseline[0][0], gi[0][0], lit[0][0], blocked[0][0]
        );
    }
}

#[test]
#[ignore = "Explicit Vulkan local light endpoint occlusion regression"]
fn surface_gpu_local_light_does_not_skip_unrelated_geometry_near_emitter() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let normal = Image::new(&host, 1, 1, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = [
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
        ];
        host.begin();
        normal.initialize(host.command, [0., 0., -1., 0.5]);
        albedo.initialize(host.command, [0.4, 0.4, 0.4, 1.4]);
        atlas.initialize(host.command, [1.; 4]);
        for image in &outputs { image.initialize(host.command, [0.; 4]); }
        ray.barrier(host.command);
        host.submit();
        let views = [atlas.view.as_raw(), normal.view.as_raw(), albedo.view.as_raw()];
        let mut frame = identity_frame(1, 1);
        frame[75] = 0.;
        ray.scene(&triangle([[100., -4., -4.], [100., 4., -4.], [100., 0., 4.]], 0.)).unwrap();
        let ambient = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        ray.set_surface_lights(&[0., 0., -1., 15., 1., 1., 1., 0.], 1).unwrap();
        let lit = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(lit[0][0][0] > ambient[0][0][0] + 0.3);
        // This wall is just 0.2 blocks before a held light. It is not an emitter,
        // even though it shares the cell containing the source position.
        ray.scene(&triangle([[-4., -4., -0.8], [4., -4., -0.8], [0., 4., -0.8]], 0.)).unwrap();
        let blocked = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!((blocked[0][0][0] - ambient[0][0][0]).abs() < 0.002,
            "a real wall near a light must still occlude: ambient={:?}, lit={:?}, blocked={:?}",
            ambient[0][0], lit[0][0], blocked[0][0]);
        // A full emissive block's front face must still let its own source out.
        ray.set_surface_lights(&[0.5, 0.5, -1.5, 15., 1., 1., 1., 0.], 1).unwrap();
        ray.scene(&triangle([[0., 0., -1.], [1., 0., -1.], [0.5, 1., -1.]], 15.)).unwrap();
        let emitter = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(emitter[0][0][0] > ambient[0][0][0] + 0.3,
            "an emissive block must not occlude its own source: {:?}", emitter[0][0]);
        // A different emitting surface still casts a shadow. Its hit cell is
        // z=-1, while the actual source belongs to z=-2.
        ray.scene(&triangle([[-4., -4., -0.95], [4., -4., -0.95], [0., 4., -0.95]], 15.)).unwrap();
        let other_emitter = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!((other_emitter[0][0][0] - ambient[0][0][0]).abs() < 0.002,
            "a different emitting block must still occlude the source: {:?}", other_emitter[0][0]);
        println!("endpoint ambient={:?} lit={:?} blocked={:?} emitter={:?}",
            ambient[0][0], lit[0][0], blocked[0][0], emitter[0][0]);
    }
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
        capture_albedo.clear(host.command, [0.1, 0.2, 0.3, 9.05]);
        ray.barrier(host.command);
        host.submit();
        let coated_material = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            coated_material, clear,
            "coated dielectric class 9 must receive native reflection radiance"
        );
        host.begin();
        capture_albedo.clear(host.command, [0.1, 0.2, 0.3, 2.05]);
        ray.barrier(host.command);
        host.submit();
        let metal_material = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(
            metal_material, clear,
            "explicit reflective metal class 2 must keep model reflections"
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
                [outputs[0].handle, outputs[1].handle, vk::Image::null()],
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
        [outputs[0].handle, outputs[1].handle, vk::Image::null()],
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
            pool.slots[0]
                .state
                .surface
                .as_ref()
                .unwrap()
                .actors
                .as_ref()
                .unwrap()
                .handle
                != pool.slots[1]
                    .state
                    .surface
                    .as_ref()
                    .unwrap()
                    .actors
                    .as_ref()
                    .unwrap()
                    .handle,
            "pending actor payload buffers cannot alias"
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

fn actor_box_payload(inverse: [f32; 16], transparent_entry: bool) -> [f32; ACTOR_FLOATS] {
    let mut data = [0.; ACTOR_FLOATS];
    data[0] = 1.;
    data[4..20].copy_from_slice(&inverse);
    data[24..27].fill(1.);
    for face in 0..6 {
        data[4 + 24 + face * 4 + 3] = if face == 0 && transparent_entry {
            0.75
        } else {
            0.25
        };
        data[4 + 48 + face * 4 + 3] = 0.5;
    }
    data
}
// Runs the production actor GLSL without requiring ray-query hardware. This
// isolates slab distances, entry/exit alpha, UVs and normals from triangle tracing.
unsafe fn actor_compute(host: &Host, actors: &[f32], queries: &[[f32; 8]]) -> Vec<[f32; 8]> {
    let full = if actors.len() == ACTOR_FLOATS {
        expand_surface_actors(actors).unwrap()
    } else {
        validate_surface_actors_extended(actors).unwrap();
        actors.try_into().unwrap()
    };
    let actor_buffer = Buffer::new(
        &host.device,
        &host.memory,
        (EXTENDED_ACTOR_FLOATS * 4) as u64,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        true,
    )
    .unwrap();
    actor_buffer.upload(&full).unwrap();
    let query_buffer = Buffer::new(
        &host.device,
        &host.memory,
        std::mem::size_of_val(queries) as u64,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        true,
    )
    .unwrap();
    query_buffer.upload(queries).unwrap();
    let output = Buffer::new(
        &host.device,
        &host.memory,
        (queries.len() * 32) as u64,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        true,
    )
    .unwrap();
    let skin = Image::new(host, 2, 1, vk::Format::R32G32B32A32_SFLOAT, true);
    host.begin();
    skin.initialize(host.command, [0.; 4]);
    host.submit();
    upload_capture(
        host,
        &skin,
        2,
        1,
        &[[0.8, 0.2, 0.1, 1.], [0.1, 0.9, 0.2, 0.]],
    );
    let bindings: Vec<_> = [
        (0, vk::DescriptorType::STORAGE_BUFFER),
        (1, vk::DescriptorType::STORAGE_BUFFER),
        (7, vk::DescriptorType::STORAGE_BUFFER),
        (8, vk::DescriptorType::COMBINED_IMAGE_SAMPLER),
    ]
    .into_iter()
    .map(|(binding, ty)| {
        vk::DescriptorSetLayoutBinding::default()
            .binding(binding)
            .descriptor_type(ty)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
    })
    .collect();
    let layout = host
        .device
        .create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )
        .unwrap();
    let sizes = [
        vk::DescriptorPoolSize {
            ty: vk::DescriptorType::STORAGE_BUFFER,
            descriptor_count: 3,
        },
        vk::DescriptorPoolSize {
            ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
            descriptor_count: 1,
        },
    ];
    let pool = host
        .device
        .create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .max_sets(1)
                .pool_sizes(&sizes),
            None,
        )
        .unwrap();
    let set = host
        .device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(pool)
                .set_layouts(&[layout]),
        )
        .unwrap()[0];
    for (binding, buffer) in [(0, &output), (1, &query_buffer), (7, &actor_buffer)] {
        let info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.handle)
            .range(buffer.size)];
        host.device.update_descriptor_sets(
            &[vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(binding)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&info)],
            &[],
        );
    }
    let sampler = host
        .device
        .create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::NEAREST)
                .min_filter(vk::Filter::NEAREST)
                .max_lod(0.),
            None,
        )
        .unwrap();
    let info = [vk::DescriptorImageInfo::default()
        .sampler(sampler)
        .image_view(skin.view)
        .image_layout(vk::ImageLayout::GENERAL)];
    host.device.update_descriptor_sets(
        &[vk::WriteDescriptorSet::default()
            .dst_set(set)
            .dst_binding(8)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&info)],
        &[],
    );
    let pipeline_layout = host
        .device
        .create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default().set_layouts(&[layout]),
            None,
        )
        .unwrap();
    let binary = include_bytes!("../shaders/surface_actors_test.spv");
    let words: Vec<u32> = binary
        .chunks_exact(4)
        .map(|v| u32::from_le_bytes(v.try_into().unwrap()))
        .collect();
    let module = host
        .device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
        .unwrap();
    let name = std::ffi::CString::new("main").unwrap();
    let pipeline = host
        .device
        .create_compute_pipelines(
            vk::PipelineCache::null(),
            &[vk::ComputePipelineCreateInfo::default()
                .layout(pipeline_layout)
                .stage(
                    vk::PipelineShaderStageCreateInfo::default()
                        .stage(vk::ShaderStageFlags::COMPUTE)
                        .module(module)
                        .name(&name),
                )],
            None,
        )
        .unwrap()[0];
    host.device.destroy_shader_module(module, None);
    host.begin();
    host.device
        .cmd_bind_pipeline(host.command, vk::PipelineBindPoint::COMPUTE, pipeline);
    host.device.cmd_bind_descriptor_sets(
        host.command,
        vk::PipelineBindPoint::COMPUTE,
        pipeline_layout,
        0,
        &[set],
        &[],
    );
    host.device
        .cmd_dispatch(host.command, queries.len() as u32, 1, 1);
    host.device.cmd_pipeline_barrier(
        host.command,
        vk::PipelineStageFlags::COMPUTE_SHADER,
        vk::PipelineStageFlags::HOST,
        vk::DependencyFlags::empty(),
        &[vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::HOST_READ)],
        &[],
        &[],
    );
    host.submit();
    let pointer = host
        .device
        .map_memory(output.memory, 0, output.size, vk::MemoryMapFlags::empty())
        .unwrap();
    let result = std::slice::from_raw_parts(pointer as *const [f32; 8], queries.len()).to_vec();
    host.device.unmap_memory(output.memory);
    host.device.destroy_pipeline(pipeline, None);
    host.device.destroy_pipeline_layout(pipeline_layout, None);
    host.device.destroy_descriptor_pool(pool, None);
    host.device.destroy_descriptor_set_layout(layout, None);
    host.device.destroy_sampler(sampler, None);
    result
}

#[test]
#[ignore = "Explicit Vulkan compute actor-math acceptance; works with lavapipe"]
fn actor_compute_world_distance_parallel_inside_alpha_and_inverse_transpose() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new_with_ray(false);
        let mut inverse = [0.; 16];
        inverse[0] = 0.5;
        inverse[5] = 0.5;
        inverse[10] = 0.5;
        inverse[15] = 1.;
        let opaque = actor_box_payload(inverse, false);
        let queries = [
            [-2., 1., 1., 0., 1., 0., 0., 10.],
            [-2., 3., 1., 0., 1., 0., 0., 10.],
            [1., 1., 1., 0., 1., 0., 0., 10.],
            [-2., 1., 1., 0., 1., 0., 0., 1.99],
        ];
        let hits = actor_compute(&host, &opaque, &queries);
        assert_eq!(
            hits[0][3], 2.,
            "world distance must survive non-unit inverse scale"
        );
        assert_eq!(hits[0][0], -1.);
        assert_eq!(hits[1][3], -1., "parallel outside slab misses");
        assert_eq!(hits[2][3], 1., "inside origin selects exit");
        assert_eq!(hits[3][3], -1., "nearer terrain cutoff occludes actor");
        assert!(
            (hits[0][4] - 0.8f32.powf(2.2)).abs() < 1e-5,
            "UV rows sample and linearize skin"
        );
        let transparent = actor_box_payload(inverse, true);
        let hits = actor_compute(&host, &transparent, &queries);
        assert_eq!(hits[0][3], 4., "transparent entry must expose opaque exit");
        let mut invisible = opaque;
        for face in 0..6 {
            invisible[4 + 24 + face * 4 + 3] = 0.75;
        }
        assert_eq!(
            actor_compute(&host, &invisible, &queries)[0][3],
            -1.,
            "transparent skin is not geometry"
        );
        let mut nearest = opaque;
        nearest[0] = 2.;
        nearest[76..148].copy_from_slice(&opaque[4..76]);
        nearest[76 + 12] = 1.;
        assert_eq!(
            actor_compute(&host, &nearest, &[[-4., 1., 1., 0., 1., 0., 0., 10.]])[0][3],
            2.,
            "nearest actor wins independently of order"
        );
        inverse[4] = 1.;
        inverse[5] = 1.;
        inverse[10] = 1.;
        let sheared = actor_box_payload(inverse, false);
        let hit = actor_compute(&host, &sheared, &[[-4., 0.5, 0.5, 0., 1., 0., 0., 10.]])[0];
        assert!((hit[3] - 3.).abs() < 1e-5);
        assert!(
            (hit[0] + 1. / 5f32.sqrt()).abs() < 1e-5 && (hit[1] + 2. / 5f32.sqrt()).abs() < 1e-5,
            "inverse transpose transforms the face normal"
        );
    }
}

#[test]
#[ignore = "Explicit RX 9070 XT actor/terrain closest-hit acceptance"]
fn surface_gpu_actor_skin_alpha_and_nearer_block_occlusion() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let normal = Image::new(&host, 1, 1, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let skin = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = [
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
            Image::new(&host, 1, 1, vk::Format::R16G16B16A16_SFLOAT, false),
        ];
        host.begin();
        let diagonal = std::f32::consts::FRAC_1_SQRT_2;
        normal.initialize(host.command, [-diagonal, 0., -diagonal, 0.5]);
        albedo.initialize(host.command, [0.1, 0.2, 0.3, 4.05]);
        atlas.initialize(host.command, [0., 0., 1., 1.]);
        skin.initialize(host.command, [1., 0., 0., 1.]);
        for image in &outputs {
            image.initialize(host.command, [0.; 4]);
        }
        ray.barrier(host.command);
        host.submit();
        let mut frame = identity_frame(1, 1);
        frame[75] = 0.;
        let views = [
            atlas.view.as_raw(),
            normal.view.as_raw(),
            albedo.view.as_raw(),
        ];
        let target = triangle([[-3., -4., -4.], [-3., 4., -4.], [-3., 0., 4.]], 0.);
        ray.scene(&target).unwrap();
        let mut inverse = [0.; 16];
        inverse[0] = 1.;
        inverse[5] = 0.5;
        inverse[10] = 1.;
        inverse[15] = 1.;
        inverse[12] = 2.;
        inverse[13] = 0.5;
        let actors = actor_box_payload(inverse, false);
        let reflected = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&actors, skin.view.as_raw())),
        );
        assert!(
            reflected[1][0][0] > 0.19 && reflected[1][0][2] < 0.001 && reflected[1][0][3] == 1.,
            "skin actor before terrain must reflect red"
        );
        host.begin();
        skin.clear(host.command, [1., 0., 0., 0.]);
        ray.barrier(host.command);
        host.submit();
        let transparent = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&actors, skin.view.as_raw())),
        );
        assert!(
            transparent[1][0][2] > 0.005 && transparent[1][0][0] < 0.001,
            "transparent player must expose terrain"
        );
        host.begin();
        skin.clear(host.command, [1., 0., 0., 1.]);
        ray.barrier(host.command);
        host.submit();
        let blocker = triangle([[-0.5, -4., -4.], [-0.5, 4., -4.], [-0.5, 0., 4.]], 0.);
        ray.scene(&blocker).unwrap();
        let occluded = readback_actors(
            &host,
            &mut ray,
            &frame,
            &outputs,
            views,
            1,
            1,
            Some((&actors, skin.view.as_raw())),
        );
        assert!(
            occluded[1][0][2] > 0.005 && occluded[1][0][0] < 0.001,
            "nearer block must occlude player"
        );
        // Legacy dispatch must upload initialized zero actors, even after actor dispatch.
        ray.scene(&target).unwrap();
        let legacy = readback(&host, &mut ray, &frame, &outputs, views, 1, 1);
        assert!(
            legacy[1][0][2] > 0.005 && legacy[1][0][0] < 0.001,
            "legacy call must not retain stale actor payload"
        );
    }
}

#[test]
#[ignore = "Explicit Vulkan compute extended actor index63 acceptance; works with lavapipe"]
fn actor_compute_extended_index63_is_visible() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new_with_ray(false);
        let mut inverse = [0.; 16];
        inverse[0] = 0.5;
        inverse[5] = 0.5;
        inverse[10] = 0.5;
        inverse[15] = 1.;
        let legacy = actor_box_payload(inverse, false);
        let mut data = [0.; EXTENDED_ACTOR_FLOATS];
        data[0] = 64.;
        for index in 0..64 {
            let start = 4 + index * 72;
            data[start..start + 72].copy_from_slice(&legacy[4..76]);
            if index < 63 {
                data[start + 13] = 100.;
            }
        }
        let hit = actor_compute(&host, &data, &[[-2., 1., 1., 0., 1., 0., 0., 10.]])[0];
        assert_eq!(
            hit[3], 2.,
            "actor63 must be intersected rather than silently dropped"
        );
        assert_eq!(hit[7], 1.);
        // A legacy empty payload also remains a miss with the64-box shader.
        let empty = actor_compute(
            &host,
            &[0.; ACTOR_FLOATS],
            &[[-2., 1., 1., 0., 1., 0., 0., 10.]],
        )[0];
        assert_eq!(empty[3], -1.);
    }
}

fn translated(x: f32, y: f32, z: f32) -> [f32; 12] {
    [1., 0., 0., x, 0., 1., 0., y, 0., 0., 1., z]
}
#[test]
#[ignore = "Explicit headless Vulkan acceptance (GREENMARINE_GPU_TEST_DEVICE selects the GPU)"]
fn surface_gpu_external_meshes_reflect_shadow_move_and_survive_flights() {
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
        let center = (height / 2 * width + width / 2) as usize;
        let world_target = triangle(
            [[-3.0, -1.0, -0.5], [-3.0, 1.0, -0.5], [-3.0, 0.0, 1.5]],
            4.0,
        );
        ray.scene(&world_target).unwrap();
        let reference = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(reference[1][center][3], 1.0, "world reference reflects the target");

        // Same triangle authored in mesh space and placed by its instance transform.
        let world_miss = triangle([[3.0, -1.0, -0.5], [3.0, 1.0, -0.5], [3.0, 0.0, 1.5]], 4.0);
        ray.scene(&world_miss).unwrap();
        let empty = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(empty[1][center], [0.0; 4], "world alone has nothing to reflect");
        let local_target = triangle([[0.0, -1.0, -0.5], [0.0, 1.0, -0.5], [0.0, 0.0, 1.5]], 4.0);
        let factory = ray.scene_factory();
        let target = factory.prepare_mesh(&local_target).unwrap();
        ray.set_external_frame(vec![ExternalInstance {
            mesh: target.clone(),
            transform: translated(-3.0, 0.0, 0.0),
        }])
        .unwrap();
        let external = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert!(target.built.load(Ordering::Acquire), "first recording builds the mesh BLAS");
        for channel in 0..4 {
            assert!(
                (external[1][center][channel] - reference[1][center][channel]).abs() < 0.002,
                "external instance must reflect exactly like world geometry, channel {channel}: {:?} vs {:?}",
                external[1][center],
                reference[1][center]
            );
        }
        assert_eq!(external[0][center][3], 1.0, "reflected mesh does not shadow the surfel");
        println!(
            "surface_gpu external reflection={:?} reference={:?}",
            external[1][center], reference[1][center]
        );

        // An external blocker above the surfel casts a sun shadow onto captured terrain.
        let local_blocker = triangle([[-1.0, 0.0, -1.0], [1.0, 0.0, -1.0], [0.0, 0.0, 1.0]], 0.0);
        let blocker = factory.prepare_mesh(&local_blocker).unwrap();
        ray.set_external_frame(vec![
            ExternalInstance {
                mesh: target.clone(),
                transform: translated(-3.0, 0.0, 0.0),
            },
            ExternalInstance {
                mesh: blocker.clone(),
                transform: translated(-1.0, 1.0, 0.5),
            },
        ])
        .unwrap();
        let shadowed = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(shadowed[0][center][3], 0.0, "external blocker must occlude the sun");
        assert!(
            (shadowed[0][center][0] - 0.2).abs() < 0.002,
            "shadowed surfel keeps ambient only: {:?}",
            shadowed[0][center]
        );
        assert_eq!(shadowed[1][center][3], 1.0, "reflection survives the shadow caster");

        // Moving only the transform must move the hit: the TLAS is rebuilt per recording.
        ray.set_external_frame(vec![ExternalInstance {
            mesh: target.clone(),
            transform: translated(6.0, 0.0, 0.0),
        }])
        .unwrap();
        let moved = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(moved[1][center], [0.0; 4], "moved instance leaves the reflected ray");
        assert_eq!(moved[0][center][3], 1.0, "moved blocker no longer shadows");

        // Untextured tint mode for model textures that are not in the block atlas.
        let mut tinted = local_target;
        tinted[18] = 2.0;
        let tint_mesh = factory.prepare_mesh(&tinted).unwrap();
        ray.set_external_frame(vec![ExternalInstance {
            mesh: tint_mesh,
            transform: translated(-3.0, 0.0, 0.0),
        }])
        .unwrap();
        let tint = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        let scale = reference[1][center][1] / (51f32 / 255.0).powf(2.2);
        for (channel, value) in [0.5f32, 1.0, 0.25].iter().enumerate() {
            let expected = value.powf(2.2) * scale;
            assert!(
                (tint[1][center][channel] - expected).abs() < 0.004,
                "tint-only albedo channel {channel}: {} vs {expected}",
                tint[1][center][channel]
            );
        }

        ray.set_external_frame(Vec::new()).unwrap();
        let cleared = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(cleared, empty, "an empty frame returns to the world TLAS");

        // Flight leases copy the frame, rebuild their own TLAS and keep meshes alive.
        ray.set_external_frame(vec![ExternalInstance {
            mesh: target.clone(),
            transform: translated(-3.0, 0.0, 0.0),
        }])
        .unwrap();
        assert_eq!(ray.begin_submission(1).unwrap(), 0);
        let leased = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(ray.submission_submitted(1).unwrap(), 0);
        assert_eq!(ray.submission_completed(1).unwrap(), 0);
        assert_eq!(leased[1][center], external[1][center], "flight path matches direct path");

        let late = factory.prepare_mesh(&local_blocker).unwrap();
        ray.set_external_frame(vec![ExternalInstance {
            mesh: late.clone(),
            transform: translated(-1.0, 1.0, 0.5),
        }])
        .unwrap();
        assert_eq!(ray.begin_submission(2).unwrap(), 0);
        host.begin();
        ray.record_surface(
            host.command,
            &frame,
            width,
            height,
            [outputs[0].handle, outputs[1].handle, vk::Image::null()],
            views,
        )
        .unwrap();
        host.device.end_command_buffer(host.command).unwrap();
        assert!(late.built.load(Ordering::Acquire));
        assert_eq!(ray.submission_cancel(2).unwrap(), 0);
        assert!(
            !late.built.load(Ordering::Acquire),
            "a cancelled recording must not leave its BLAS marked built"
        );
        assert_eq!(ray.begin_submission(3).unwrap(), 0);
        let rebuilt = readback(&host, &mut ray, &frame, &outputs, views, width, height);
        assert_eq!(ray.submission_submitted(3).unwrap(), 0);
        assert_eq!(ray.submission_completed(3).unwrap(), 0);
        assert_eq!(rebuilt[0][center][3], 0.0, "rebuilt blocker shadows after cancel");
        println!(
            "surface_gpu external shadow={:?} moved={:?} tint={:?} flight={:?}",
            shadowed[0][center], moved[1][center], tint[1][center], leased[1][center]
        );
    }
}

#[test]
#[ignore = "Explicit Vulkan local-light penumbra continuity regression"]
fn surface_gpu_local_light_penumbra_is_stable_across_receiver_basis_threshold() {
    let _gpu_test = GPU_TEST_LOCK.lock().unwrap();
    unsafe {
        let host = Host::new();
        let mut ray = RayState::new(&host.instance, &host.device, host.physical).unwrap();
        ray.init_surface().unwrap();
        let (width, height) = (1, 1);
        let normal = Image::new(&host, width, height, vk::Format::R32G32B32A32_SFLOAT, true);
        let albedo = Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, true);
        let atlas = Image::new(&host, 1, 1, vk::Format::R8G8B8A8_UNORM, true);
        let outputs = [Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false), Image::new(&host, width, height, vk::Format::R16G16B16A16_SFLOAT, false)];
        host.begin();
        normal.initialize(host.command, [0., 1., 0., 0.5]);
        albedo.initialize(host.command, [0.5, 0.5, 0.5, 1.4]);
        atlas.initialize(host.command, [1.;4]);
        for image in &outputs {image.initialize(host.command, [0.;4]);}
        ray.barrier(host.command);host.submit();
        ray.scene(&triangle([[100., -4., -4.], [100., 4., -4.], [100., 0., 4.]], 0.)).unwrap();
        let views = [atlas.view.as_raw(), normal.view.as_raw(), albedo.view.as_raw()];
        let mut frame = identity_frame(width, height);
        frame[0]=2.;frame[5]=2.;frame[10]=-4.;
        frame[21]=0.;frame[22]=1.;frame[25]=1.;frame[26]=0.;frame[29]=2.;
        frame[75]=0.;frame[76]=0.02;
        // Probe the receiver-relative emitter basis switch with one nearby hand
        // beside a source, while the source itself is outside that box.
        let mut hand_inverse=[0.;16];hand_inverse[15]=1.;
        for (axis,(lo,hi)) in [(0,(0.08,0.5)),(1,(0.95,1.2)),(2,(-0.5,0.5))] {
            hand_inverse[axis*5]=1./(hi-lo);hand_inverse[12+axis]=-lo/(hi-lo);
        }
        let hand=actor_box_payload(hand_inverse,false);
        ray.set_surface_lights(&[0.,1.,0.,14.,1.25,0.74,0.32,0.],1).unwrap();
        let radius=(1.0f32/(0.9*0.9)-1.).sqrt()/2.0f32.sqrt();
        let mut nearby_values=Vec::new();
        for scale in [0.9999,1.0001] {
            frame[28]=radius*scale;frame[30]=radius*scale;
            let values=readback_actors(&host,&mut ray,&frame,&outputs,views,width,height,Some((&hand,atlas.view.as_raw())));
            let centre=(height/2*width+width/2) as usize;
            println!("basis source receiver scale={scale} centre={:?}",values[0][centre]);
            nearby_values.push(values[0][centre][0]);
        }
        assert!((nearby_values[0]-nearby_values[1]).abs()<0.005,
            "an infinitesimal receiver move must not rotate the emitting samples and halve visibility: {nearby_values:?}");
    }
}
