# GreenMarine progress

Next-session direction and continuity are recorded in
[SESSION-HANDOFF.md](SESSION-HANDOFF.md): develop a substantial public rendering
and shader API inspired by Veil/Iris, continuing the current 0.8 source and preserving earlier public contracts.

## Current milestone: freshness and rendering cost, 0.8.0-prototype

Named prior scene-pass inputs retain their current-frame outputs independently
of reusable ping-pong images. Old PackPass constructors remain. Solstice caches
linear scene lighting/fog once and reuses it for bloom/composition. Opt-in lighting
history stores material/final-depth rejection inputs and uses elapsed frame time;
actual GPU tests reject changed materials, current/prior opaque entities and
adjacent rejected color texels. MarineFrame288, history160 and native ABI1 layouts
remain; formerly reserved metadata fields now expose frame/scene age.

Pending bookkeeping is O(1), cached column membership is stable, overlapping
camera moves retain valid extraction and only actual urgent edits preempt it.
Covered publication retains previous complete geometry while outer sections load,
without blocking edits inside retained bounds. Exact-size immutable assembly and
metadata coalescing remove repeated full-scene growth/copies and unchanged builds.
Reduced RT samples reconstruct from the actual captured texel centre. A first-hit
sun-query candidate was measured slower and removed before delivery.

Fixed-sun/clear-weather Vulkan Ultra movement at 1200x675 measures 6.166 → 5.704 ms
mean callback interval and 9.330 → 7.379 ms p95. Composition GPU is .444 → .184 ms;
cumulative packing is 1145 → 87 ms across a 96-block route. Host-only intervals
also vary, so stage/counter savings are stronger evidence than attributing the
entire frame difference to this mod. No universal FPS gain is claimed.

Final dynamic evidence records 14.39–35.98 ms edit-to-changed-native frames,
1610 correct walking samples, 13.81 ms bounds catch-up and zero steady native
idle calls. Final image changes settle to 90% in 44.2–55.9 ms with 26–33% maximum
per-frame steps, versus about 95% in the discontinuous baseline. This does not
prove invisible transitions or hitch-free gameplay everywhere. Finite 96x64x96
RT, missing entity/fluid secondary geometry and full Iris parity remain limits.
FSR4 stays deferred. [Measurements, review and verification](evidence/performance-08/README.md).

## Previous milestone: surface materials and dynamic RT, 0.7.0-prototype

Solstice RT changes terrain, real water geometry/refraction/absorption,
offscreen model reflections, sun/GI, sky/cloud layers, weather, puddles, fog,
bloom and tone mapping. Public surface capture, environment, opt-in history and
surface imports retain existing schema/ABI/constructors. OpenGL uses the shared
raster fallback and reports unavailable hardware RT honestly.

The owner's delayed walking shadows were reproduced and addressed with cached
sections, render-frame extraction, actual world invalidation, bounded worker
preparation and fence-retained native scene generations. Five controlled edits
reached GPU sunlight in 9.88–14.18 ms versus 4.96–5.24 seconds; 2509 walking GPU
samples passed with no steady device-idle calls. This is local fixture evidence,
not a universal frame-time promise. Finite 96x64x96 geometry, entity/fluid secondary
ray omissions, first-use compilation and full Iris parity remain limits.

[Pack, API and checks](evidence/solstice/README.md),
[dynamic scene measurements](evidence/dynamic-rays/README.md),
[authoring and limits](../docs/SOLSTICE-RT.md). FSR4 remains deferred.

Final verification: 141 Java, 36 Python, 10 portable Rust and two actual GPU tests,
22 real FFM checks, base/Fullpack builds, native release, clippy/fmt and SPIR-V.
Current Vulkan Solstice/OIT, OpenGL classic fallback and legacy Aurora probes pass.

## Previous milestone: world programs, 0.6.0-prototype

