# GreenMarine session handoff

Updated 2026-10-15 for 0.7.0-prototype. This file preserves the
owner's next direction across sessions. Read the current source before treating
any historical report as a new bug.

## Owner direction and authorization

Continue toward a substantial public rendering and shader API for mods and
shaderpacks, inspired by Veil's rendering capabilities and Iris's shaderpack
pipeline. The owner wants more than additional full-screen effects: reusable
access to renderer capabilities and a deeper material/geometry pipeline.
These references express a design direction, not implemented compatibility
with either project's API, pack formats or mod ecosystem.

Vulkan is the primary backend; OpenGL uses shared Minecraft Renderpearl paths
and honest fallbacks. Keep the base mod standalone: Fabric Loader is required,
Fabric API and KoperLib are optional. Preserve optional Fullpack integration,
public pack contracts, Lua extensions and GLSL fallbacks for SPIR-V. Optimize
with separate CPU/GPU measurements. FSR4 is explicitly deferred. Hardware RT
must remain unavailable until an actual pipeline and enabled host features exist.

The owner has authorized autonomous implementation and testing. Do not stop at
a plan or repeat approval questions for routine reversible work. Preserve user
changes, sibling projects and installed jars. Do not infer authorization to
commit, publish, change branches or replace installed artifacts.

## 0.7 Solstice surface rendering and dynamic scene updates

Continue this source, not the older 0.3 or 0.6 checkpoint. Read
`docs/SOLSTICE-RT.md`, `docs/SURFACE-RENDERING.md`, `docs/DYNAMIC-RAY-SCENES.md`,
`development/evidence/solstice/README.md` and
`development/evidence/dynamic-rays/README.md` for current implementation and proof.

Solstice RT is a new original builtin and editable resource pack inspired by the
owner's local Eclipse Unstable visual direction, without copying its code/assets.
It changes actual water vertices, refraction/absorption/Fresnel, terrain lighting,
sky, moon/stars, procedural cloud layers, fog, wetness/puddles, weather accents,
bloom and tone mapping. Full-resolution raster normal/depth and albedo/material
capture shares displacement/alpha with visible terrain. Native Vulkan Ray Query
uses these origins for sun shadows, diffuse GI and offscreen model reflections.
Water reflects model geometry but is not secondary native fluid geometry.
Surface-only packs avoid the old primary-camera trace. OpenGL retains raster
water/atmosphere and reports Fallback for unavailable hardware RT.

Public additions are opt-in surface/surfaceOnly programs, environment128,
history160 with bounded reprojection, per-pass history declarations and surface
frame imports/readiness. Old constructors, schema1, MarineFrame288, native core
ABI1, existing pack IDs and optional standalone/Fullpack boundaries remain.
No jittered TAA, PBR texture maps or automatic exposure is implemented.

The owner's walking-shadow delay was reproduced: the full scanner/tick scheduler
took 4.96–5.24 seconds from edits to GPU sunlight. Render-frame section caching,
real block/chunk/tint invalidation, worker scene preparation and fence-retained
native generations reduce this to 9.88–14.18 ms in the controlled fixture.
2509 walking GPU samples preserve shadow/control; coverage catches up in 8.69 ms.
Steady callback p99 is 7.97 ms, maximum 17.93 ms, with zero additional native
device-idle calls. These local instrumented results are not whole-game guarantees;
all-frame maximum includes startup and an intentional submission-failure reset.

Cache retention is bounded to 216 sections / 192 MB raw mesh data, with transient
snapshot/staging allocations additional. Native async ABI1 has four flight slots,
four retired scenes, two preparation tokens and a 512 MiB logical scene budget.
Host fences protect owned and borrowed resources; resize/reset drains before
replacement. A real post-submit exception fixture verifies Fallback and recovery.
Older native libraries retain synchronous ABI behavior. See evidence for scopes.

RT remains a finite 96x64x96 loaded baked-model scene, not the entire render
distance. Entities/fluids are not secondary ray targets; dense scenes retain the
million-triangle cap. This is not full Iris/Veil compatibility or a verified
Eclipse image match. FSR4 stays deferred. Continue material/scene coverage and
real-world visual testing rather than describing the entire renderer as finished.

