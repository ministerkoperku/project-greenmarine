# World ray tracing and shaderpack readiness

Owner: finish the world-facing renderer work, supply an example shaderpack,
perform real ray tracing and explicitly report whether the pack loaded. Prior
autonomous implementation/testing authorization remains in force. Continue 0.4;
preserve public APIs, schema-v1 packs, optional Fullpack and standalone Loader.
Vulkan is primary, OpenGL has an explicitly labelled portable fallback. FSR4 is
deferred. No branches, commits, installed jars or sibling edits are authorized.

## Chosen architecture

Implement Vulkan hardware Ray Query over BLAS/TLAS built from loaded Minecraft
baked block-model triangles. Java collects positions, UVs and material metadata
incrementally; Rust owns native buffers, acceleration structures and a compute
pipeline through the versioned Panama FFM boundary. The pipeline samples the
borrowed Minecraft block atlas, tests alpha cutouts, traces primary surfaces,
sun visibility and a secondary diffuse/emissive ray, and exports albedo,
normal/depth and HDR lighting attachments for pack authors.

Add an optional host FeatureSet during Vulkan device creation. Require actual
enabled accelerationStructure, rayQuery and bufferDeviceAddress plus extension
dependencies. GPUs lacking the complete set continue without hardware RT. The
public capability becomes true only after native pipeline initialization; active
status additionally requires a built scene and submitted trace. GPU support alone
never means active RT. Use the actual Minecraft device and command encoder;
no independent device/queue, JNI or host-handle destruction.

A separate Vulkan ray-query compute path is necessary because Renderpearl's
public raster binding types do not expose acceleration structures. Record native
commands into command buffers supplied by the host encoder, apply explicit
barriers, and copy outputs into host-managed GreenMarine-owned resources.
Composition remains shared Renderpearl GLSL for Vulkan and OpenGL. Device idle
is permitted only at lifecycle/scene replacement/resize boundaries, never per
steady-state frame. Buffer updates must not overwrite data still read by the GPU.
Native allocation failures clean partial state. Rust returns explicit error
codes; Java disables only RT and reports the fallback/failure reason.

## World integration and limits

Scene acquisition uses loaded chunks only, stable block-model random seeds,
actual baked model positions/UVs and neighbour face culling. Relative coordinates
avoid float cancellation. Publish complete immutable snapshots, cap memory and
skip rebuilds for unchanged content. Start with a bounded local volume, track
its origin and rebuild after movement/world edits/resource reload. Model geometry
captures slabs/stairs and cutout textures rather than full-cell occupancy. Water,
entities and special block-entity renderers remain vanilla in composition; do not
claim those are in the RT scene or claim Iris pack-format compatibility. Preserve
vanilla color/depth where the ray scene is unavailable or mismatches raster depth.

Expose initialized world-ray attachments and readiness bindings to scene pack
programs and public frame contexts. Lightmaps stay separate. Existing voxel
contracts stay unchanged. Native outputs describe a real world G-buffer and
lighting for the captured models; the example pack demonstrates them end to end.

## Pack and reporting

Supply an opt-in example `greenmarine:aurora_rt`, builtin and a resource-pack ZIP.
Use schema-v1 passes with explicit HARDWARE_RT requirement and portable GLSL
fallback; no breaking pack-format change. Parameters adjust exposure, sun/GI
strength and diagnostics. Settings are not silently changed. Live status states
Disabled, Loading, Loaded, Fallback and Failed; include pack name, effective ray
path, readiness and useful failure cause. Loaded for packs with passes requires shader/pipeline success
and actual render submission, not script parsing. Existing valid script-only
packs retain Loaded for a healthy executed script, with an explicit no-render-passes
detail; no GPU submission or RT activity is claimed. Chat/toast notification once per
transition and a persistent F6 status make success/failure visible. Pack/script/
GPU/reload errors must not leave a stale Loaded message. Reload retains last
valid catalog assets and describes that situation explicitly.

## Verification

Test status transitions and stale-failure handling, feature gate predicates,
triangle ABI/UV extraction/caps/relative coordinates, native error paths and
pack snapshots before implementation. Validate compute SPIR-V offline. Probe
actual Vulkan feature enablement, native BLAS/TLAS build and ray-query dispatch,
textured partial-shape occlusion and offscreen emission, resize/reload, ray mode
Off, shader failure reporting and missing-native/OpenGL fallbacks. Collect CPU
acquisition/submission and asynchronous GPU timings separately. Preserve the
owner's settings and dev-world mode with backup/abort cleanup. Finish base and
Fullpack builds, Java/Python/Rust tests and Rust release build. Document exact
implemented limits and evidence; no Veil/Iris parity claim follows from test count.

## Owner movement bug follow-up (2026-10-14)

Confirmed translated projection changed the effective perspective eye while the
primary ray still started at the physical camera. Correct inverse projection and
view translation together within the existing internal frame ABI. Store/filter
irradiance separately from atlas albedo; avoid dark-texel ratios and per-channel
highlight saturation. Anchor ray patterns to world coordinates and use vanilla
sun angle/weather. Coverage is now 96x64x96 on a stable 16-block grid, bounded by a
one-million triangle budget. Empty-section skipping keeps acquisition incremental.
The composite uses shared global lighting and an eight-block edge fade so finite
RT coverage does not create a hard moving patch. Alpha silhouettes remain limited.