Schema-v1 world vertex/fragment replacements and public host-state cloning now
join the existing GPU resources/graphs/material API. Seabreeze changes terrain,
actual water geometry/refraction/absorption, sky and clouds on Vulkan and OpenGL.
An independent corrected Prismarine reproduces/fixes the owner's white RT patch
without modifying its archive. Night ambient/GI, initialization, classic/OIT and
hand scope are verified. FSR4 remains deferred; no Iris format/parity claim.

102 Java, 17 Python and 4 Rust tests; base/Fullpack builds, release native and
SPIR-V pass. World probes and Aurora camera/scene lifecycle regression pass.
[Evidence, measurements and review](evidence/world-programs/README.md),
[authoring and limits](../docs/WORLD-PROGRAMS.md).

## Previous milestone: world hardware rays, 0.5.0-prototype

Continuing the 0.4 public API, native Vulkan Ray Query now builds owned model
BLAS/TLAS and traces textured, alpha-tested loaded block geometry, sun shadows
and four diffuse/emission secondary rays. Complete actual enabled host features
are required. Commands use Minecraft's borrowed device/encoder/atlas and no
steady-frame device idle. Core ABI1, schema1, MarineFrame288 and old constructors
are preserved. OpenGL/native-disabled systems report a portable shader fallback.

Owner lighting reports led to a confirmed camera bug: translated bob/hurt matrices
changed the perspective eye but the primary ray still started at the physical
camera. RayCamera now extracts that eye from the submitted projection. Regression
math covers bob/hurt/nausea and both depth conventions. Filtering irradiance rather
than radiance/albedo ratios removes dark-texel amplification; common-scale highlight
compression preserves hue. Random ray directions use world coordinates. The sun
uses vanilla sky angle/weather/dimension, rather than a fixed camera-era direction.

Scene coverage is 96x64x96 blocks on a 16-block world grid, up to one million
triangles, with empty-section skipping and a 1.5ms incremental scan budget. The
Aurora composite shares a global baseline outside RT coverage and blends shadow/GI
over eight blocks at scene edges. This avoids the previous hard moving patch;
finite RT distance and stale scan updates remain explicit limits. Half-resolution
alpha foliage silhouettes can still mismatch/shimmer. Biome tint is not yet in
native model albedo. Entities/fluids/special block renderers remain vanilla;
there is no Iris pack-format compatibility or full Veil/Iris parity claim.

Public frame imports expose model irradiance, normal/depth and albedo plus current
readiness, with initialized distinct empty textures. F6/chat report actual selected
pack program readiness and errors; script-only packs explicitly report script
readiness without rendering passes. Aurora is builtin and an independent namespace
example ZIP is built by exampleShaderpack. Full verification and review rulings:
[evidence/hardware-rt/README.md](evidence/hardware-rt/README.md).

## Historical public renderer milestone: 0.4.0-prototype

Implemented an additive client-only `api.render` layer: render-stage registration
leases, callback-scoped frame snapshots, owned texture/buffer/program handles,
GLSL/includes/optional fragment SPIR-V snapshots, named single-writer texture
graphs and checked mesh/material draws. Existing pack APIs, shader schema v1,
288-byte MarineFrame and optional Fullpack declarations are preserved. Factories
and callbacks fail per contribution; world/resource reset releases sessions and
keeps registrations. Java uses the host Renderpearl device on both backends.

`GeometryExample` rasterizes a real 36-vertex mesh into albedo, normal and depth
attachments, then shades/composites against scene depth. It is opt-in and uses
only the exported API. The controlled fixture draws it in front of the camera;
world-relative models use double-precision camera subtraction. There is no
vanilla-world G-buffer or terrain replacement in this release.

Graphs validate ordering, cycles, missing inputs, duplicate writers, physical
import aliases, declared accesses, attachment formats/view dimensions/aspects,
material bindings and mesh layouts. Targets and pipelines are reused. Fixed
textures survive resize; a test fixture previously allocated four textures
across its initial frame and resize, now three. Failed replacement allocation
closes partial new objects and preserves old targets. Runtime confirms three
mesh attachments and two compiled programs across hundreds of frames, with
exactly three replacement attachments on resize.

