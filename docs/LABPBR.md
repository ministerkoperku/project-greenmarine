# LabPBR resource-pack materials

Solstice reads LabPBR 1.3 `_s.png` and `_n.png` maps next to block textures in
resource packs. For example, `assets/minecraft/textures/block/stone_s.png` changes
stone's material without replacing its colour texture. The maps use the visible
block sprite's UV rectangle, including Minecraft 26.3's atlas padding offset.

- `_s`: red is perceptual smoothness (converted to squared linear roughness),
  green is linear dielectric F0 for 0–229 or metal for 230–255, blue is porosity
  for 0–64, and alpha is emission for 0–254. Alpha 255 means no emission.
- `_n`: red/green encode tangent-space XY in DirectX orientation; blue stores
  ambient occlusion. Normal Z is reconstructed. Alpha/height is preserved but
  displacement and parallax occlusion mapping are not implemented.
- Metal variants currently use albedo as F0; predefined complex metal IORs,
  subsurface scattering and porosity-driven wetness are not implemented.
- Normal maps replace procedural relief only on sprites that supply them.
  Blocks without maps keep builtin profiles or conservative inferred materials and procedural relief.
  Valid all-black specular texels remain material overrides.

Dielectric F0 drives Solstice's sun highlight and reflective material Fresnel.
Existing quality, roughness, reflection and wetness policies still apply. Normal
maps affect raster shading; hardware rays currently use geometric capture normals.
Maps are nearest-resampled to the albedo sprite resolution and use their first
animation frame. Animated material maps, higher-resolution independent material
atlases and PBR on external Kender textures are separate work. `_s` emission is
visible on the shaded block; it does not add new native GI light sources.

The R8 presence atlas uses one byte per block-atlas texel (4 MiB for 2048²).
Specular/normal atlases use four bytes each. CPU atlas images close immediately
after Renderpearl copies their uploads to staging. Without material maps the GPU
bindings use inferred materials for known texture families. Initialized 1×1 placeholders remain available before the atlas is ready.

`surfaceDiagnostic=9` visualizes material presence (red), perceptual smoothness
(green), and metal (blue). In the world pipeline the material inputs are
`MarinePbrSpecular`, `MarinePbrNormal`, and `MarinePbrPresence`; all are initialized.
Presence bit 0 means authored specular, bit 1 means normal, and bit 2 means inferred properties, encoded in an R8 UNORM texel. Inferred materials do not appear as authored maps in diagnostic 9.

Channel conventions follow the [LabPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard).

## Albedo-only packs

Solstice 0.9.9 infers conservative defaults from block sprite names for metal shapes
(iron bars/doors, anvils, chains, copper shapes and oxidation stages), glass,
polished stone, common stone and masonry, wood, wool and concrete. Existing builtin
profiles remain exact. Ores and raw metal blocks remain rough dielectrics; unknown
names retain the original opaque fallback. Names in mod namespaces can match the
same families, so unusual mod texture names may need authored maps.

Inference occupies otherwise unused specular texels with a separate mask flag; it
never overrides a readable `_s` map, including valid black texels. `_n` maps remain
independent. Matte inferred surfaces vary roughness by at most 0.04 with albedo
luminance, bounded to 0.75–0.98; bright pixels never imply metal. Water retains its
existing shader path, and transparent builtin classes retain their profiles.
Existing procedural albedo relief supplies detail when no normal map exists.
This is a heuristic fallback, not generated authored normal maps or POM.

Known inferred textures now require full material atlases even without resource
pack maps: roughly 36 MiB GPU storage at 2048² (specular, normal, presence combined).
Atlas inference runs on atlas rebuild, with no new per-frame CPU scanning.
