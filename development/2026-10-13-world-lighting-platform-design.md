# Postprocess completion and voxel material lighting

The owner asks to finish the ReShade-like surface layer and start deeper
renderer work. Existing authorization covers autonomous design/implementation;
Vulkan stays primary, OpenGL portable, Java 25/MC 26.3/Loader only required.
No sibling sources, branches, index or installed instance changes.

## Selected approach

Finish the existing pass chain with per-pack parameter editing/persistence and
optional RGBA16F intermediate targets, then expose actual voxel material data
to pack shaders. This builds on the host command encoder and owned resources.
Alternatives: replacing vanilla chunk shading immediately would couple this
stage to every material/pipeline; hardware RT first needs device feature and
pipeline work. Those remain separate architectural stages. The selected stage
ships real world-derived emissive ray lighting without claiming a complete
native renderer or path tracer.

## Surface layer contract

ShaderSettings preserves its four-argument constructor and adds immutable
per-pack parameter overrides (64 packs, 32 values each, finite). Load failures
keep the previous settings. Known parameters validate against pack ranges;
stale parameter names are ignored when resolving a pack. F6 opens paged pack
parameter sliders with live preview, saves on release/close, and resets a
pack's overrides. An explicit override wins after Lua updates; clearing one
restores authored values. Editing parameters does not rebuild GPU pipelines.

PackPass preserves its eight-argument constructor and adds `format`: `scene`
(default) or `rgba16f`. Targets/pipelines use the actual output format. The last
pass copies only when dimensions AND format match; otherwise a draw converts
or scales. Unsupported float target allocation falls back to the scene format
with a visible log; this trades HDR range for compatibility. Pack authors own
tonemapping; this cannot recover HDR from vanilla's already clamped scene.

## World data and first lighting consumer

Keep the 32-cubed occupancy bitset. Add per-cell RGB map-color albedo and
0..15 block emission. Occupancy and materials publish atomically and only
complete changed snapshots upload. Materials use a 256x128 RGBA8 texture atlas,
requiring neither SSBO nor compute support. Missing chunks remain empty; only
loaded client chunks are read under the existing scan budget. Partial geometry
still uses full-cell emission and full-opaque-block occlusion approximations.

Scene shaders receive VoxelFrame (existing 4128-byte block) and VoxelMaterials.
A shared GLSL include exposes safe bounds, cell material lookup and bounded DDA
first-hit rays. Bind only for scene stages, upload before render-pass encoding,
and expose readiness explicitly; fallback data remains initialized. Voxel
scanning follows effective Voxel mode. Off/below-quality skips expensive rays.

A new Radiance sample traces a fixed budget of hemisphere rays from depth-
reconstructed world surfaces into this material volume. First-hit emissive
blocks supply diffuse incident radiance even when outside the visible image.
Opaque cells occlude it. Store the reduced-resolution result in RGBA16F and
compose with the full-resolution original scene with depth-edge rejection.
This is single-segment voxel emitter lighting, not multi-bounce GI, hardware
RT, sun lighting or screen-space color borrowing. The rasterized scene remains
Minecraft's. FSR4 stays deferred.

## Acceptance

Tests: settings immutability/ranges/rollback; Lua override precedence/restoration;
format parsing/validation/schedule; material snapshot isolation/channel layout;
incremental atomic publication. Runtime: actual parameter control edits,
HDR value >1 survives intermediate target then scales back, shared world data
compiles/submits on Vulkan/OpenGL, offscreen emitter illuminates a floor and
an intervening wall removes it. Record CPU/GPU/scan costs and screenshots;
normal pack reload/resize/failure fallback remains valid. Build and inspect
both distributable variants. One independent final read-only review with a
single fix pass, plus precise documentation of implemented limits.
