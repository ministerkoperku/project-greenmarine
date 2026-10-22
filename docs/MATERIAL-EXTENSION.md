# Additional block material profiles

The existing `MarineMaterialFrame` contract remains 1040 bytes: 32 atlas UV
rectangles at offset 0, 32 material property vectors at offset 512, and a count
vector at offset 1024. `BlockMaterials.atlasProfiles()` and `writeAtlas()` retain
the original 32 profiles and layout.

New profiles use a separate std140 `MarineMaterialExtension` uniform block:

| Field | Offset | Size |
| --- | ---: | ---: |
| `AdditionalMaterialRects[8]` | 0 | 128 bytes |
| `AdditionalMaterialProperties[8]` | 128 | 128 bytes |
| `AdditionalMaterialInfo` | 256 | 16 bytes |

`BlockMaterials.additionalAtlasProfiles()` supplies only the additional profiles;
`writeAtlasExtension()` writes the 272-byte block. Properties remain roughness,
reflection weight, sunlight transmission and surface class. Writers clear all
unused entries and count padding, skip missing or empty atlas rectangles, and
reject non-finite or malformed rectangles and undersized storage. World programs
upload actual block-atlas UV bounds, bind both blocks and release both buffers on
pack/resource reset. The additional binding follows existing world uniform slots.
`materials.glsl` checks the original profiles first, then up to eight extension
profiles, and retains the rough opaque fallback when neither matches.

Diamond blocks use a solid gemstone dielectric class (8), roughness 0.22 and
reflection weight 0.35. They do not transmit sunlight. Netherite blocks use metal
class 2, roughness 0.65 and reflection weight 0.55, with no transmission. The
native surface reflection dispatcher accepts class 8 alongside existing reflective
classes. Water profiles and the original atlas are unchanged. These classifications
use the rendered block atlas UV rectangles, rather than block identifiers alone.

Tests cover serialization of every additional profile, unavailable sprites,
initialization/validation, legacy atlas compatibility and pipeline binding conflicts.
Compilation and serialization tests do not establish visual quality on a running
Vulkan or OpenGL client.

Copper oxidation stages use three additional atlas entries, bringing extension
usage to five of eight slots. Exposed, weathered and fully oxidized full copper
blocks use coated dielectric class 9, rather than tinting metallic F0 with the
green patina albedo. Their builtin roughness/reflection-weight approximations are
0.60/0.35, 0.76/0.22 and 0.90/0.12 respectively; sunlight transmission is zero.
Class 9 uses a neutral 0.04 normal-incidence Fresnel value and retains the ordinary
reflection-weight and roughness attenuation. Fully oxidized copper therefore
remains matte. Unoxidized copper keeps its existing metal profile.

Vanilla waxed full copper cubes reference the same models and textures as their
unwaxed stages. Their block IDs alias the corresponding stage profile without
adding duplicate atlas entries. The tests follow actual Minecraft
26.3-snapshot-9 blockstate/model JSON fixtures to each texture ID and verify that
its UV rectangle serializes the expected material class and properties. This
coverage applies to the four full-cube textures; cut/chiseled copper, grates,
trapdoors, doors, bulbs and other distinct copper textures are not added here.
Neither uniform layout changes. These profiles are builtin approximations, not
measured optical material data or a claim that movement artifacts are resolved.
