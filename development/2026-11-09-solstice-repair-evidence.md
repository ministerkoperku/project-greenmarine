# Solstice repair evidence, 2026-11-09

## Status

This is a bounded first repair batch, not completion of the owner's Solstice
overhaul. A definite texture-history borrowing defect is repaired, but the owner
still observes similar-looking blocks that appear closer to vanilla and less
smoothed. The complete first visual report remains open. Do not infer its full
cause from the word PBR or mark it solved by the history fix alone.
The Iris converter and its large existing WIP are preserved.

## Confirmed local-light shadow defect

`surface_rt.comp` shortened block visibility rays by0.6 blocks regardless of
source type. A real wall0.2 blocks before a held light was consequently ignored.
The new GPU regression failed on RX9070XT with ambient0.19995 and both unobstructed
and incorrectly blocked output0.96045. Complete receiver-to-light segments now
produce0.19995 behind that wall. Emitting source geometry still produces0.84180;
a different emitting block still occludes. Only scanned emitting world geometry
inside the source's cell is exempted. Arbitrary modded source models extending
outside that cell remain unproven; the native ABI has no owning-block metadata.

This proves a near-light visibility bug. It does not prove all shadow artifacts
in the owner's five screenshots are resolved.

## Static-light readiness

The old global cache waited20 ticks or more than8 blocks of movement, without
observing chunk availability or existing geometry notifications. A section cache
now tracks identities/palettes and receives block/section/column/reset events from
RayWorld. New and edited nearby sections precede periodic refresh. Unloaded,
replaced and empty sections cannot retain old lights. Extraction is bounded to
four sections (16384 block visits) per refresh.

Stable frames use a tick/camera-block/invalidation-generation memo before section
observation, sorting and allocation. Dynamic sources still interpolate per frame.
Dense initial emitter neighborhoods can fill over multiple tick/event refreshes.
Fifteen pure Java tests cover correctness and repeated-frame work avoidance;
legacy scheduling reproduced nine assertion failures, and the first section-cache
implementation failed the added repeated-observation cost regressions.

The exact delayed lava appearance reported by the owner has not been reproduced.
Emitter-table readiness and fluid appearance/mesh/lightmap readiness are distinct.

## Actual entity-light runtime proof

Evidence directory:
`build/verification/solstice-repair-2026-11-09/entity-light/`.
`entity-light-result.json` reports passed on the host Vulkan backend. A sealed
stone room has no block emitters and world block/skylight both remain zero.
Actual EntityRenderer.getPackedLightCoords and extracted render-state light agree:

| State | Packed entity block-light coordinate |
|---|---:|
| Torch two blocks away, Hardware |196|
| Empty hand |0|
| Torch re-equipped |196|
| Torch22 blocks away |0|
| Solstice with rays Off |196|
| Pack none, torch still equipped |0|

Inspected `entity-held-on.png`, `entity-held-off.png` and `entity-rays-off.png`.
The mob visibly responds; the underlying world light is not modified. Terrain and
entities share interpolated held/dropped/fire sources through one lazy frame
snapshot. Entity reception adds scalar unoccluded block light, preserving sky and
existing fullbright. It is not colored RT shading or a block-entity/hand hook.

## Texture investigation and bounded history repair

Actual Vulkan captures use2560x1370, Medium, forced Hardware and default resources.
The initial diagnostic launch captured a demo popup and is rejected. Subsequent
valid captures are in `before/`, `history/` and `owner-before/` under
`build/verification/solstice-repair-2026-11-09/`. The last directory uses a copied
`run/saves/New World (1)`; the original world/settings were not modified.

Each valid run compares default, relief0, GI0, rays Off and diagnostic10; later
runs also invalidate history each frame. Sand sprite is(1456,1232,16,16) in the
2048-square atlas. Logs report0 authored specular/normal maps and0 addons.
Sand has no inferred material. Its16-aligned bounds do not cross a neighboring
sprite in the relief helper. Host atlas format is RGBA8_UNORM, and history
validation textures use nearest sampling; two initially suspected mechanisms
are therefore excluded. No production texture workaround was applied.

The owner subsequently reported the symptom remains visible, possibly less
strongly, with affected blocks looking alike and closer to unsmoothed vanilla.
They also suggested lighting may contribute. Keep that exact description.

An executable regression now establishes one definite wrong-texture mechanism:
the old aggregate linear-albedo tolerance0.035 accepted historical albedo0.43
for current albedo0.40 with matching geometry/material. Executing the actual
production rejection and blending statements changed current final RGB0.40 to
0.418853581 using the different texel's final color0.43. The new helper tests
each RGB channel at its own RGBA16F relative precision with a2^-24 floor.
The witness now remains0.40; same-albedo lighting history and one-step rounding
remain accepted. Dark-channel changes cannot hide behind saturated channels.
Eight RED/GREEN behavioral cases execute the production predicate and blend as
C++; they are policy proof, separate from actual GPU sampling.

