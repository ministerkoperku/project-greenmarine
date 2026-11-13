# Intrinsic surface emission repair

The default material path had no intrinsic emission definition for lava sprites.
`solstice_terrain.glsl` instead guessed emission from
`pow(surfaceLight.x,9) * max(luma(base)-.35,0)`. This is incoming illumination,
not a source property: ordinary bright sand receiving full block light acquired
emission, whereas dark lava texels had zero emission. If local traced lighting
replaces the unshadowed lightmap, the latter has no source term left to keep it
bright.

`tools/test_solstice_emission.py` executes the exact production emission statements
in a controlled C++ scalar fixture. Its first valid RED failed because bright,
nonemissive sand emitted merely from receiving block light. After the repair the
same test passes and also checks authored emission independent of incident light
and albedo, plus positive raw-addon source emission on dark texels.

`BuiltinEmissions` supplies intrinsic emission for vanilla lava still/flow, fire,
soul fire, glowstone, sea lantern and shroomlight sprites. These are full source
sprites; mixed torch/lantern housing textures receive no blanket source mask.
The existing PbrAtlas raw-addon payload carries roughness .96, reflection zero,
emission one, and opaque surface class one. This preserves the existing material
and normal/reflection APIs and requires no uniform ABI expansion or atlas table
slots. Authored LabPBR maps prevent the builtin fallback; authored addon pixels
can replace it. Terrain now uses actual authored/builtin source emission without
the incident-light guess.

Checks performed without launching Gradle/Minecraft:

- Production emission statements: PASS after observed behavioral RED.
- Standalone javac/java on the actual BuiltinEmissions class: both lava sprite
  identities, roughness, no reflection, emission, and ordinary/mixed/custom sprite
  rejection pass.
- Existing scalar production reflection policy regression: PASS.
- New `BuiltinEmissionsTest` is ready for the root agent's coordinated Gradle run.

No runtime lava acceptance screenshot has been produced by this bounded task.
Mixed torch/lantern texels require authored per-texel masks for intrinsic glow;
their world illumination remains handled by traced/block lights.

Ordinary-terrain reflection investigation found sand, red sand, dirt, gravel and
sandstone already have explicit class seven, zero reflectance and .99 roughness;
class seven suppresses puddles and native reflections. The builtin atlas table
contains exactly 32 entries within its 32-entry budget. No blanket reflection or
PBR disable was made. A runtime class/puddle readback is needed to distinguish a
binding/classification error from the separately investigated lighting/shadow
reconstruction artifact in the owner's screenshot.
