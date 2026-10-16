# Shader Platform Implementation Plan

> Implement inline with superpowers:executing-plans; one independent final review.

**Goal:** A testable standalone shaderpack platform with scripts, lightmap
stages, portable/advanced examples, capability fallbacks and measured rendering.
**Architecture:** Validated resource catalog -> per-pack script runtime ->
cached Renderpearl lightmap/scene passes, with reusable buffers/targets.
**Tech stack:** Java 25, Minecraft 26.3, Fabric Loader, bundled LuaJ 3.0.1,
GLSL/SPIR-V, existing Rust FFM bridge.
**Spec:** `2026-10-13-shader-platform-design.md`.

## Constraints and review focus

No sibling source or installed jars changed; no API/Core requirement. Vulkan
first. Preserve user files and public ids. English code/docs. Fixed shader ABI
and ownership, bounded script work. Test missing capabilities, bad resources,
world/pack switching, pathological light lists and asynchronous GPU timing.

### Task 1: Pack schema and capability resolver
- [x] RED: `ShaderPackTest` rejects invalid schema/id/scale/duplicates/limits;
  `RenderPolicyTest` exercises OFF/AUTO/VOXEL/HARDWARE and pass fallback.
- [x] Implement immutable `api.pack.ShaderPack`, `PackPass`, `PackParameter`,
  `RenderCapabilities`, `RenderPolicy`, strict JSON parser and pass resolution.
- [x] GREEN: Gradle targeted tests, then suite. Catalog API exposes immutable
  definitions; resource loading validates shader/script existence transactionally.

### Task 2: Extended Lua API and ownership
- [x] RED: `PackScriptTest` evaluates time-based parameters and world lights,
  rejects nonfinite values, terminates infinite loops, hides IO/Java and releases
  owned lights. Add bundled LuaJ dependency; preserve standalone metadata.
- [x] Implement `script.PackScript` with `tick(Frame)` / `close()`, frame fields,
  `marine.uniform(name,value)`, `marine.light(slot,...)`, remove and math helpers.
- [x] GREEN: tests and suite; verify tick count/parameter updates in live probe.

### Task 3: Rendering, controls and examples
- [x] RED: catalog rollback, frame ABI, deterministic render schedule/target
  reuse and settings persistence tests. Preserve old configuration constructors.
- [x] Implement resource catalog, script lifecycle, F6 settings, lightmap mixin,
  `MarineFrame` UBO, scene ping-pong passes, public registration facade and two
  complete packs with Lua/GLSL. GPU failure returns ordinary rendering.
- [x] GREEN: both builds; Vulkan live pack/lightmap/reload/resize acceptance.

### Task 4: Optimization and voxel ray shadows
- [x] RED: light snapshot caching/ranking and voxel DDA wall tests; no-op frame
  schedule and fixed work budgets. Capture baseline native-disabled and enabled.
- [x] Implement reusable matrices, bounded top-light selection, conditional
  bloom, incrementally acquired voxel occupancy and optional ray-shadow path.
  Add nonblocking GPU timestamps and CPU metrics; validate measurements separately.
- [x] GREEN: suites, microbenchmarks and live Vulkan/OpenGL comparisons with
  budget counters; retain portable fallback and document fidelity tradeoffs.

### Task 5: Optional direct SPIR-V and handoff
- [x] RED: binary size/header/instruction bounds/entry stage and GLSL fallback.
- [x] Implement owned binary registry and scoped compiler hook returning a
  fresh SPIRVModule; require GLSL fallback. Validate and exercise sample binary.
- [x] GREEN: full suites, both artifacts, native-disabled portable runtime,
  independent read-only review and fixes, docs/evidence with honest limits.

## Ledger

Ruling: implement inline in the existing untracked project; broad autonomous
authorization supersedes repeated approval/commit/worktree steps. Cost if
wrong: later integration requires explicitly staging this project only.

Baseline: existing `./gradlew test` succeeds (21 tests).

