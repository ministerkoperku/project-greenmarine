# Surface RT native contract and verification

## Current async integration, 2026-10-15

The synchronous ownership description below records the original surface ABI.
The current optional async ABI1 adds immutable shared scene generations, worker
preparation tokens and four descriptor/uniform/output flight slots. Host fences
retain each scene and borrowed image view until actual GPU completion. Steady
publication/recording does not wait for device idle; synchronous compatibility,
reset and teardown retain their cleanup behavior. Read
`docs/DYNAMIC-RAY-SCENES.md` and `development/evidence/dynamic-rays/README.md` for
the current host integration, measured lifecycle proof and memory limits.
The tests now include a separate real GPU fixture for distinct in-flight scenes,
bounded Busy and FIFO reclamation. The following sections preserve the original
surface ABI and its historical acceptance rather than superseding that extension.

Surface RT is an optional ABI v1 extension. Core ABI v1, legacy RT ABI v1,
MarineFrame288, triangle metadata96 and RayCamera frame320 remain unchanged.
Initialization requires an already initialized parent RT pipeline. The surface
child owns its compute pipeline, descriptors, sampler, uniform and packed output
buffer; the parent remains the sole owner of model vertices/metadata and BLAS/TLAS.
Scene replacement/reset waits for device idle and invalidates child descriptor
bindings before the next dispatch, including when Vulkan reuses handle values.
Context teardown waits for idle and drops the child before the shared scene.
Borrowed Vulkan device, command, image and image-view handles are never destroyed.

## C and Java calls

- `greenmarine_surface_rt_api_version() -> u32` returns 1.
- `greenmarine_surface_rt_init(u32 enabled) -> i32`; enabled must be 1.
- `greenmarine_surface_rt_record(u64 command, const float *frame320, u32 width,
  u32 height, u64 lightingImage, u64 reflectionImage, u64 atlasView,
  u64 surfaceNormalView, u64 surfaceAlbedoView) -> i32`.

Java exposes `surfaceAvailable()`, `surfaceError()`, `initializeSurface(boolean)`
and `recordSurface(long, ByteBuffer, int, int, long, long, long, long, long)`.
Missing surface symbols affect only surface availability; legacy libraries remain
usable. Status codes preserve the existing convention: 0 success, -1 invalid
raw handles/pointers, -2 no attached host or optional symbols, -3 Java/native
failure, -20 disabled, -21 rejected native state/contract or Vulkan operation.

The frame must contain exactly 80 finite floats; frame indices 68/69 are the
output dimensions. Capture dimensions may differ: sampling uses normalized UV.
Call outside a render pass on the attached device's ordered render queue. Record
once per command buffer; submit it before replacing/resetting scene resources or
changing the views/output size for another surface recording. Those changes wait
for submitted work, which cannot protect resources referenced by an unsubmitted
command buffer. Capture images and output images must remain live through GPU
completion. The caller supplies required image layout transitions and ownership.
All images/views are single-layer, mip-zero objects in `GENERAL`. Capture views
and atlas support sampled access; outputs are distinct RGBA16F images supporting
`TRANSFER_DST`. Native barriers order earlier capture writes and AS builds before
compute, uniform transfer before shader reads, compute output before image copies,
and copies before later host sampling. No queue-family transfer is performed.

## Bindings and output semantics

Descriptor bindings 0/1 borrow parent TLAS and triangle metadata; 2 is the owned
packed output storage buffer; 3 is frame320; 4 is the borrowed atlas; 5 is captured
RGBA32F world normal/device depth; 6 is captured RGBA16F linear albedo/material.
Material alpha encodes integer class plus roughness times 0.5, with class 4 water.
The shader reconstructs the captured surfel through the paired RayCamera inverses
so displaced terrain/water supplies origins and normals. It does not first trace
an undisplaced terrain primary ray. Packed RGBA16F outputs have a 16-byte-aligned
reflection offset; odd dimensions remain safe. Both shaders use 8x8 groups with
bounds guards, and the native pixel budget remains 4,194,304 pixels.

Lighting RGB is irradiance: nonnegative ambient `Options.x`, direct sunlight
`max(dot(normal, normalize(Sun.xyz)),0) * max(Sun.w,0) * visibility`, plus four
traced cosine diffuse samples scaled by `Options.y * 0.25` when GI is enabled.
Lighting alpha is sun visibility: 1 clear, 0 shadow/night/backface/no surfel.
Captured normal/depth independently supplies surfel validity. Reflection RGB is
linear atlas color with biome tint times hit ambient/direct/emission; alpha is
1 for a geometry hit, 0 for a miss. Reflection is traced for water or roughness
below 0.95. Roughness currently selects participation, not a filtered GGX lobe.
Biome tint uses Minecraft 26.3 BlockTintSource and the existing metadata RGB.