Local artifacts are the base/alternative Fullpack 0.7 jars and four example ZIPs
in `build/`; Solstice is also builtin in F6. Artifact hashes and final checks live
with the Solstice evidence. Final base/Fullpack builds, 141 Java tests, 36 Python
tests, 10 portable Rust tests plus two explicitly executed GPU tests, 22 real FFM
checks, clippy/fmt, release native and both SPIR-V validations pass. Final Vulkan
Solstice/OIT, OpenGL/classic and legacy Aurora probes pass independently. All
probe clients exited. The final read-only process audit observed an independent
owner GreenMarine Vulkan client starting at 22:09 local time after the builds;
leave it and the owner's Mechanics Dream client untouched. Current captured owner settings are Solstice RT,
Ultra, Hardware, profiling Off, parameters empty; preserve the latest settings
rather than older Aurora/Seabreeze/Sunlite notes. Probes restore pose/survival and
their client-only edits. No installed jars, commits, branches or siblings changed.

## Historical 0.6 world programs and Prismarine owner report

Read `docs/WORLD-PROGRAMS.md`, `development/evidence/world-programs/README.md`
and `development/2026-10-14-world-programs-{design,plan}.md`. Continue this source;
do not restart 0.3. Schema1 now optionally declares `PackProgram` targets with
explicit vertex/fragment snapshots; old ShaderPack constructor remains. Public
WorldPipeline preserves host state and flattened uniform slots; ShaderProgram
supports same-id stage sources. Base/Fullpack, MarineFrame288 and native ABI1 remain.

Actual world pipeline replacements provide terrain/block/entity/sky/cloud/weather/
particle routing, initialized frame/surface128/ray/voxel bindings and independently
copied opaque color/depth. Classic transparency is deferred to a new pass after
opaque copy; OIT copies outside its opaque pass. World scope ends before HUD/hand.
Native RT now dispatches before world rasterization from the submitted projection.
Failed compilation restores host shaders and defers chat notifications safely.

Builtin Seabreeze changes terrain, actual water vertices/materials, refraction,
absorption, Fresnel sky/sun reflections, sky and clouds. Its atlas-corner recognition
and interpolated vertex displacement were verified on GPU, not inferred from a
fragment effect. It has no traced water reflections/caustics; other target interfaces
are routing support, not independently accepted examples for every variant. Native
RT remains finite loaded baked block-model geometry, no fluids/entities/PBR/biome
RT tint/history. Night/weather ambient and non-emitter sky GI are no longer fixed
at day brightness. Full Iris/Veil compatibility/parity is not claimed; FSR4 deferred.

Original Prismarine 0.4 adds irradiance as white radiance/divides by albedo and
creates camera-orbiting Lua lights. Same-pose Vulkan captures reproduce white trees
and correct them in an independent `prismarine:showcase_fixed` example using
`prismarine_fixed` resources. Owner ZIP unchanged. Hardware intermediates carry
lighting, preserving full-resolution scene detail; portable voxel GI/bloom retained.

Artifacts: base/alternative Fullpack 0.6 jars and Seabreeze/Prismarine Corrected/
Aurora example ZIPs in build. 102 Java, 17 Python, 4 Rust tests, base/Fullpack,
release native and SPIR-V checks pass. Accepted GPU evidence: `vulkan-stable`,
`opengl-stable-accepted`, `aurora-regression`; see evidence README for scopes, timings,
rejected first smoke/crash cleanup and independent review fixes. Latest actual
config is Aurora/Ultra/Hardware, profiling Off, parameters empty; earlier 0.5
Prismarine config text is historical. Survival/position restored; no clients remain.
No installed jars, commits, sibling changes or owner archive modifications.

## 0.5 hardware rays and owner movement bug follow-up delivered

Read `docs/HARDWARE-RT.md`, `development/evidence/hardware-rt/README.md` and
`development/2026-10-13-hardware-rt-{design,plan}.md`. Source continues 0.4,
not 0.3. Actual Vulkan enabled features/native BLAS/TLAS compute Ray Query now
trace loaded baked model triangles, atlas UV/alpha/emission, sun visibility and
four secondary diffuse rays. GPU support alone is insufficient. Ready world
irradiance/normal-depth/albedo are scene pack bindings and additive public graph
imports; MarineFrame288, schema1, core native ABI1 and old constructors remain.

