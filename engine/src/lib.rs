use ash::vk::Handle;
use ash::{vk, Device, Entry, Instance};
mod rt;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::{c_char, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, Mutex,
};
use std::time::Instant;

struct BorrowedContext {
    _device: Device,
    _instance: Instance,
    _entry: Arc<Entry>,
    epoch: u64,
    physical: vk::PhysicalDevice,
    ray: Option<rt::RayState>,
}

static CONTEXT: Mutex<Option<BorrowedContext>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn greenmarine_abi_version() -> u32 {
    1
}

// Only Minecraft-owned, live handles may cross this trusted host boundary.
#[no_mangle]
pub extern "C" fn greenmarine_attach(
    instance: u64,
    physical: u64,
    device: u64,
    queue: u64,
    family: i32,
) -> i32 {
    if instance == 0 || physical == 0 || device == 0 || queue == 0 || family < 0 {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Ok(entry) = Entry::load() else {
            return -2;
        };
        let instance = Instance::load(entry.static_fn(), vk::Instance::from_raw(instance));
        let properties =
            instance.get_physical_device_properties(vk::PhysicalDevice::from_raw(physical));
        if properties.api_version < vk::API_VERSION_1_1 {
            return -4;
        }
        let device = Device::load(instance.fp_v1_0(), vk::Device::from_raw(device));
        let Ok(mut context) = CONTEXT.lock() else {
            return -3;
        };
        invalidate_prepared();
        *context = Some(BorrowedContext {
            _device: device,
            _instance: instance,
            _entry: Arc::new(entry),
            epoch: NEXT_EPOCH.fetch_add(1, Ordering::Relaxed),
            physical: vk::PhysicalDevice::from_raw(physical),
            ray: None,
        });
        ACTIVE_EPOCH.store(context.as_ref().unwrap().epoch, Ordering::Release);
        0
    }))
    .unwrap_or(-3)
}

