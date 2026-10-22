# Reflection stability design and implementation plan

Owner request: near-block reflections disappear in patches and flicker. The owner
mostly tested metal, diamond and netherite; glass is untested and water is good.
Preserve the reported playable performance improvement from 0.9.0.

## Diagnosis and design

The shared ray reconstruction compared world-space neighbour distance to a
camera-distance tolerance without accounting for grazing incidence. Reduced
native samples also originate at snapped full-capture texel centres, whereas the
filter assumed ideal reduced texture centres. Correct both in bounded scalar
helpers, preserving normal/material/plane rejection and the existing tap/ray
budgets. Keep the old scalar helper available. Respect reversed final scene depth
for transparent captured surfaces.

Diamond and netherite lacked atlas profiles. Preserve the existing public
32-profile/1040-byte block and append a separate 8-profile/272-byte uniform block.
Class 8 identifies solid diamond dielectric; netherite uses existing metal class.
Remove duplicate roughness energy suppression only for metal/gem reflection;
retain the current water response and model limits.

## Execution

1. Establish red scalar regressions and an actual-GLSL oblique-plane fixture.
2. Correct producer interpolation, grazing footprint and material response.
3. Add extension serialization, host ownership/binding, atlas lookup and native
   class dispatch with compatibility and actual UV classification tests.
4. Sweep distance/slope/output resolution and test distinct reflected colors on
   a nearer same-normal surface. Obtain independent review.
5. Run all Java/Python/Rust checks, rebuild both ARM64 native libraries, execute
   Bionic native smoke, then guarded Android export with byte/hash verification.

Do not substitute synthetic fixtures for in-game Vulkan acceptance. Do not add
unvalidated temporal reflection history or promise FPS changes from work counts.
