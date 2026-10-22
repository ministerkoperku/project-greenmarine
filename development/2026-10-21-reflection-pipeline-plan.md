# Reflection pipeline implementation plan

**Goal:** Compose block reflections on their surfaces and allow native reflections
to hit the animated local player with skin and block occlusion.

**Architecture:** See `2026-10-21-reflection-pipeline-design.md`. Independent tasks
have disjoint file ownership; root integrates and verifies the complete pipeline.
**Stack:** Java25/Fabric26.3/Renderpearl, GLSL, Rust/Vulkan and FFM ABI1.

## Constraints and review focus

Preserve existing public constructors, frame sizes and native calls. No installed
jar replacement or commits. Deliver only verified Android/Bionic artifacts to
`/storage/emulated/0/Documents/koper/recents/`. Avoid stale entity/skin data, main
depth feedback, duplicate capture, unmatched projection and in-flight mutation.

## Tasks

- [x] Native optional actor entrypoint, buffers/descriptors, posed box intersection
  and closest player/terrain hit in `engine/` and `NativeRayTracer.java`. Start
  with failing ABI/payload/intersection tests; preserve old entrypoint behavior.
- [x] Java animated vanilla model extraction and payload serializer in new
  `client/rt/PlayerReflection*` files. Test transforms, UVs, slim/outer layer and
  finite/bounded serialization before integration. Do not mutate host models.
- [x] Root: early capture with compatible program/explicit flag selection in WorldTransparencyMixin/WorldPrograms and
  SurfaceScene, single native reflection mix in solstice_terrain.glsl, no post
  reflection overlay. Pin actual host ordering in a regression before changes.
- [x] Root: integrate player payload in RayWorld with borrowed texture retention,
  independent review, full checks, dual-native build/smoke, export/checksum and
  honest gameplay limits in delivery evidence.