#[no_mangle]
pub extern "C" fn greenmarine_sample(time: f32, intensity: f32) -> f32 {
    catch_unwind(|| {
        if !time.is_finite() || !intensity.is_finite() || !(0.0..=16.0).contains(&intensity) {
            return 0.0;
        }
        intensity * (0.75 + 0.25 * time.sin())
    })
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "C" fn greenmarine_detach() {
    let _ = catch_unwind(|| {
        if let Ok(mut context) = CONTEXT.lock() {
            // ash wrappers do not destroy the host's Vulkan handles on drop.
            ACTIVE_EPOCH.store(0, Ordering::Release);
            invalidate_prepared();
            *context = None;
        }
    });
}

#[cfg(test)]
mod tests {
    #[test]
    fn rejects_null_host_handles_without_loading_vulkan() {
        assert_eq!(super::greenmarine_attach(0, 0, 0, 0, 0), -1);
    }

    #[test]
    fn evaluates_pulse_with_known_phase_and_rejects_non_finite_input() {
        assert_eq!(super::greenmarine_sample(0.0, 2.0), 1.5);
        assert!(
            (super::greenmarine_sample(std::f32::consts::FRAC_PI_2, 2.0) - 2.0).abs() < 0.00001
        );
        assert_eq!(super::greenmarine_sample(f32::NAN, 2.0), 0.0);
        assert_eq!(super::greenmarine_sample(0.0, -1.0), 0.0);
    }
}

#[cfg(test)]
mod rt_contract_tests {
    #[test]
    fn rejects_invalid_scene_abi_and_nan_coordinates() {
        assert!(crate::rt::validate_scene(&[0.0; 23]).is_err());
        let mut triangle = [0.0f32; 24];
        triangle[4] = 1.0;
        triangle[9] = 1.0;
        assert!(crate::rt::validate_scene(&triangle).is_ok());
        triangle[0] = f32::NAN;
        assert!(crate::rt::validate_scene(&triangle).is_err());
    }
    #[test]
    fn bounds_native_output_allocation_and_abi_entrypoints() {
        assert!(crate::rt::output_bytes(0, 540).is_err());
        assert_eq!(crate::rt::output_bytes(600, 338).unwrap(), 6_489_600);
        assert_eq!(crate::rt::output_bytes(1, 1).unwrap(), 40);
        assert!(crate::rt::output_bytes(u32::MAX, u32::MAX).is_err());
        assert_eq!(crate::greenmarine_rt_init(0), -20);
    }
}

impl Drop for BorrowedContext {
    fn drop(&mut self) {
        if self.ray.is_some() {
            unsafe {
                let _ = self._device.device_wait_idle();
            }
            self.ray.take();
        }
    }
}

#[no_mangle]
pub extern "C" fn greenmarine_rt_api_version() -> u32 {
    1
}

fn ray_result(action: impl FnOnce(&mut BorrowedContext) -> Result<(), String>) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(mut guard) = CONTEXT.lock() else {
            return -3;
        };
        let Some(context) = guard.as_mut() else {
            return -2;
        };
        match action(context) {
            Ok(()) => 0,
            Err(error) => {
                eprintln!("GreenMarine RT: {error}");
                -21
            }
        }
    }))
    .unwrap_or(-3)
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_init(enabled: u32) -> i32 {
    if enabled != 1 {
        return -20;
    }
    ray_result(|context| {
        if context.ray.is_none() {
            context.ray = Some(unsafe {
                rt::RayState::new(&context._instance, &context._device, context.physical)?
            });
        }
        Ok(())
    })
}
#[no_mangle]
/// # Safety
/// `values` must reference `triangles * 24` readable, aligned floats for the call duration.
pub unsafe extern "C" fn greenmarine_rt_scene(values: *const f32, triangles: u32) -> i32 {
    if values.is_null() || triangles == 0 || triangles > 1_000_000 {
        return -1;
    }
    ray_result(|context| unsafe {
        context
            .ray
            .as_mut()
            .ok_or("RT pipeline is unavailable")?
            .scene(std::slice::from_raw_parts(values, triangles as usize * 24))
    })
}
#[no_mangle]
/// # Safety
/// `frame` must reference 80 readable, aligned floats. Handles must be live objects
/// on the attached host device, and `command` must be recording outside a render pass.
pub unsafe extern "C" fn greenmarine_rt_record(
    command: u64,
    frame: *const f32,
    width: u32,
    height: u32,
    color: u64,
    normal: u64,
    albedo: u64,
    atlas: u64,
) -> i32 {
    if command == 0 || frame.is_null() || color == 0 || normal == 0 || albedo == 0 || atlas == 0 {
        return -1;
    }
    ray_result(|context| unsafe {
        context
            .ray
            .as_mut()
            .ok_or("RT pipeline is unavailable")?
            .record(
                vk::CommandBuffer::from_raw(command),
                std::slice::from_raw_parts(frame, 80),
                width,
                height,
                [
                    vk::Image::from_raw(color),
                    vk::Image::from_raw(normal),
                    vk::Image::from_raw(albedo),
                ],
                atlas,
            )
    })
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_reset_scene() -> i32 {
    ray_result(|context| unsafe {
        advance_epoch(context);
        if let Some(ray) = context.ray.as_mut() {
            ray.reset_scene()?;
        }
        Ok(())
    })
}

#[cfg(test)]
mod surface_rt_contract_tests {
    #[test]
    fn surface_abi_is_additive_and_off_requires_no_host() {
        assert_eq!(crate::greenmarine_abi_version(), 1);
        assert_eq!(crate::greenmarine_rt_api_version(), 1);
        assert_eq!(crate::greenmarine_surface_rt_api_version(), 1);
        assert_eq!(crate::greenmarine_surface_rt_init(0), -20);
        unsafe {
            assert_eq!(
                crate::greenmarine_surface_rt_record(0, std::ptr::null(), 1, 1, 1, 2, 3, 4, 5),
                -1
            );
        }
    }
    #[test]
    fn surface_outputs_pack_two_half_float_images_without_odd_size_overlap() {
        let layout = crate::rt::surface_output_layout(1, 1).unwrap();
        assert_eq!(layout.reflection_offset, 16);
        assert_eq!(layout.bytes, 24);
        let layout = crate::rt::surface_output_layout(601, 339).unwrap();
        assert_eq!(layout.reflection_offset % 16, 0);
        assert!(layout.reflection_offset >= 601 * 339 * 8);
        assert_eq!(layout.bytes, layout.reflection_offset + 601 * 339 * 8);
        assert!(crate::rt::surface_output_layout(0, 1).is_err());
        assert!(crate::rt::surface_output_layout(u32::MAX, u32::MAX).is_err());
    }
    #[test]
    fn surface_descriptors_borrow_the_parent_scene_and_capture_views() {
        use ash::vk::DescriptorType as D;
        assert!(
            crate::rt::surface_descriptor_types()
                == [
                    D::ACCELERATION_STRUCTURE_KHR,
                    D::STORAGE_BUFFER,
                    D::STORAGE_BUFFER,
                    D::UNIFORM_BUFFER,
                    D::COMBINED_IMAGE_SAMPLER,
                    D::COMBINED_IMAGE_SAMPLER,
                    D::COMBINED_IMAGE_SAMPLER,
                    D::STORAGE_BUFFER,
                    D::COMBINED_IMAGE_SAMPLER
                ]
        );
    }
}

#[no_mangle]
pub extern "C" fn greenmarine_surface_rt_api_version() -> u32 {
    1
}
#[no_mangle]
pub extern "C" fn greenmarine_surface_rt_init(enabled: u32) -> i32 {
    if enabled != 1 {
        return -20;
    }
    ray_result(|context| unsafe {
        // Surface rays are an optional child of the existing world RT scene.
        context
            .ray
            .as_mut()
            .ok_or("Parent RT pipeline is unavailable")?
            .init_surface()
    })
}
#[no_mangle]
/// # Safety
/// Frame references 80 readable aligned floats. Borrowed handles belong to the
/// attached device and remain live until submission completes. Captures are
/// sampled RGBA32F normal/depth and RGBA16F linear albedo/material views in GENERAL.
/// Outputs are distinct RGBA16F images with TRANSFER_DST usage, in GENERAL.
/// Command is recording outside a render pass, on the host's ordered render queue.
pub unsafe extern "C" fn greenmarine_surface_rt_record(
    command: u64,
    frame: *const f32,
    width: u32,
    height: u32,
    lighting: u64,
    reflection: u64,
    atlas: u64,
    normal: u64,
    albedo: u64,
) -> i32 {
    if command == 0
        || frame.is_null()
        || lighting == 0
        || reflection == 0
        || lighting == reflection
        || atlas == 0
        || normal == 0
        || albedo == 0
    {
        return -1;
    }
    ray_result(|context| unsafe {
        context
            .ray
            .as_mut()
            .ok_or("Parent RT pipeline is unavailable")?
            .record_surface(
                vk::CommandBuffer::from_raw(command),
                std::slice::from_raw_parts(frame, 80),
                width,
                height,
                [
                    vk::Image::from_raw(lighting),
                    vk::Image::from_raw(reflection),
                ],
                [atlas, normal, albedo],
            )
    })
}

#[no_mangle]
pub extern "C" fn greenmarine_surface_rt_actors_api_version() -> u32 {
    1
}
#[no_mangle]
/// # Safety
/// Existing surface-record handle/frame rules apply. Actors references exactly
/// 1156 readable aligned floats (4624 bytes). Skin is a borrowed sampled view in
/// GENERAL layout, valid through host fence completion; zero is allowed for no actors.
pub unsafe extern "C" fn greenmarine_surface_rt_record_actors(
    command: u64,
    frame: *const f32,
    width: u32,
    height: u32,
    lighting: u64,
    reflection: u64,
    atlas: u64,
    normal: u64,
    albedo: u64,
    actors: *const f32,
    skin: u64,
) -> i32 {
    if command == 0
        || frame.is_null()
        || actors.is_null()
        || lighting == 0
        || reflection == 0
        || lighting == reflection
        || atlas == 0
        || normal == 0
        || albedo == 0
    {
        return -1;
    }
    let payload = std::slice::from_raw_parts(actors, rt::ACTOR_FLOATS);
    let count = match rt::validate_surface_actors(payload) {
        Ok(count) => count,
        Err(_) => return -1,
    };
    if count > 0 && skin == 0 {
        return -1;
    }
    ray_result(|context| unsafe {
        context
            .ray
            .as_mut()
            .ok_or("Parent RT pipeline is unavailable")?
            .record_surface_actors(
                vk::CommandBuffer::from_raw(command),
                std::slice::from_raw_parts(frame, 80),
                width,
                height,
                [
                    vk::Image::from_raw(lighting),
                    vk::Image::from_raw(reflection),
                ],
                [atlas, normal, albedo],
                payload,
                skin,
            )
    })
}

#[no_mangle]
pub extern "C" fn greenmarine_surface_rt_actors_extended_api_version() -> u32 {
    1
}
#[no_mangle]
/// # Safety
/// Existing surface-record handle/frame rules apply. Actors references exactly
/// 4612 readable aligned floats (18448 bytes). Skin is a borrowed sampled view in
/// GENERAL layout, valid through host fence completion; zero is allowed for no actors.
pub unsafe extern "C" fn greenmarine_surface_rt_record_actors_extended(
    command: u64,
    frame: *const f32,
    width: u32,
    height: u32,
    lighting: u64,
    reflection: u64,
    atlas: u64,
    normal: u64,
    albedo: u64,
    actors: *const f32,
    skin: u64,
) -> i32 {
    if command == 0
        || frame.is_null()
        || actors.is_null()
        || lighting == 0
        || reflection == 0
        || lighting == reflection
        || atlas == 0
        || normal == 0
        || albedo == 0
    {
        return -1;
    }
    let payload = std::slice::from_raw_parts(actors, rt::EXTENDED_ACTOR_FLOATS);
    let count = match rt::validate_surface_actors_extended(payload) {
        Ok(count) => count,
        Err(_) => return -1,
    };
    if count > 0 && skin == 0 {
        return -1;
    }
    ray_result(|context| unsafe {
        context
            .ray
            .as_mut()
            .ok_or("Parent RT pipeline is unavailable")?
            .record_surface_actors_extended(
                vk::CommandBuffer::from_raw(command),
                std::slice::from_raw_parts(frame, 80),
                width,
                height,
                [
                    vk::Image::from_raw(lighting),
                    vk::Image::from_raw(reflection),
                ],
                [atlas, normal, albedo],
                payload,
                skin,
            )
    })
}

#[cfg(test)]
mod async_rt_contract_tests {
    #[test]
    fn async_extension_preserves_versions_and_rejects_unattached_submissions() {
        assert_eq!(super::greenmarine_abi_version(), 1);
        assert_eq!(super::greenmarine_rt_api_version(), 1);
        assert_eq!(super::greenmarine_surface_rt_api_version(), 1);
        assert_eq!(super::greenmarine_rt_async_api_version(), 1);
        assert_eq!(super::greenmarine_rt_async_begin(0), -1);
        assert_eq!(super::greenmarine_rt_async_begin(1), -2);
        assert_eq!(super::greenmarine_rt_async_submitted(1), -2);
        assert_eq!(super::greenmarine_rt_async_completed(1), -2);
        assert_eq!(super::greenmarine_rt_async_cancel(1), -2);
    }
    #[test]
    fn preparation_rejects_invalid_input_and_reports_thread_local_status() {
        unsafe {
            assert_eq!(super::greenmarine_rt_scene_prepare(std::ptr::null(), 1), 0);
        }
        assert_eq!(super::greenmarine_rt_prepare_status(), -1);
        assert_eq!(super::greenmarine_rt_scene_publish(0), -1);
        assert_eq!(super::greenmarine_rt_scene_discard(0), -1);
    }
    #[test]
    fn preparation_status_and_error_are_isolated_between_worker_threads() {
        super::prepare_status(1, "caller busy");
        let other = std::thread::spawn(|| unsafe {
            assert_eq!(super::greenmarine_rt_scene_prepare(std::ptr::null(), 1), 0);
            assert_eq!(super::greenmarine_rt_prepare_status(), -1);
            assert_eq!(
                std::ffi::CStr::from_ptr(super::greenmarine_rt_prepare_error())
                    .to_str()
                    .unwrap(),
                "Invalid preparation triangle ABI"
            );
        });
        other.join().unwrap();
        assert_eq!(super::greenmarine_rt_prepare_status(), 1);
        unsafe {
            assert_eq!(
                std::ffi::CStr::from_ptr(super::greenmarine_rt_prepare_error())
                    .to_str()
                    .unwrap(),
                "caller busy"
            );
        }
    }
}

static NEXT_EPOCH: AtomicU64 = AtomicU64::new(1);
static ACTIVE_EPOCH: AtomicU64 = AtomicU64::new(0);
static TOKEN_ID: AtomicU64 = AtomicU64::new(1);
static PREPARE_COUNT: AtomicU64 = AtomicU64::new(0);
static PREPARE_NANOS: AtomicU64 = AtomicU64::new(0);
struct PreparedScene {
    epoch: u64,
    scene: Option<Arc<rt::Scene>>,
}
static PREPARED: Mutex<Option<HashMap<u64, PreparedScene>>> = Mutex::new(None);
thread_local! {
    static PREPARE_STATUS:Cell<i32>=const {Cell::new(0)};
    static PREPARE_ERROR:RefCell<CString>=RefCell::new(CString::new("").unwrap());
}
fn prepare_status(code: i32, message: &str) -> u64 {
    PREPARE_STATUS.with(|s| s.set(code));
    PREPARE_ERROR.with(|e| *e.borrow_mut() = CString::new(message.replace('\0', " ")).unwrap());
    if code < 0 {
        eprintln!("GreenMarine RT preparation: {message}");
    }
    0
}
fn invalidate_prepared() {
    if let Ok(mut p) = PREPARED.lock() {
        *p = None;
    }
}
fn advance_epoch(context: &mut BorrowedContext) {
    context.epoch = NEXT_EPOCH.fetch_add(1, Ordering::Relaxed);
    ACTIVE_EPOCH.store(context.epoch, Ordering::Release);
    invalidate_prepared();
}
fn async_result(action: impl FnOnce(&mut rt::RayState) -> Result<i32, String>) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(mut context) = CONTEXT.lock() else {
            return -3;
        };
        let Some(context) = context.as_mut() else {
            return -2;
        };
        let Some(ray) = context.ray.as_mut() else {
            return -2;
        };
        match action(ray) {
            Ok(code) => code,
            Err(e) => {
                eprintln!("GreenMarine async RT: {e}");
                -21
            }
        }
    }))
    .unwrap_or(-3)
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_async_api_version() -> u32 {
    1
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_async_begin(s: u64) -> i32 {
    if s == 0 {
        return -1;
    }
    async_result(|r| unsafe { r.begin_submission(s) })
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_async_submitted(s: u64) -> i32 {
    if s == 0 {
        return -1;
    }
    async_result(|r| r.submission_submitted(s))
}
#[no_mangle]
/// Host confirms the oldest submitted lease's GPU fence signaled. Never call for a closed but unpolled fence.
pub extern "C" fn greenmarine_rt_async_completed(s: u64) -> i32 {
    if s == 0 {
        return -1;
    }
    async_result(|r| r.submission_completed(s))
}
#[no_mangle]
/// Caller guarantees this recording will never be executed or submitted.
pub extern "C" fn greenmarine_rt_async_cancel(s: u64) -> i32 {
    if s == 0 {
        return -1;
    }
    async_result(|r| r.submission_cancel(s))
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_prepare_status() -> i32 {
    PREPARE_STATUS.with(Cell::get)
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_prepare_error() -> *const c_char {
    PREPARE_ERROR.with(|e| e.borrow().as_ptr())
}
#[no_mangle]
/// # Safety
/// Values reference triangle_count * 24 readable aligned floats until return.
/// The host must join preparation workers before destroying its Vulkan device or detaching.
pub unsafe extern "C" fn greenmarine_rt_scene_prepare(
    values: *const f32,
    triangle_count: u32,
) -> u64 {
    if values.is_null() || triangle_count == 0 || triangle_count > 1_000_000 {
        return prepare_status(-1, "Invalid preparation triangle ABI");
    }
    catch_unwind(AssertUnwindSafe(|| {
        // Clone only immutable device/factory state while holding the context lock.
        let (factory, epoch, _entry) = {
            let Ok(context) = CONTEXT.lock() else {
                return prepare_status(-3, "Context lock failed");
            };
            let Some(context) = context.as_ref() else {
                return prepare_status(-2, "No attached host");
            };
            let Some(ray) = context.ray.as_ref() else {
                return prepare_status(-2, "No RT pipeline");
            };
            (ray.scene_factory(), context.epoch, context._entry.clone())
        };
        let token = TOKEN_ID.fetch_add(1, Ordering::Relaxed);
        {
            let Ok(mut guard) = PREPARED.lock() else {
                return prepare_status(-3, "Prepared registry lock failed");
            };
            let registry = guard.get_or_insert_with(HashMap::new);
            if registry.len() >= 2 {
                return prepare_status(1, "Prepared scene capacity is busy");
            }
            if ACTIVE_EPOCH.load(Ordering::Acquire) != epoch {
                return prepare_status(-23, "Preparation context changed");
            }
            registry.insert(token, PreparedScene { epoch, scene: None });
        }
        let started = Instant::now();
        // Conversion, allocation and uploads occur with neither CONTEXT nor PREPARED locked.
        let prepared = factory.prepare(std::slice::from_raw_parts(
            values,
            triangle_count as usize * 24,
        ));
        let elapsed = started.elapsed().as_nanos() as u64;
        let Ok(mut guard) = PREPARED.lock() else {
            return prepare_status(-3, "Prepared registry lock failed");
        };
        let Some(registry) = guard.as_mut() else {
            return prepare_status(-23, "Preparation was invalidated");
        };
        if ACTIVE_EPOCH.load(Ordering::Acquire) != epoch {
            registry.remove(&token);
            return prepare_status(-23, "Preparation context changed");
        }
        match prepared {
            Ok(scene) => {
                let Some(slot) = registry.get_mut(&token) else {
                    return prepare_status(-23, "Preparation was cancelled");
                };
                slot.scene = Some(scene);
                PREPARE_COUNT.fetch_add(1, Ordering::Relaxed);
                PREPARE_NANOS.fetch_add(elapsed, Ordering::Relaxed);
                prepare_status(0, "");
                token
            }
            Err(e) => {
                registry.remove(&token);
                prepare_status(-21, &e)
            }
        }
    }))
    .unwrap_or_else(|_| prepare_status(-3, "Preparation panicked"))
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_scene_publish(token: u64) -> i32 {
    if token == 0 {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(mut context) = CONTEXT.lock() else {
            return -3;
        };
        let Some(context) = context.as_mut() else {
            return -2;
        };
        let Some(ray) = context.ray.as_mut() else {
            return -2;
        };
        let Ok(mut registry) = PREPARED.lock() else {
            return -3;
        };
        let Some(registry) = registry.as_mut() else {
            return -23;
        };
        let Some(prepared) = registry.get(&token) else {
            return -23;
        };
        if prepared.epoch != context.epoch {
            return -23;
        }
        let Some(scene) = prepared.scene.as_ref() else {
            return 1;
        };
        match ray.publish_scene(scene.clone()) {
            Ok(0) => {
                registry.remove(&token);
                0
            }
            Ok(code) => code,
            Err(e) => {
                eprintln!("GreenMarine RT publish: {e}");
                -21
            }
        }
    }))
    .unwrap_or(-3)
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_scene_discard(token: u64) -> i32 {
    if token == 0 {
        return -1;
    }
    let Ok(mut registry) = PREPARED.lock() else {
        return -3;
    };
    if registry.as_mut().and_then(|r| r.remove(&token)).is_some() {
        0
    } else {
        -23
    }
}
#[no_mangle]
/// # Safety
/// Same input and device-lifetime requirements as scene_prepare. This synchronous convenience still allocates on its calling thread.
pub unsafe extern "C" fn greenmarine_rt_scene_async(values: *const f32, count: u32) -> i32 {
    let token = greenmarine_rt_scene_prepare(values, count);
    if token == 0 {
        return greenmarine_rt_prepare_status();
    }
    let result = greenmarine_rt_scene_publish(token);
    if result != 0 {
        greenmarine_rt_scene_discard(token);
    }
    result
}
#[no_mangle]
pub extern "C" fn greenmarine_rt_reset_scene_async() -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(mut context) = CONTEXT.lock() else {
            return -3;
        };
        let Some(context) = context.as_mut() else {
            return -2;
        };
        let Some(ray) = context.ray.as_mut() else {
            return -2;
        };
        match ray.reset_scene_async() {
            Ok(0) => {
                advance_epoch(context);
                0
            }
            Ok(code) => code,
            Err(e) => {
                eprintln!("GreenMarine RT reset: {e}");
                -21
            }
        }
    }))
    .unwrap_or(-3)
}
#[no_mangle]
/// # Safety
/// Output references 14 writable u64 values. Statistics are cumulative, and polls never wait for GPU completion.
pub unsafe extern "C" fn greenmarine_rt_async_stats(output: *mut u64, count: u32) -> i32 {
    if output.is_null() || count != 14 {
        return -1;
    }
    async_result(|ray| {
        let values = ray.async_stats();
        let prepared = PREPARED
            .lock()
            .map_err(|_| "Prepared registry lock failed")?
            .as_ref()
            .map_or(0, |p| p.len() as u64);
        let mut out = [0u64; 14];
        out[0] = PREPARE_COUNT.load(Ordering::Relaxed);
        out[1] = PREPARE_NANOS.load(Ordering::Relaxed);
        out[2..10].copy_from_slice(&values[..8]);
        out[10] = prepared;
        out[11..13].copy_from_slice(&rt::idle_stats());
        out[13] = values[8];
        std::ptr::copy_nonoverlapping(out.as_ptr(), output, 14);
        Ok(0)
    })
}

