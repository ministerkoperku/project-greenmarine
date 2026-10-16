# World ray tracing implementation plan

> Execute inline with superpowers:executing-plans and test-driven-development.

Goal: Real hardware world rays, usable shaderpack attachments/example and honest
load status, continuing the 0.4 API.
Spec: `development/2026-10-13-hardware-rt-design.md`.
Constraints: MC 26.3/Java25, standalone, optional Fullpack, existing APIs/pack ABI,
Vulkan primary, labelled GL fallback; no FSR4/commits/installed-jar changes.

Review focus: enabled host features vs hardware support; in-flight descriptor/
buffer/resource lifetime; AS scratch alignment and barriers; partial-allocation
cleanup; stale Loaded status and unavailable ray-data bindings.

### Task 1: Public pack readiness and world-scene contracts

Files: api/pack/PackStatus.java; client/ShaderRuntime.java, ShaderScreen.java,
PackRenderer.java; client/rt/TriangleScene.java, WorldSceneScanner.java; unit tests.
- [x] Write failing tests for state transitions, scene ABI/UVs/limits/precision.
- [x] Implement real submission-driven status, visible notifications and scanner.
- [x] Run Java tests; retain all old 83 regressions.

### Task 2: Native host-backed hardware RT

Files: mixin/VulkanRtFeaturesMixin.java; client/rt/RtFeatures.java,
NativeRayTracer.java, RayWorld.java; engine/src/rt.rs; engine/shaders/world_rt.comp.
- [x] Test complete feature gates and unavailable native paths before implementation.
- [x] Enable optional host features, bind additive versioned RT C ABI, build owned
BLAS/TLAS and compute pipeline, record queries/copies using host command buffers.
- [x] Validate SPIR-V, run Rust tests/release build and controlled live Vulkan probe.
- [x] Fix concrete validation/lifetime faults with regressions, never fake readiness.

### Task 3: World pack integration and example delivery

Files: scene bindings/PackRenderer/MarineRenderer; Aurora RT pack shaders/script;
examples/shaderpacks ZIP task; src/probe HardwareRtProbe; public docs/evidence.
- [x] Test unchanged pack ABI and initialized unavailable bindings.
- [x] Integrate world outputs and labelled GL/voxel fallback, package example ZIP.
- [x] Probe actual forest/model rays, movement/bobbing, resize/reload/odd output,
empty scene/Off, invalid shader status/recovery and GL/native-disabled fallbacks;
restore settings and mode on exit/abort.
- [ ] Controlled partial-shape and paired offscreen-emission/blocked fixtures are
not accepted yet; retained as follow-up material acceptance, not a claimed result.
- [x] Measure CPU/GPU separately; run base/Fullpack builds and Java/Python/Rust
suites, release native build and packaging checks; request one final review.
- [x] Update progress/handoff and report useful installed artifacts plus limits.

Ledger: evidence in development/evidence/hardware-rt; this project is untracked
in the dirty parent repo, so no task commit/worktree operations are used.
Initial ruling: assume the stricter hardware-RT interpretation while the optional
clarification is pending; software-only work will not be described as hardware RT.

2026-10-14 delivery ruling: owner movement/white-light bugs take priority. The
0.5 native model path/example/status and movement/lifecycle verification are
complete. Controlled material fixtures and full scene/entity/PBR parity remain
explicit follow-up. Final acceptance is hardware-rt/vulkan-verified; failed
vulkan-final and vulkan-delivery runs are diagnostic evidence only. Review fixes,
restored current Prismarine settings and exact source/artifact hashes are recorded
in the evidence README. No further permission/commit/publishing was requested.