Fresh verification: 83 Java tests, 13 Python verifier regressions and two Rust
tests pass. Base and optional Fullpack test/build pass. Vulkan and OpenGL probes
pass geometry/occlusion captures, resize/reload, owned-resource lease cleanup,
borrowed scene survival, wrong-thread reset rejection and invalid-shader
isolation while Radiance keeps rendering. The `fromPack` bridge executes the
Portable fragment through real SPIR-V on Vulkan and GLSL on OpenGL. Artifact
inspection verifies Java 25, Loader/MC/Java-only requirements, exact native bytes,
optional adapter isolation and excluded probes. Installed jars are untouched.

One independent review found three Important issues and a smaller example
pipeline leak. All are closed: physical import alias rejection, depth clear
selection for LESS/GREATER pipelines, render-thread guards before lifecycle
mutation, and release of the replaced example program. Tests reproduce the
first three; the initialized-session wrong-thread case also passes live.
Initial rejected runtime runs exposed host sparse vertex slots and null depth
attachment handling; both have RED/GREEN regressions and final runtime passes.

Measured graph costs and exact artifacts are in
[evidence/render-api/README.md](evidence/render-api/README.md).
The final Vulkan sample at 1200x675 has 0.887 ms mean CPU submission/profiler
cost and 0.0244 ms mean GPU cost. These describe only the example graph on
RX 9070 XT/RADV, exclude session-factory compilation and are not FPS gains.
Submission variance is substantial; no controlled CPU speedup is claimed.

Public guide: [RENDERING-API.md](../docs/RENDERING-API.md). Design/plan:
`2026-10-13-render-api-design.md` and `2026-10-13-render-api-plan.md`.
Limits: non-indexed single-binding triangle helpers, explicit shader bindings,
synchronous first-use compilation, no graph history/alias pooling or cross-mod
graph merging. Generic Java graph contributions do not change schema-v1 pack
manifests or automatically populate pack uniforms. Contributed mesh composition
does not write vanilla depth for later effects. Broader shader-mod coexistence,
other devices/OSes and device-loss recovery remain unverified. Hardware RT and
FSR4 remain deferred. All source remains uncommitted; sibling work is preserved.

## Historical world material lighting milestone: 0.3.0-prototype

Owner request: finish the ReShade-like authoring layer and start deeper renderer
work. This stage adds practical parameter editing and HDR targets, then a
world material volume and a real shader consumer; it does not declare the full
native/material renderer finished. Design/plan:
`2026-10-13-world-lighting-platform-design.md` and
`2026-10-13-world-lighting-platform-plan.md`.

Implemented: F6 Pack parameters exposes paged sliders, live preview without
script/pipeline restart, per-pack persistence, authored-Lua restoration on reset,
and safe edits while effects are disabled. Overrides are immutable/finite and
pruned against reloaded definitions; storage errors are visible and do not evict
other pack settings. Old constructors remain available.

Scene passes can use `format: rgba16f`; values above 1 survive intermediate
passes, and incompatible final formats convert by drawing. Atlas/readiness
bindings are initialized for every scene shader, including requires:none and
fallback shaders with rays Off. Lightmap stages remain separate. Unsupported
float target allocation uses the scene format with a log and reduced HDR range.

World data now contains full-block occupancy, map-color RGB albedo and vanilla
0..15 emission. The loaded-chunk scanner publishes geometry/materials together,
then uploads changed cells into a reusable 256x128 RGBA8 atlas. Shader API
`greenmarine:voxel.glsl` exposes material lookup and bounded first-hit DDA rays.
Radiance traces 4 High/8 Ultra hemisphere rays per half-resolution surface,
receives emission from offscreen blocks, and composes with the full-resolution
scene using depth rejection. Ray distance is 12 blocks within the local 32-cubed
volume. This is world emitter lighting, not neighboring-screen color borrowing.

Review: one read-only review found no Critical, three Important, no Minor. One
fix pass closes all findings: initialized shared bindings for ordinary/fallback
passes (live Missing uniform VoxelFrame RED -> GREEN), explicit-pack editing
without an active script (unit RED -> GREEN), and retired-name pruning / safe
storage rejection (unit RED -> GREEN). The suite contains 62 passing Java tests;
11 Python checks and two Rust tests pass. Native Rust source/ABI are unchanged.

