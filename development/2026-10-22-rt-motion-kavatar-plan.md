# RT movement, copper patina and optional custom avatar correction

The owner confirms visual glitches during movement with RT enabled, strongest on
oxidized copper but also visible on iron. Their KAvatar model is absent from
reflections. They have not tested mobs; do not imply arbitrary entity capture.

## Evidence and bounded design

The reconstruction normalizes native hit validity by accepted taps only. A tiny
remaining compatible producer therefore supplies full-strength RT until the last
tap is rejected. The actual GLSL fixture reproduced this: all accepted pixels had
blue255 despite partial representation near the producer footprint boundary.
Introduce bounded accepted/intended footprint support, use it once for both native
lighting and reflection coverage, preserve normalized radiance and the original
6-argument include function via a compatibility overload. Do not add temporal
history, rays, samples or surface resources. This addresses a verified contributor,
not proof that every movement glitch is resolved.

Copper oxidation stages currently fall back to generic opaque class1. Add exposed,
weathered and oxidized full-cube atlas profiles using existing extension slots.
Waxed block IDs reference the same actual vanilla model textures. Patina uses a
new coated dielectric class9 with neutral F0.04 and increasing roughness, rather
than converting green patina albedo into metallic F0. Keep frame/native ABI sizes.

KAvatar replaces body rendering through its own Kodel layer; PlayerReflection
currently always builds a vanilla model. Add an optional isolated adapter reading
the actual public KAvatar evaluated frame, posed cube geometry and texture without
mutating host models or adding a required dependency. Preserve the native16box
budget; reject unsupported/oversized bodies explicitly instead of stale/partial
vanilla replacement. Use actual sibling source and built fixture jars read-only.

## Steps and verification

- [x] Reproduce full-strength partial-footprint behavior in actual GLSL, observe
  red policy/coverage tests, then apply support fading with unchanged tap budget.
- [x] Verify actual vanilla copper models/UVs and waxed aliases, then add class9
  profiles and native dispatch; validate source/native shaders and serialization.
- [x] Add KAvatar optional adapter with red/green real-model fixture tests for
  cube pose, deformation, UVs, slim models, budget/failure and absent dependency.
- [x] Independent review, full Java/Python/Rust/backend checks, Android dual-native
  build, Bionic smoke, guarded export/checksum and report exact runtime limits.