Finite scene geometry is unchanged. Sun/reflection rays have a 96-block reach;
diffuse bounces have 12-block reach. Four secondary samples provide one traced
bounce with secondary sunlight visibility, not full multi-bounce transport.
Reflection misses remain zero for pack sky fallback. No native scene-edge fade,
entity scene, translucent transport, reflection denoising or jittered TAA is added.
The parent/pack applies scene coverage policy and controls advertised readiness.

## Fresh offline checks (2026-10-14)

The three new Rust contract tests were observed failing before implementation
(ABI version/availability, seven-binding descriptor contract, and packed odd-size
outputs). Final `cargo test --manifest-path engine/Cargo.toml` passes all 7 tests;
`cargo build --release --manifest-path engine/Cargo.toml` passes. `nm -D` confirms
all three new exports alongside legacy exports. Surface shader compilation with
`glslangValidator -V --target-env vulkan1.2` and validation with `spirv-val
--target-env vulkan1.2` pass. Both sources and compiled SPIR-V are present.

The standalone Java25 `engine/tests/NativeRayTracerSmoke.java` compiles with
NativeRayTracer and TriangleScene, and passes 8 real-FFM checks against the fresh
release library. These include a lookup that hides surface symbols while retaining
legacy availability, Off, missing-host initialization/dispatch and frame288
rejection. Run with `--enable-native-access=ALL-UNNAMED`, passing the release .so
path. This uses no live Minecraft client or Gradle build.

The offline checks above do not establish host surface visibility or visual
equivalence. Additional real GPU evidence follows; Minecraft Vulkan/OpenGL
acceptance and full Java/scanner compilation belong to parent integration.

## Headless real GPU acceptance (2026-10-14)

`engine/src/surface_gpu_tests.rs` creates an isolated Vulkan device on the actual
RX 9070 XT, with enabled ray-query, acceleration-structure and buffer-address
features. It never attaches to or mutates Minecraft's device. The explicit test
is ignored by ordinary cargo test runs, which remain portable. Run:

```sh
CARGO_TARGET_DIR=/tmp/greenmarine-surface-gpu-target cargo test --manifest-path engine/Cargo.toml -- --include-ignored --nocapture
```

Fresh run: all 8 tests passed, including the GPU acceptance test. Driver is
RADV, Mesa 26.2.2-arch3.2, device AMD Radeon RX 9070 XT (RADV GFX1201). Raw output
is in `development/evidence/surface-native/headless-gpu.log`; structured pixel
values are in `headless-gpu.json` beside it.

The synthetic surfel at the output center uses world normal
`(-1/sqrt(2),0,-1/sqrt(2))` and device depth 0.5. Projection/view and their
inverses are identity, so the finite synthetic camera frustum has x in [-1,1].
All reflected target vertices are at x=-3, outside that frustum. The target is
absent from the camera primary ray, but the captured surfel's reflected eye ray
hits it. Expected reflected color uses the UNORM atlas RGB (204,51,26)/255,
triangle tint (0.5,1,0.25), ambient 0.2 and emission 4 times 0.45. Real center
reflection is `(0.26635742,0.05795288,0.0005722046,1)` and matches the independent
linear-color calculation within 0.002/channel. Moving geometry to x=+3 gives
reflection `(0,0,0,0)`.

The native dispatcher writes all 35 pixels of both odd-sized 7x5 outputs;
readback rejects unchanged sentinel values and non-finite components. Changing
only the captured normal to (0,0,-1) produces a reflection miss, proving capture
normal participation. Changing only captured depth to 1 produces 35 zero pixels
per output. A separate triangle intercepting the sun ray lowers center lighting
from `(0.69970703,0.69970703,0.69970703,1)` to
`(0.19995117,0.19995117,0.19995117,0)` while retaining the reflection hit. Scene
reset rejects a dispatch without geometry; recreation restores the same hit.

A larger offscreen triangle at x=-1.5 exercises diffuse transport with sunlight
Off. Keeping geometry fixed and raising its metadata emission from 0 to 15 raises
center irradiance from `(0.2310791,0.22094727,0.2244873,0)` to
`(0.90527344,0.36767578,0.22583008,0)`. GI Off removes that contribution and
returns `(0.19995117,0.19995117,0.19995117,0)`.

As a negative control, temporarily bypassing the test fixture's native dispatch
failed the reflection-hit assertion with sentinel alpha -3 instead of 1. The
fixture was restored and all 8 tests passed freshly; the deliberate failed run is
preserved in `headless-negative-control.log`. Production native code/shaders,
release binaries and bundled resources were unchanged during this GPU exercise;
only a cfg(test) module inclusion was added to rt.rs.

This establishes actual ray-query output, sampled capture participation,
reflection/GI transport, odd-size copies and basic scene lifetime on this device.
It uses queue-wait-idle readback and synthetic identity matrices, so it does not
establish Minecraft borrowed-handle lifecycle, bob/hurt projection correctness,
resize/reload behavior, asynchronous frame pacing, performance or visual match.
No Khronos validation layer is installed; pixel acceptance is not formal
synchronization validation. Those host cases remain parent runtime acceptance.