Runtime evidence and exact final metrics are in
[evidence/world-materials/README.md](evidence/world-materials/README.md).
Controlled fixtures exercise world emission with separate On/Off pairs for
both open and blocked geometry, HDR arithmetic 4/2/1 -> 0.5/0.25/0.125, explicit
voxel bindings with rays Off, real sliders and resize/resource reload. The
normal pack default gain stays 2; the fixture uses gain 4 for visible contrast.
Rejected runs are retained and are not acceptance evidence. The fixture now
waits for spectator acknowledgement, freezes position/velocity and excludes
client-side entities to avoid gameplay state contaminating the sampled floor.

Known limits: fixed low ray counts, full-cell partial-emitter approximation,
map-color materials rather than texture/normal/PBR data, local-volume reach,
stale incremental snapshots, no multi-bounce transport or temporal denoising.
Computed light adds to vanilla illumination without separating vanilla block
light, so energy can be counted twice. Geometry rasterization remains vanilla.
A complete material renderer, hardware RT and native drawing are still future
work; FSR4 remains explicitly deferred. First-use compilation and scene copies
retain the previously documented costs. Fullpack declarations remain optional;
Fullpack Lua/KFX forwarding and broader shader-mod coexistence are unverified.

Workspace rulings: implement inline with no commits/worktree/branch/index or
installed-jar changes; sibling/user pack sources are preserved. Java acquires
world data and Vulkan/OpenGL shaders trace rays; a future native engine needs
its own bridge. Fixed-fixture brightness checks use geometry-specific baselines,
not a universal arbitrary-image test. A failing dev fixture temporarily left its
settings/mode selected; original settings are now backed up and abort restores
them. The owner's Sunlite Full/Ultra/Voxel selection was restored with profiling
Off; the previous profiling flag was not backed up by that failed run. Survival
was explicitly restored in the owned dev world. Original lighting configuration
is restored after final probes. No review findings are deferred.

## Historical shader platform milestone: 0.2.0-prototype

Implemented on 2026-10-13 under the owner's autonomous continuation request.
This is a completed, testable platform prototype; the wider native renderer,
hardware RT and FSR vision remains future work. Vulkan remains primary.
Design and plan: `2026-10-13-shader-platform-design.md` and
`2026-10-13-shader-platform-plan.md`. Public installation/controls are in
[docs/README.md](../docs/README.md); authoring is in
[docs/SHADERPACKS.md](../docs/SHADERPACKS.md).

Packs now have schema-v1 manifests, extended Lua engine functions, declaration-
ordered uniform parameters, a real lightmap stage and ordered GLSL scene passes.
Optional precompiled fragment SPIR-V loads directly on Vulkan and keeps GLSL
fallbacks. Includes are snapshotted transactionally. Public Java registration
supports standalone mods; unmounted asset-only mods are not auto-discovered.
Lua runs once per unpaused tick with instruction, string, memory and call-depth
budgets, cleans up owned lights and accepts trusted Java engine extensions.
Two included packs demonstrate Portable lightmap/color grading and Advanced
half-resolution bloom/full-resolution depth compositing. F6 selects pack,
quality, ray policy and profiling; F7/F8 remain lamp placement/effects toggle.

Voxel rays now shadow opaque full blocks through a loaded-chunk-only 32-cubed
occupancy volume on both backends. Acquisition is incremental (2048 reads /
750 microsecond target per tick), publishes only complete snapshots and avoids
unchanged-cell uploads. Only the first four selected sources receive shadows;
up to 16 selected sources illuminate the world. CPU side-frustum rejection and
bounded influence ranking replace insertion-order selection. Light snapshots,
matrices, schedules, pipelines and targets are reused. Disabled bloom and
empty light frames skip GPU work. Owned GLSL uses Shaderc performance options;
the sample SPIR-V is optimized offline with reflection names preserved.
Hardware mode explicitly falls back; no hardware RT pipeline exists.

