# Integration verification, 2026-10-30

All work remains in the existing main-development checkout. No installed game jars
were replaced and no Android delivery is claimed for the desktop build.

## Build checks

- GreenMarine: 399 discovered, 386 passed, 13 opt-in skips, 0 failures, 0 errors.
- Development tools: 126 discovered, 121 passed, 5 opt-in skips, 0 failures, 0 errors.
- Root `./gradlew test build` and `./gradlew -p development-tools test build` pass.
- `engine/cargo test`: 17 passed, 14 opt-in GPU tests ignored, no failures.
- KoperLib root compile/tests and Khysics build pass; 140 Khysics tests pass.
  Mechanics Dream compiles against that rebuilt Khysics library. Sibling source
  work and its evidence remain in the KoperLib checkout.
- `git diff --check` passes. Rust production sources are unchanged in this work.

## Actual Vulkan checks

RX 9070 XT, Minecraft 26.3 Renderpearl device/command encoder:

- Actor memory: 50000-frame actor and scene CPU regressions fit a 1 MiB direct
  limit with ZGC/DisableExplicitGC. Actual High torch/reflection fixture completed
  with two actor slots (36896 bytes) and normal teardown. No fourteen-minute
  fullpack acceptance run; the short sample is not a measured long-term plateau.
- Mechanics Dream static fixture: two actual quarter pieces; exactly twelve
  glowstone triangles inside the quarter's bounds. Matching ordinary stone
  comparison and lighting inputs captured. Moving geometry has unit/compile
  evidence only; actual moving lighting/shadow/reflection acceptance is pending.
- Volume and line fixture: 96 voxels / three slices and 17 RG32UI line texels,
  zero compute/clear/raster/line mismatches. W-addressing failure reproduced first,
  fixed boundaries read 1 and 3.
- Manifest uniform/MRT fixture: compute plus four scene passes, four frames,
  three mixed float/uint attachments, zero mismatches. Real host dimensions,
  frame index, projection/inverse, vec3/scalar and int-array packing verified.
- Two uniform payloads recorded in one encoder retain distinct expected results
  after reusing and freeing the CPU Arena before submission: zero mismatches.
- Raster and compute ABI rejection fixtures intentionally rename a member while
  preserving byte size. Both fail explicitly before the invalid binding is used.
  Shader modules are explicitly closed on rejected graphics pipeline validation.

Evidence lives under build/verification/actor-memory-2026-10-29,
build/verification/microgrid-2026-10-29 and
build/verification/eclipse-conversion-2026-10-29. The final uniform receipts are
uniform-abi-runtime/mrt-result.json, uniform-copy-runtime/uniform-copy-result.json
and uniform-abi-reject-verified-{raster,compute}/mrt-result.json.

## Eclipse status

The local converted project preserves source/license bytes and translated linked
stages, but remains runtimeReady=false. This work supplies real resources, named
MRT/feedback and typed host uniform plumbing; it does not establish a playable
full conversion. Remaining dependencies are imported phase scheduling, world/
entity attributes and MRT, shadows, intermediate graphics stages, custom option/
expression wiring, and DH/optional host integrations. Native DH depth and Iris
scalar plane conventions differ and must be integrated consistently.