Task 1: schema and policy tests GREEN; invalid/missing resource and transactional catalog tests GREEN. Missing feature contracts reproduced before implementation.
Task 2: six Lua tests GREEN, including loop budget, owned-light cleanup, extension factories and forbidden process/filesystem/reflection access. MathLib needs a temporary package.loaded table; provided it without loading PackageLib and removed public package access.
Task 3 in progress: 39 tests pass; settings, MarineFrame ABI, render schedule, examples and custom pipeline identifiers verified. Live GPU acceptance follows; shader/pipeline optimization and timings remain pending.

Task 3: first live standalone portable pack Vulkan acceptance succeeds after fixing probe focus/pause handling (test-only). Catalog 2 packs, Lua active before captures, scene pass submits before hand, paired brightening after resize/reload passes. Current evidence remains in ignored run/probe-output/platform-portable pending final archive.
Task 4 in progress: cached immutable light snapshots, bounded influence ranking, reused matrix scratch buffers, native-independent pulse, skipped zero-work point pass and disabled bloom taps. New tests reproduced missing features and snapshot reallocation before fixes; suite is GREEN. Added optional nonblocking GPU timestamp ring and bounded timing samples; advanced live run will validate actual GPU timing availability.

Task 4: voxel grid/DDA and incremental budget tests GREEN; real Advanced+voxel Vulkan run submits both GPU stages, publishes loaded-chunk-only snapshots and survives reload. GPU timestamps are nonblocking and work on the local device. Added a wall-specific runtime fixture; first run was rejected on image inspection because the server corrected the client-only teleport. Fixed fixture positioning through the owned integrated server, with temporary spectator mode and restoration. Correct wall acceptance remains in progress.
Task 5: structural SPIR-V tests GREEN, offline spirv-val Vulkan 1.1 succeeds, portable sample binary actually loads through the scoped compiler hook on Vulkan with native disabled. GLSL remains the portable fallback; unsupported binary capabilities use GLSL. Lua memory/string/call-depth guards added after a safe bounded growth regression reproduced the missing guard; suite GREEN.


Final acceptance: 54 Java tests, six Python verifier regressions, two Rust
unit tests and both 0.2.0-prototype Gradle builds succeed. Native release build
and offline Vulkan 1.1 SPIR-V validation succeed. Artifact inspection checks
Java 25, Loader/MC/Java-only requirements, one bundled LuaJ jar, four third-party
notice files, exact native bytes, sample SPIR-V and no probe/KoperLib classes.
Only the optional variant contains its Fullpack adapter/entrypoint.

Runtime acceptance: native-disabled Portable Vulkan uses the actual optimized
1764-byte SPIR-V module before/after reload. Portable GLSL on OpenGL and Advanced
on both backends survive resize, reload and world shutdown. Script ticks and
GPU timestamps are recorded. The fixed-wall Vulkan and OpenGL fixtures show
actual voxel occlusion rather than screen tint. Final Vulkan and OpenGL repeat the wall
check after owned Shaderc performance optimization. Evidence is under
`evidence/shader-platform/`; metrics cover these scenes/devices only.

Independent read-only review: no Critical, three Important and one Minor.
All accepted; one fix pass. Metatable-retained memory now counts toward Lua
budgets (bounded RED -> GREEN regression), profiling On -> Off -> On closes and
restarts point-light GPU queries (live RED -> GREEN), and standalone mod pack
discovery docs now require mounted assets or direct Java registration. Minor
stale status is replaced with the 0.2 handoff, preserving historical evidence.
Separate schema coercion RED -> GREEN check rejects 1.5 and string versions.
No review finding remains deferred. Details: `2026-10-13-shader-platform-review.md`.

GPU failure injection uses a directly registered pack with intentionally invalid
GLSL. Both backends reject it without stopping Minecraft and compile/submit the
valid resource pack after removal/restoration. F6 controls and screenshots are
verified live. Logged shader errors in these stages are expected test evidence.

Ruling: deliver a completed platform prototype with documented limits, not an
entire native path tracer. Hardware RT is explicitly unavailable and resolves
to voxel fallback; FSR4 stays deferred. Fullpack declaration compatibility is
preserved/compiled, while Fullpack scripting/KFX forwarding and full coexistence
remain future/unverified scope. Additional GPUs/OS, dedicated server and device
loss need separate runtime coverage. No commits, branches, installed jars or
sibling sources are changed.