Matched moving-camera Vulkan runs are `motion/` and `motion-after/`, with the
same owner-world camera path and a history-disabled comparison per run. Both
complete. In the fixed screenshot crop(250,200)-(2000,900), raster history-vs-off
RGB mean absolute differences decreased from(0.895,1.147,1.261) to
(0.223,0.244,0.288) out of255; maximum differences decreased from(5,7,8) to
(3,4,5). This quantifies reduced color feedback in that fixture, not complete
visual acceptance or FPS improvement. Hardware differences also include
changing native lighting and are not a clean texture-only error metric.

The follow-up `coverage/` probe adds actual captured albedo and native coverage
diagnostics. The sand plane is uniformly cyan in diagnostic3 (full coverage
and gathered support), excluding per-block missing-RT fallback in this view.
No arbitrary material/default/relief disabling was applied. Independent review
found no Important issue in the narrow history fix; stricter matching can reduce
temporal stabilization on filtered/mipped moving textures.

## Procedural relief orientation repair

The owner's follow-up reports affected blocks looking alike and lacking the
surface smoothing of neighbors. The procedural relief's forward-only difference
has a reproducible coordinate-orientation bias: a controlled physical heightfield
encoded with jointly rotated texture, UV and TBN yielded normal
(-0.565685,0.707107,-0.424264) at0degrees but exactly(0,1,0) at180degrees.
Symmetric differences now yield(-0.357771,0.894427,-0.268328) for all four
quarter-turns. Tile edges and transparent neighbors use available opaque samples,
and no neighbor outside the existing16-texel tile is fetched.

Eight production-gradient/output behavior tests cover rotations, flat input,
alpha neighbors/center and tile edges/corners. Their supplied TBN is controlled;
they do not execute Minecraft's derivative-based tangent construction. Actual
Vulkan default-resource capture completes under `relief-after/`, including
Hardware, Off, no-relief and coverage/albedo diagnostics. World/pack/surface GPU
stage timings are recorded separately in its JSON, without a before/after cost
claim. Interior procedural relief uses5 height fetches instead of3; edges use4
and corners3. Authored PBR normals and all material/default parameters are
unchanged. This is a slope calculation repair, not disabling PBR.

## Geometric direct-light horizon repair

A second actual production-path regression establishes direct-light leakage:
an opaque face normal(+X), sun normalize(-0.02,1,0), and perturbed shading normal
normalize(1,0.2,0) produced raster visibility1, diffuse0.176469252 and
specular0.001655646 although the geometric face points away from the light.
Geom-aware irradiance/specular overloads now gate direct light using the actual
face while retaining the perturbed normal's frontside response. Sun and moon
have separate gates, preserving nighttime/twilight moonlight. Existing overloads
remain available with their geometric normal equal to their shading normal.

Seven production-call behavior tests cover backside diffuse/specular, frontside
relief, moon backside, night, twilight, ambient and block light. Full fragment
GLSL/SPIR-V variants compile on Vulkan/OpenGL. Actual `horizon-after/` Vulkan
run completes all Hardware/Off/feature-isolation captures in the owned step
fixture at time5900, including nearly tangent sun on vertical faces. It is visual
execution evidence, not a separate per-pixel assertion of the horizon equations.
Neither this nor the relief
regression alone establishes that every original screenshot artifact is solved;
the owner's complete visual acceptance remains outstanding.

## Verification and limits

- `./gradlew test build`:498 JUnit tests discovered,13 explicitly skipped,
  zero failures/errors after integrating section-cache memoization.
- `python3 -m unittest discover -s tools -p 'test_*.py'`:88 passed. Initial
  run found stale example diagnostic maximum9 vs built-in10; the editable
  example now matches the existing built-in manifest.
- `cargo test`:17 passed,15 explicit GPU tests ignored by default.
- `cargo test surface_gpu -- --ignored --test-threads=1`:15 passed on RX9070XT.
- `cargo build --release`:passed. GLSL/SPIR-V validation and regenerated binary
  identity also checked by independent review.
- Two old GPU fixture mistakes were corrected: external tint material tag belongs
  at float18, not14; an intentionally reflective receiver uses explicit class2,
  not ordinary class1. Production reflection semantics are unchanged.
- Independent scoped review found no Important/Critical issue. Actual mapped
  Minecraft26.3 bytecode confirms both entity/frame mixin hook signatures.

No controlled whole-frame FPS improvement,1%-low result, Android acceptance,
DH occluder fix, complete raster redesign, new presets, water/cloud/footprint
feature or path-tracing implementation is claimed. No installed JAR was replaced,
no commit/push was made, and no Android release was exported.

Desktop build: `build/libs/greenmarine-0.9.11-prototype.jar`. See the artifact
receipt under `build/verification/solstice-repair-2026-11-09/artifacts.json`
for final hashes after the texture-history shader rebuild.
Packaged Linux native bytes match Gradle's explicit
`engine/target/x86_64-unknown-linux-gnu/release/libgreenmarine_engine.so`, SHA256
`21f3a9f977154e8373043b06950dc3e8205a3a9e2fb4c4a32b98b1713a76f7c2`.
The separate target-less `engine/target/release/` build has different bytes and
is not the Gradle packaging input. Probe classes are excluded from the JAR.
