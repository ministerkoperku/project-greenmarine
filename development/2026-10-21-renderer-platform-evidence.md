# GreenMarine 0.9.0 prototype — renderer platform and UI

## Delivered

- Public indexed triangle meshes with unsigned native-endian SHORT/INT data,
  validated bounds/stride/count and cleanup on partial allocation failure.
  Extended graph draw overload submits many instances in one actual host command;
  old methods remain available. Per-instance vertex streams are not added.
- Explicit transientTexture graph declarations with inspectable immutable
  allocation/lifetime plans and conservative equal-spec, nonoverlapping reuse.
  Existing texture declarations retain dedicated persistent storage, including
  unused legacy declarations. Imports never share owned storage. Resize is
  transactional; physical resources are closed once.
- Per-pass world binding cache avoids repeated GreenMarine binding setters when
  pipeline identity, frame/resource epoch and capture flags match. Host bytecode
  was checked: setPipeline retains/replays the frontend uniform map. Frame,
  pipeline, reset and capture state changes require fresh bindings.
- Profiling toggles preserve live programs, scripts and native ray scenes;
  value-only edits retain the old override path. Pack timestamp pools close
  dynamically when profiling stops. Profiling-only edits do not reset Lua values.
- New touch-sized paginated pack/quality/ray/parameter/settings/status/performance
  views, friendly names, direct pack selection, full wrapped fallback details,
  separately labeled CPU/GPU samples cached every 500ms. Policy-selected rays
  are distinguished from actual readiness.

## Validation

- Android dual-native Gradle test/build passed: 219 Java tests, 0 failures/errors,
  1 skipped optional Sodium resource fixture.
- All 56 Python tests passed, including Vulkan/OpenGL shader variant compilation
  and SPIR-V validation.
- Rust: 10 passed, 4 explicitly GPU-only tests ignored. No Rust/native ABI change.
- Actual Android/Bionic Java25 loaded the packaged native library, exercised FFM
  and closed successfully; this smoke has no attached host GPU.
- Packaged Android and Linux bytes match explicit-target release artifacts.
- Independent read-only review found no Important correctness/API/lifetime issue.
- git diff --check passed. Guarded Android exporter validates Bionic ELF and
  exported bytes are compared with the built artifact.

## Measured work boundaries and limits

Regression fixtures use real API validation/scheduling with fake allocation/draw
boundaries: two disjoint transient resources use one physical texture; repeated
same-state cache requests update once across20000 repetitions; indexed quad uses
4 vertices plus6 indices and draws64 instances in one command. These demonstrate
allocation and submission policy, not gameplay frame-time/FPS improvement.

Legacy persistent graph storage was explicitly regression-tested before aliasing
became opt-in. Wrong-owner/closed resource, invalid index/instance ranges, layout,
resize/partial failure, odd/small UI sizes, page boundaries, missing packs and
snapshot throttling are covered. Actual Android UI/touch appearance and new GPU
indexed/instanced execution remain runtime acceptance work. No comparative FPS
claim or renderer parity is established.

General compute/storage resources, texture3D/mips, full world/material capture,
PBR texture loading, world shadow maps, skinned player RT geometry and volumetric
clouds are not implemented by this release. The platform additions are real,
but do not automatically batch Minecraft entities or change builtin shader art.

JAR SHA256: 0755cf7db9d1fb46907258db80c55d62d1ffa5bc317ad8956bbe9929c4a4e705
Android native SHA256: 498919581aad70796961b929ffe19be4f3df0bdc6b18e20ac405aae084b2d238