#[cfg(test)]
mod surface_actor_contract_tests {
    #[test]
    fn optional_actor_abi_preserves_versions_and_rejects_invalid_handles() {
        assert_eq!(crate::greenmarine_surface_rt_actors_api_version(), 1);
        assert_eq!(crate::greenmarine_surface_rt_api_version(), 1);
        unsafe {
            assert_eq!(
                crate::greenmarine_surface_rt_record_actors(
                    0,
                    std::ptr::null(),
                    1,
                    1,
                    1,
                    2,
                    3,
                    4,
                    5,
                    std::ptr::null(),
                    0
                ),
                -1
            );
        }
    }
    #[test]
    fn actor_payload_is_bounded_finite_affine_and_zero_header_padded() {
        use crate::rt::{validate_surface_actors, ACTOR_FLOATS};
        assert_eq!(ACTOR_FLOATS * 4, 4624);
        let mut data = [0f32; ACTOR_FLOATS];
        assert_eq!(validate_surface_actors(&data).unwrap(), 0);
        data[0] = 1.;
        for axis in 0..4 {
            data[4 + axis * 5] = 1.;
        }
        for axis in 0..3 {
            data[4 + 20 + axis] = 1.;
        }
        assert_eq!(validate_surface_actors(&data).unwrap(), 1);
        for bad in [-1., 17., 0.5, f32::NAN] {
            let mut invalid = data;
            invalid[0] = bad;
            assert!(validate_surface_actors(&invalid).is_err());
        }
        let mut invalid = data;
        invalid[1] = 1.;
        assert!(validate_surface_actors(&invalid).is_err());
        let mut invalid = data;
        invalid[4 + 3] = 1.;
        assert!(validate_surface_actors(&invalid).is_err());
        let mut invalid = data;
        invalid[4] = 0.;
        assert!(validate_surface_actors(&invalid).is_err());
        let mut invalid = data;
        invalid[4 + 20] = 0.;
        assert!(validate_surface_actors(&invalid).is_err());
        let mut invalid = data;
        invalid[4 + 24] = f32::INFINITY;
        assert!(validate_surface_actors(&invalid).is_err());
        assert!(validate_surface_actors(&data[..ACTOR_FLOATS - 1]).is_err());
    }
}

