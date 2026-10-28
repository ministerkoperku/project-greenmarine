# Solstice edge, local-light cost and water/sky follow-up

Continue the owner's screenshot report: bright lines on shadowed block corners,
approximately 80 FPS with a held torch versus 133–160 without, excessively clear
water and distorted stars. High and Ultra appear similar to the owner.

- Reproduce the corner using actual reconstruction GLSL and a shadowed normal discontinuity. Distinguish geometric lighting support from reflection footprint confidence.
- Measure CPU/GPU surface-stage costs with/without the held torch in the same pose before changing native processing. Investigate full-traversal local shadow queries, actor shading work in shadow-only queries, and moonlight incorrectly driving unused sun-shaft marches.
- Keep water refraction in the material pass. Account for absorption/scattering once and avoid blending the already composed opaque background a second time.
- Replace the stretched planar star projection with angular point sources and pixel-footprint filtering; preserve parameter order and all 32 slots.
- Verify Java/Rust suites, shader variants, controlled regressions and an isolated Minecraft Vulkan run. Record limitations and desktop artifact checksums. Preserve all previous work and installed jars; do not export a desktop library as Android.

Implemented and verified. See `2026-10-27-solstice-edges-performance-evidence.md`
for final behavior, review corrections, rejected/accepted runs and artifact hashes.
