# GreenMarine shader platform v1

The owner requests continued autonomous implementation, with Vulkan primary,
portable packs using scripting plus shader code, real lightmap authoring,
hardware-dependent advanced packs, user controls and measured optimizations.
FSR4 is explicitly deferred. Lua must expose engine features rather than limit
the renderer to what can be calculated in Lua. Direct SPIR-V is requested where
useful; binary format alone is not a runtime performance claim.

## Architecture

Keep Minecraft 26.3 / Java 25 / Fabric Loader, no required Fabric API or KoperLib.
Retain Fullpack effect declarations. Renderpearl owns GPU submission on Vulkan
and OpenGL; its SPIR-V reflection pipeline can consume a pack's optional
precompiled fragment module on Vulkan, with GLSL as portable fallback.
Never replace/destroy Minecraft-owned handles or create a competing queue.

Mounted resource packs author `assets/<namespace>/greenmarine/packs/*.json`
(schema 1), Lua scripts and GLSL fragment shaders. A pack has an id/name,
optional script, at most one lightmap stage and up to eight ordered scene
passes. Each pass chooses a fragment shader, scale (1, .75, .5 or .25), minimum
quality, optional required capability and fallback fragment. Resource ids must
be namespaced. Reload validates all declarations and source resources before
publishing a new catalog; a failed scan preserves the last valid catalog.
Asset-only mods need resource mounting; standalone mods can instead register
an immutable loaded pack directly, with no Fabric API requirement.

Passes consume scene color/depth, lightmap and a stable `MarineFrame` uniform
block: time/day/quality/RT mode, viewport, inverse camera matrices and 32 pack
parameters. Lua runs once per game tick, not per pixel/frame. Minimal LuaJ is
bundled, with a bounded instruction budget and no filesystem/process/Java
reflection libraries. The `marine` table exposes named parameter setters,
owned world-light handles and engine math helpers; frame data includes camera,
time/day and actual capabilities. Shader calculations stay on the GPU. Script
errors disable that script and remove its lights without crashing Minecraft.
Parameters are validated, and pack switching/world change releases ownership.

The lightmap stage transforms vanilla's 16x16 lightmap into an owned texture
after vanilla updates it, and world rendering samples that result. It never
repeatedly grades its own previous output. With effects disabled or a failed
stage, the vanilla view is returned. No global replacement of vanilla shaders.

F6 opens pack/quality/ray-lighting controls, F7 places a light, F8 toggles.
Settings persist. Portable sample: warm lightmap, exposure/saturation grading,
scripted uniforms. Advanced sample: additional reduced-resolution bloom and
depth-aware effects with explicit fallback, plus voxel ray shadows where
enabled. Java API allows mod-owned pass/pack registration and light control.

## Capabilities and ray lighting

Never label an available GPU feature as an enabled/implemented renderer path.
Minecraft currently controls device creation; hardware RT requires both its
enabled extensions/features and an implemented compatible native pipeline.
Unsupported hardware RT requests fall back with a visible reason. OFF/AUTO/
VOXEL/HARDWARE controls are explicit. A bounded local voxel occupancy volume
provides software ray shadows on either backend; this is not hardware RT or
path tracing. Full blocks are the first occlusion representation; transparent
and partial geometry remain documented limits. Rasterization still draws the
scene. A native hardware pipeline and FSR4 are separate future work, not flags
pretending they already work.

## Optimization and acceptance

Cache immutable light snapshots until mutation. Cull/rank sources before the
16-source upload so distant entries cannot starve nearby lights; reuse frame
matrices/buffers. Skip no-op lighting and disabled bloom taps. Reuse render
targets and ping-pong scene passes, copying once at the end; reduced-resolution
stages deliberately trade fidelity for work. Cache compiled pipelines across
frames, rebuild on reload. Voxel acquisition is incremental with a tick time
budget and uploads only completed changed snapshots. No synchronous GPU readback
or queue wait in normal rendering. Collect nonblocking GPU timestamps and CPU
submission/script timings, not just FPS, for the old and new pass budgets.

Acceptance: meaningful tests for malformed packs, deterministic fallbacks,
Lua errors/loops and ownership, lightmap lifecycle, ranked lights, voxel wall
occlusion, SPIR-V structure/stage and render budgets. Build both variants.
Live Vulkan acceptance first, then OpenGL and native-disabled fallback; verify
pack selection, lightmap changes, ordered effects, resize/reload/world teardown,
failure fallback and script execution. Archive paired images and timing data.
Document measured limits without claiming support on every GPU/OS or universal
speedups. This is a coherent platform prototype for further game testing, not
completion of the original FSR/OptiFine/native renderer vision.

## Execution authorization

Existing broad approval and the owner's explicit instruction to keep writing
autonomously supersede repeated spec/plan approval gates. Implement inline in
this untracked project directory; no sibling edits, staging, commits, worktree
or branch changes. The owner is concurrently working on KoperLib.

Primary references (architecture inspiration, no copied implementation):
[Radiance/MCVR](https://github.com/Minecraft-Radiance/MCVR),
[Veil post-processing](https://github.com/FoundryMC/Veil/wiki/PostProcessing),
[LuaJ](https://github.com/luaj/luaj).