Owner reported white lighting, camera bob/hurt mismatch and a moving chunk-local
patch. Confirmed/fixed: effective perspective eye from translated projection,
independent irradiance filtering instead of radiance/albedo ratios, world-anchored
sampling, vanilla sun/weather direction, 96x64x96 stable grid coverage and 8-block
fade into a shared global baseline. Sky derivatives are evaluated before divergent
returns. Alpha foliage edges still mismatch/shimmer; finite sun 32 / GI 12 ray reach,
biome tint/PBR, entities/fluids/special rendering and full Iris parity remain limits.

F6/chat report actual Loading/Loaded/Fallback/Failed with failure causes. Empty
scans clear old scene/native ownership and readiness; status updates immediately
on scene/path changes and on renders even while paused. Script-only packs retain
healthy Loaded with an explicit no-render-passes detail. Pack-aware selection
preserves voxel/portable packs when hardware capability becomes available.

Builtin Aurora Hardware RT and independent `aurora:rt_example` resource pack:
`build/shaderpacks/aurora-rt-example-0.5.0-prototype.zip`. Build via
`./gradlew exampleShaderpack`; editable source in `examples/aurora-rt`.
Jars: `build/libs/greenmarine-0.5.0-prototype.jar` and alternative Fullpack variant.
Checksums/native bytes/source hashes are in hardware-rt evidence. No installed
jars, commits or sibling edits. Latest owner config is Prismarine/Ultra/Hardware,
profiling Off; older Radiance/Sunlite handoff selections are historical. Preserve
that current config. Probes restore mode/position and no clients remain running.

Fresh 95 Java / 15 Python / 4 Rust tests, base/Fullpack builds, clippy, release native
and SPIR-V validation pass. Final actual native acceptance: `vulkan-verified`,
including 20.4-block movement/bobbing masks, reload, 625x469 odd output, empty scene,
Off and failed-GPU-shader/recovery. Final sample 0.559ms GPU / 0.172ms CPU for the RT
stage only; CPU variance is high, no speedup/FPS claim. GL/native-disabled fallback
and new public unavailable graph imports also pass. Rejected runs are retained
and identified in README; never treat their filenames as acceptance.

One review found two applicable Important bugs now fixed and a script-only
contract interpretation addressed by explicit readiness text; biome tint remains
a documented Minor limit. No accepted controlled partial-shape or paired blocked
emitter fixture exists yet. Next work should address those material/alpha/native
scene integrations and temporal filtering, not claim the whole renderer finished.
FSR4 stays deferred.

## 0.4 continuation delivered

The additive public renderer layer is implemented in `api.render` and documented
in `docs/RENDERING-API.md`. Read `development/2026-10-13-render-api-design.md`,
the matching plan and `development/evidence/render-api/README.md` for scope,
review fixes, exact artifacts and separate CPU/GPU measurements.

`MarineRenderer` supplies BEFORE_LIGHTING/BEFORE_PACKS/AFTER_PACKS leases.
Factories receive owned `RenderSession`s; registrations survive world/resource
reset, sessions do not. GPU access/reset is render-thread-bound; lease closure
can be requested off-thread and cleanup is deferred. Program snapshots support
GLSL/includes and optional Vulkan fragment SPIR-V, including a `fromPack` bridge.
`RenderGraph` compiles single-writer dependencies, checks physical import aliases
and executes through Renderpearl. Graph textures are reused, fixed-size targets
survive resize, and failed replacement cleans partial allocations. Material
bindings, attachments and non-indexed triangle meshes are checked before draws.

An opt-in `GeometryExample` draws a mesh G-buffer (albedo, normals and depth)
and composes it against vanilla depth. It is a working public API consumer, not
a vanilla G-buffer or terrain replacement. Java graph contributions require
explicit bindings; schema-v1 pack manifests, MarineFrame and Lua are unchanged.
No history resources, automatic cross-contribution graph merging, indexed helper,
instanced helper, compute helper or PBR texture layer is claimed.