#[cfg(test)]
mod extended_actor_contract_tests {
    #[test]
    fn extended_payload_does_not_change_legacy_bounds_and_padding() {
        use crate::rt::{
            expand_surface_actors, validate_surface_actors, validate_surface_actors_extended,
            ACTOR_FLOATS, EXTENDED_ACTOR_FLOATS,
        };
        assert_eq!(ACTOR_FLOATS * 4, 4624);
        assert_eq!(EXTENDED_ACTOR_FLOATS * 4, 18448);
        let legacy = [0.; ACTOR_FLOATS];
        let expanded = expand_surface_actors(&legacy).unwrap();
        assert_eq!(expanded.len() * 4, 18448);
        assert!(expanded.iter().all(|v| *v == 0.));
        let mut data = [0.; EXTENDED_ACTOR_FLOATS];
        data[0] = 64.;
        for actor in data[4..].chunks_exact_mut(72) {
            for axis in 0..4 {
                actor[axis * 5] = 1.;
            }
            actor[20..23].fill(1.);
        }
        assert_eq!(validate_surface_actors_extended(&data).unwrap(), 64);
        assert!(validate_surface_actors(&data).is_err());
        let mut old = [0.; ACTOR_FLOATS];
        old.copy_from_slice(&data[..ACTOR_FLOATS]);
        assert!(
            validate_surface_actors(&old).is_err(),
            "v1 rejects count64 rather than truncating"
        );
        old[0] = 16.;
        let expanded = expand_surface_actors(&old).unwrap();
        assert_eq!(&expanded[..ACTOR_FLOATS], &old);
        assert!(
            expanded[ACTOR_FLOATS..].iter().all(|v| *v == 0.),
            "old upload initializes all additional shader slots"
        );
        data[0] = 65.;
        assert!(validate_surface_actors_extended(&data).is_err());
        data[0] = 64.;
        data[4 + 63 * 72 + 20] = 0.;
        assert!(
            validate_surface_actors_extended(&data).is_err(),
            "validate the last box bounds"
        );
        data[4 + 63 * 72 + 20] = 1.;
        data[EXTENDED_ACTOR_FLOATS - 1] = f32::NAN;
        assert!(validate_surface_actors_extended(&data).is_err());
    }
    #[test]
    fn extended_symbols_are_optional_additions() {
        assert_eq!(
            crate::greenmarine_surface_rt_actors_extended_api_version(),
            1
        );
        assert_eq!(crate::greenmarine_surface_rt_actors_api_version(), 1);
        unsafe {
            assert_eq!(
                crate::greenmarine_surface_rt_record_actors_extended(
                    0,
                    std::ptr::null(),
                    1,
                    1,
                    1,
                    2,
                    3,
                    4,
                    5,
                    std::ptr::null(),
                    0
                ),
                -1
            );
        }
    }
}