### Verified scope

Fresh base and optional Fullpack `test build` succeed: **54 Java tests** with
zero failures/errors. **Six Python verifier regressions** and **two Rust unit
tests** pass. Native release build and offline Vulkan 1.1 `spirv-val` pass.
[Artifact inspection](evidence/shader-platform/artifacts.json) confirms Java 25
bytecode, only Loader/MC/Java requirements, one nested LuaJ jar, four third-party
notices, exact release native bytes and absence of probe/KoperLib classes.
Only the Fullpack variant has the adapter/entrypoint. Install one variant:
`greenmarine-0.2.0-prototype.jar` or `greenmarine-fullpack-0.2.0-prototype.jar`.
Base remains independent of Fabric API/KoperLib; missing native processing
uses Java animation without losing rendering.

Live Wayland/RX 9070 XT acceptance covers Portable Vulkan with native disabled
and actual optimized 1764-byte SPIR-V before/after reload, Portable GLSL on
OpenGL, and Advanced on both backends (Vulkan with native enabled, OpenGL with
native disabled). Paired captures survive resize/reload, source positions
remain fixed after a camera turn, scripts advance and shutdown is clean.
F6 On -> Off -> On profiling is verified with unchanged ray mode. Direct Java
registration supplies an intentionally invalid GPU shader: it is rejected,
Minecraft continues, and the valid pack submits after restoration. The error
logs in that injection stage are expected evidence. Results retain pre-transition
metrics rather than reporting counters cleared by settings changes.

Fixed-wall fixtures additionally verify actual occlusion: voxel rays remove
green light from the foreground floor while retaining the ordinary scene.
Both fixtures were repeated after final owned shader optimization. Earlier
brightening-only screenshots are insufficient to prove wall shadows or spatial
lighting. Relevant images were also inspected directly.

CPU/GPU measurements and their resolution/scope are in
[evidence/shader-platform/README.md](evidence/shader-platform/README.md).
The final Vulkan wall stage costs approximately 0.021 ms without voxel rays
and 0.045 ms with them at 960x540. These are stage times for one device/scene,
not whole-frame time or a universal FPS claim. Profiling is off by default.

### Review, limits and handoff

One independent read-only final review found no Critical issue, three Important
issues and one Minor status issue. One fix pass closes all findings: real Lua
metatables count toward retained-memory limits (bounded RED -> GREEN), settings
close point-light GPU queries (live RED -> GREEN), standalone contribution docs
require mounting/direct registration, and this handoff replaces the stale
current milestone. A separate RED -> GREEN schema-coercion regression rejects
1.5/string versions. See [review/rulings](2026-10-13-shader-platform-review.md).
No review finding is deferred and no second review loop is started.

Limits: scene depth-derived normals, no G-buffer/material maps, partial-block/
entity voxel occlusion, sun shadows, GI or denoising. Outside the local volume
lighting is unshadowed; incremental scans can be stale during movement/changes.
First-use pipeline compilation can hitch. Shader correctness, trusted Java
callback budgets, extra OS/GPU coverage, dedicated server and device-loss
recovery still need separate validation. Full Sodium/KFX coexistence and
Fullpack Lua/KFX forwarding are unverified/unimplemented; the existing Fullpack
effect declarations remain compiled and their historical runtime evidence is
preserved below. FSR4 is explicitly deferred. No OptiFine packs, hardware path
tracer or independent ash drawing is advertised.

All changes remain uncommitted in this project. Installed game jars, sibling
sources, branches, index and submodule pointers were not changed by this work.
The owner is concurrently editing siblings; their workspace changes are preserved.

## Historical world lighting milestone: 0.1.1-prototype

Owner correction on 2026-10-12: replace the screen tint with spatial lighting;
Vulkan is primary and Renderpearl portability to OpenGL is permitted.
The bounded design is in `2026-10-12-world-lighting.md`.