Fresh base/Fullpack builds and 83 Java / 13 Python / two Rust tests pass.
Final Vulkan/OpenGL probes pass mesh/depth captures, resize/reload, shader
failure isolation, registration cleanup and wrong-thread reset rejection.
Vulkan really loads Portable's fragment SPIR-V through the new API; OpenGL uses
GLSL. The existing Radiance pack continues during shader failure injection.
Artifacts: `build/libs/greenmarine-0.4.0-prototype.jar` and the alternative
`build/libs/greenmarine-fullpack-0.4.0-prototype.jar`; hashes in render-api evidence.

Three Important review findings and one smaller example leak are closed.
Rejected initial runtime runs are retained separately and are not acceptance
proof. No commits, installed jars, branches or siblings were changed. This run
observed and preserved Radiance/Ultra/Voxel with profiling Off; the earlier
Sunlite selection note below is historical and must not override newer settings.
No clients were left running. Next work should build on 0.4, not restart 0.3.

## Read first

1. `../AGENTS.md` and this project's `AGENTS.md`.
2. `development/PROGRESS.md` for implemented behavior and remaining limits.
3. `docs/SHADERPACKS.md` for current public authoring contracts, and
   `docs/README.md` for installation and controls.
4. `development/2026-10-13-world-lighting-platform-design.md` and the matching
   `-plan.md` for the completed 0.3 design, decisions and verification ledger.
5. `development/evidence/world-materials/README.md` and `artifacts.json` for
   accepted runtime evidence and exact artifact hashes.

## Historical implementation at 0.4 (superseded by 0.5 above)

Minecraft 26.3, Java 25, Fabric Loader 0.19.5, Gradle wrapper 9.7 and Loom
1.18.2. Check build declarations before changing these. The base has no Fabric
API or KoperLib dependency. Fullpack adapters live in `src/fullpack`, using
compile-only Core/Fullpack 0.1.1-alpha dependencies.

The preserved pack/effect surface consists of `GreenMarine.EFFECTS`, `LIGHTS` and
`PACKS`, light handles, pack definitions/parameters/passes, Java pack
registration leases, capability and quality selection, and trusted Lua
extension registration. Pack schema v1 supports ordered parameters, a lightmap
stage, ordered scene passes, scaled targets, optional RGBA16F intermediates,
includes and optional fragment SPIR-V. The MarineFrame std140 ABI is 288 bytes.
Keep existing constructors and parameter ordering compatible.

`PackCatalog` snapshots shader sources/includes/binaries transactionally and
keeps the last valid pack on failed reload. Java registration survives resource
reload. Asset-only third-party mods are not automatically mounted by Loader;
they need a resource loader or direct Java registration. Lua runs once per
unpaused tick with owned lights, constrained libraries and explicit execution,
call, string, table and retained-value budgets. Trusted Java extension work is
outside those budgets. Parameter previews/persistence preserve scripts and
pipelines, work without an active script, and prune retired names safely.

`PackRenderer` caches compiled pipelines and reuses targets. Final copies
require matching dimensions and actual formats; otherwise it draws a
conversion/upscale pass. All scene shaders receive initialized VoxelFrame and
material bindings, including ordinary and fallback shaders with rays Off.
Unavailable voxel data reports readiness false. Lightmap shaders have no world
bindings. First-use compilation can still hitch.

World lighting uses a loaded-chunk scanner, a 32-cubed voxel volume, full-block
occupancy, map-color albedo and vanilla emission. Scans are incremental and
publish complete snapshots; unchanged data is not uploaded. A reusable
256x128 RGBA8 atlas stores materials. `greenmarine:voxel.glsl` provides bounded
first-hit DDA tracing. The Radiance example uses 4 High/8 Ultra rays per surface,
12-block reach, half-resolution HDR lighting and depth-aware composition.
It receives offscreen emission and opaque blocks obstruct the light.

The new 0.4 layer is an initial public renderer API; a complete low-level
renderer and Iris-style vanilla geometry pipeline remain unfinished. Rasterization remains vanilla. There is no G-buffer, texture/PBR
material representation, multi-bounce transport, temporal denoising, entity or
partial-shape occlusion, hardware RT or native drawing. Added emission can
double-count vanilla block light. Fullpack Lua/KFX forwarding and broader shader
mod coexistence remain unverified; preserve existing KFX providers and IDs.

Rust uses Panama FFM and a versioned C ABI, not JNI. Native processing currently
handles pulse/context inspection. Borrowed Minecraft Vulkan handles remain
Minecraft-owned. The bundled native library is Linux x86_64; other platforms
are unverified and use the Java fallback where applicable.

## Original suggested scope before 0.4 (historical)

Inspect the real Minecraft 26.3 Renderpearl interfaces and current public API,
then design a bounded first release of the deeper API. Suggested priorities:

- Typed GPU resource handles with explicit ownership and lifecycle.
- Shader/program compilation and binding contracts, including GLSL/SPIR-V.
- Named pass inputs/outputs, dependencies and reusable/history resources in a
  render graph rather than only an ordered full-screen pass list.
- Frame context and render-stage hooks usable by independent mods.
- An initial geometry/material or G-buffer integration with a working consumer.
- Capabilities and fallback selection tied to implemented backend behavior.

The 0.4 section above records the implemented subset of these original priorities.
History and vanilla geometry replacement remain future work. Preserve the existing API and packs
while adding the new layer. Deliver a useful end-to-end example with meaningful
verification. Do not restart 0.3 or repeat every accepted probe without a reason.

## Prior 0.3 verification and artifacts

The delivered stage passed 62 Java tests, 11 Python verifier tests, two Rust
tests, the Rust release build, base and optional Fullpack Gradle builds, and
SPIR-V validation. Accepted Vulkan and OpenGL runtime probes include resize,
resource reload, paired open/blocked emitter images, HDR values above 1,
readiness bindings with rays Off and live/persisted parameter editing.

Accepted captures are in `development/evidence/world-materials/` under
`world-materials-delivery-vulkan/` and `world-materials-delivery-opengl/`.
Rejected earlier runs are retained and are not acceptance evidence. Review
found three Important issues; all were fixed in one pass and verified. Details
and local CPU/GPU metrics are in the evidence README. Measurements are local
stage costs on RX 9070 XT/RADV, not whole-frame or universal performance claims.

Delivered jars:

- `build/libs/greenmarine-0.3.0-prototype.jar`
- `build/libs/greenmarine-fullpack-0.3.0-prototype.jar`

Both preserve mod ID `greenmarine`; they are alternative variants. Artifact
hashes and packaging checks are recorded in `artifacts.json`. Probe classes
are excluded from distributable jars. No installed game jars were replaced.

## Verification and runtime environment

Use `./gradlew test build`; also build with `-PfullpackIntegration=true` when
changing integration/public contracts. Use `cargo test` in `engine/` and finish
Rust changes with `cargo build --release`. Rendering acceptance is separate
from compilation. Test-only probes live in `src/probe` and are enabled with
`-PruntimeProbe`; the world fixture additionally uses `-PworldLightingProbe=true`.
Read the completed plan for exact probe commands and fixture behavior.

Locally, set `SDL_VIDEODRIVER=wayland`; an earlier XWayland launch stalled.
Loom asset downloads stalled on 141 optional language JSON files while needed
rendering assets were cached. Final probes used `runClient -x downloadAssets`.
Do not erase caches or misdiagnose this download stall as a renderer fault.
The missing host narrator/flite library is a known unrelated Minecraft error.

Runtime probes alter the owned dev world's state and configuration. Their
backup/abort cleanup was repaired after a failed run. The owner's current
Sunlite Full/Ultra/Voxel selection was restored; profiling is Off because the
failed early run had not backed up that flag. Survival was explicitly restored.
The original configuration backup used for final probes is
`/tmp/greenmarine-deep-original-config.json`. Do not blindly reset to Portable
or edit the owner's Sunlite pack. No clients were left running at handoff.

## Workspace preservation

GreenMarine is currently an untracked directory in the parent repository,
not a committed checkpoint or separate project repository. Parent status shows
`?? project-greenmarine/`. No staging, commits, branch/worktree changes or
submodule pointer changes were made. Other sibling edits belong to the owner;
the owner is working on KoperLib concurrently. Preserve `readme.txt` and source
assets. Public documentation belongs in `docs/`; designs, plans and evidence
belong in `development/`. New code, comments and documentation are English.