Implemented: fixed world-positioned point lights with radius falloff and
depth-derived diffuse response, submitted before world depth is cleared for
the hand. The shader uses the exact uploaded camera projection (including
bobbing/nausea), reverse depth and inverse view rotation. Light positions are
made camera-relative in double precision before upload. Sky is excluded from
point lighting. F7 places/repositions the demonstration lamp; F8 toggles effects.
The Java LightRegistry now supplies up to 16 sources to the pass. Bloom is
independent and defaults to zero; existing configuration files are preserved.

Vulkan acceptance: standalone Loader + GreenMarine on Wayland/RX 9070 XT
enters the demo world, borrows the actual host context with status 0, toggles,
resizes, reloads, turns the camera, disconnects and stops cleanly. Paired
pixel checks pass before reload (center green 0.23463 enabled / 0.22388
disabled) and afterwards (0.23808 / 0.22848). The subsequent turned-camera
enabled/disabled pair was inspected directly: nearby grass and leaves receive
localized light, which remains on the same terrain after the camera turn;
the sky and hand retain ordinary rendering. Source coordinates remain fixed.
This goes beyond the brightening-only automated verifier, which alone cannot
distinguish spatial lighting from tinting.

Secondary OpenGL acceptance: the same standalone probe passes on the same
Wayland/RX 9070 XT system, without a borrowed Vulkan context. Center green
pairs are 0.23342 / 0.22957 before reload and 0.23190 / 0.22895 afterwards.
Inspected the turned-camera pair: localized illumination remains visible on
the nearby terrain, with ordinary sky and hand. Vulkan remains the default
development backend and primary target; the base mod does not silently switch
an existing Minecraft graphics device.

Verification: 21 Java tests pass, including both depth conventions,
camera rotation, large-coordinate precision, GPU light bounds and configurable
radius. Base and optional Fullpack builds succeed. Three existing Python
verifier regression tests pass. Rust source/ABI are unchanged from the verified
foundation release.

Packaged handoff: `greenmarine-0.1.1-prototype.jar` and
`greenmarine-fullpack-0.1.1-prototype.jar` verified for Java 25 bytecode,
Loader/Minecraft/Java-only requirements, exact release native bytes and
absence of probe/KoperLib classes. Only the optional jar contains its adapter
and Fullpack entrypoint. All changes remain uncommitted in this project;
installed game jars, sibling sources and branches were not changed.

Independent read-only review found no Critical or Important implementation
defect in this correction. Minor items: insertion-order selection may starve
later lights (documented shared 16-source cap); registry position equality
does not prove visual stability (added and inspected the paired camera-turn
capture); stale public instructions (updated).

Limits at the 0.1.1 milestone: no shadows or wall occlusion, no material normal maps/G-buffer,
approximate transparency/fog, Linux x86_64 native library. Drawing still goes
through Renderpearl, not raw ash commands. Fullpack declaration integration
compiles; its current runtime discovery evidence belongs to the earlier
foundation below. Full KFX/Sodium coexistence, scripts, networking, FSR,
ray tracing and OptiFine shaderpacks remain unimplemented/unverified.

Screenshots and filtered logs for 0.1.1: `evidence/world-lighting/`
and its `opengl/` subdirectory.

## Historical foundation: 0.1.0-prototype

The following records the superseded screen-effect milestone, not the current
lighting behavior. Plan: `2026-10-12-foundation-plan.md`.

Owner approved the design and all further work on 2026-10-12; working prototype requested this week without rushing.

Ruling: execute inline in the existing new project directory. The parent repository is on main with unrelated dirty work; do not change its branch, create a workspace-wide checkout or stage sibling files. This project has no existing build/test baseline.

Ruling: the visible diagnostic GPU pass is included now as the next small renderer milestone requested by the owner. It uses Minecraft's command encoder for barriers and submission; raw ash drawing is later. It must not be advertised as ray tracing, FSR or terrain lighting.

Pre-flight: effect registry is shared by optional Fullpack and client configuration; both use the same validated EffectDefinition. Native ABI is shared by Java loader and Vulkan host; all handles use 64-bit values with explicit ABI version 1.

Task 1: Java effect/light tests passed. Missing types reproduced before implementation.
Task 2: Rust tests passed; real FFM ABI tests passed with the bundled release library.
Task 3: Java base build and standalone Vulkan probe pass. Initial XWayland SDL_CreateWindow hung before renderer startup; Wayland completes the world/render/resize/reload lifecycle. Added direct Minecraft resource-pack registration because Loader alone does not mount mod assets (runtime initially reported only vanilla resources).
Task 4: Optional Fullpack adapter compiles with exact 0.1.1-alpha jars; registry reload tests and runtime declaration/selected-effect checks pass.

Ruling: include direct Minecraft resource-pack registration in the base mod to keep Fabric API optional. A split dev-root resource test covers selecting the resources root instead of the classes root.

Runtime evidence: Wayland/Vulkan on RX 9070 XT, with only Loader/GreenMarine and the test-only probe, enters the demo world. Rust context attachment returns 0. On/off screenshots are visibly different; `tools/verify-probe.py` passes (center green 0.25128 enabled vs 0.22652 disabled; after resize/reload 0.28923). Clean world disconnect and shutdown were exercised.

Fullpack runtime evidence: Core + Fullpack 0.1.1-alpha + Fabric API 0.161.0+26.3 were staged only in this project's development `run/mods/`. The optional addon registered, the fixture loaded one declaration and its intensity 0.6 reached the native sampler. Paired image verification passed before reload (green 0.29609 vs 0.24385) and after resize/reload (0.31738 vs 0.25471). Test jars were removed afterwards. The example uses `jsons/aurora.json`, matching the current loader's generic one-level directory scan.

Fixed: pipeline String overload prepended the vanilla namespace; regression test reproduced IdentifierException and passes with Identifier overloads. Fixed: renderpearl draw argument order is vertexCount, instanceCount, firstVertex, firstInstance; the old order drew zero vertices and copied black scratch data. Pixel probe reproduced the black frame and passes after correcting the draw.

Final review: one read-only independent reviewer; no Critical findings, two Important findings.
Fixed authoring/native intensity mismatch: two Java tests failed before adding the 0..16 effect validation and now pass. The Light data contract remains broader.
Fixed reload verifier: two Python CLI tests reproduced false passing results for vanilla-only reload pixels and failed captures. The probe now deletes stale captures, records completion/failure, and captures an enabled/disabled pair after reload. All three verifier tests pass; the strengthened Fullpack pixel probe passes.

Review rulings: Fullpack discovery/coexistence is established by the runtime evidence above. OpenGL, Sodium/KFX coexistence, dedicated-server startup and other GPUs/platforms remain unverified. FSR/RT/OptiFine/scene lighting/script/network control remain separate milestones, as agreed. Test/binary verification is performed by the implementer because the reviewer was read-only.

Final milestone status: Tasks 1–4 complete for the implemented foundation and visible glow prototype. All source remains uncommitted for owner review. No sibling source, installed instance jar or branch was changed.

Final verification:
- Base `./gradlew test build`: successful; 16 Java tests, zero failures/errors.
- Optional `./gradlew -PfullpackIntegration=true test build`: successful.
- `cargo test`: two passing tests; `cargo fmt --check`, Clippy with warnings denied and `cargo build --release`: successful.
- Three Python pixel-verifier regression tests pass.
- Both packaged jars have only Loader/Minecraft/Java requirements, contain the current release native library and exclude probe/KoperLib classes. The base jar excludes the Fullpack adapter.
- Final standalone Vulkan pixel pairs pass before reload (0.27513 vs 0.24422 green) and afterwards (0.29246 vs 0.25476), with no optional mods installed.
- Native-disabled client starts, enters the demo world, resizes/reloads, disconnects and exits cleanly. Ordinary world pixels remain visible; no GreenMarine GPU pass is submitted.

Archived screenshots and filtered GreenMarine runtime evidence are under `evidence/`. Full logs and worlds are development artifacts under the ignored `run/` and `/tmp/` paths. The local vanilla narrator logs missing `flite`; no narrator support or OpenGL startup success is claimed. Native-disabled startup does not establish support on Windows or another native platform.
