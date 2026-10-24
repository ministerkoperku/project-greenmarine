# Solstice RT

Solstice RT is GreenMarine's full pipeline pack: warm sunlight, cool ambient shade,
refractive water, procedural sky and clouds, weather-driven wetness, restrained bloom
and, on Vulkan with hardware ray queries, native ray-traced sun shadows, diffuse GI
and reflections on terrain.

Select **Solstice RT** in GreenMarine settings (F6 or Pause menu → GreenMarine).
Hardware rays need Vulkan (`--graphicsBackend vulkan`) and the **Hardware** or
**Auto** ray mode; Auto keeps rays off below High quality. Without hardware RT,
Solstice still renders its raster materials, water and sky, optionally with local
voxel sun shadows, and reports *Fallback*. The shaders are written for GreenMarine's
API; they do not load in Iris or OptiFine.

## How a frame is built

1. **Material capture.** Terrain is replayed into private buffers: world normal and
   device depth, linear albedo and `materialClass + roughness * 0.5`. External
   renderers can draw into the same pass ([external renderers](EXTERNAL-RENDERERS.md)).
2. **Native surface rays** (hardware RT). For every captured surfel: a sun shadow
   ray, four diffuse GI rays and, on reflective materials, one reflection ray, all
   against the loaded block-model scene, the local player body and any external
   meshes. Output is written at reduced resolution (see quality).
3. **Terrain.** The terrain program composes native irradiance and reflections on
   its own material once, with raster fallbacks where a sample is unavailable.
4. **Background.** Procedural sky and clouds fill clear-depth pixels before
   transparency; vanilla Overworld sky/cloud geometry is suppressed for this pack.
5. **Transparency** (water, glass) with refraction from an opaque copy.
6. **Scene passes.** Linear scene and height fog once into RGBA16F, half-resolution
   bloom, then composition with tone mapping, optional temporal history and dither.

Entities, block entities, particles, rain, hand and GUI keep their host shaders.

## Lighting

- **Sun shadows** come from the native shadow ray. Glass transmits sunlight through
  per-triangle transmission (stained glass attenuates intensity, without colour);
  tinted glass blocks it. Cutout textures (leaves, grass) are alpha tested.
- **Player shadow.** The local player's posed body casts a sun shadow onto terrain,
  in first and third person, alpha tested against the skin. See
  [actor reflections](ACTOR-REFLECTIONS.md).
- **Diffuse GI** uses four fixed cosine-distributed directions around the normal.
  The directions do not depend on position, so lighting is stable while the camera
  moves. Strength is `giStrength`.
- **Ambient occlusion.** The four GI rays also measure local occlusion: a hit closer
  than six blocks darkens sky ambient and, at 60 %, block light. Strength is
  `occlusion`.
- **Volumetric sunlight.** The native pass marches each reduced camera ray to the
  captured surface (at most 48 blocks, the scene region) with one sun shadow ray per
  step: 4, 6, 8 or 12 steps from Low to Ultra, none when `volumetric` is 0. The scene
  pass filters the sunlit fraction over 3x3 reduced texels and adds forward-scattered
  sunlight, so canopies, walls and cave mouths cast visible shafts.
- **Sun highlight and relief.** Every surface receives a GGX sun highlight masked by
  the traced sun visibility (`specular`). `relief` turns atlas luminance into a height
  field inside 16-texel tiles and tilts the shading normal; it fades once a texel is
  smaller than a pixel. Both are procedural, not resource pack material maps.
- **Fallback shadows.** Without hardware RT, the terrain program can trace one sun
  ray through a local 32x32x32 voxel snapshot (Voxel mode, or Auto/Hardware at
  High/Ultra when hardware is unavailable). Full opaque cells occlude; slabs,
  foliage and entities do not; shadows fade over two cells at the grid edge.
- Native lighting fades out at the finite loaded scene boundary. Vanilla block
  light and emissive accents keep their raster contribution.

## Materials and reflections

Materials are identified by block atlas sprite (`BlockMaterials`):

| Class | Material | Examples |
|---|---|---|
| 1 | Opaque (default) | everything without a profile |
| 2 | Metal | iron, gold, copper, netherite blocks |
| 3 | Puddle | wet exposed top faces (runtime) |
| 4 | Water | water |
| 5 | Glass | glass, stained glass, panes |
| 6 | Ice | ice, packed ice, blue ice |
| 7 | Porous | sand, red sand, gravel, dirt, coarse dirt, sandstone |
| 8 | Gem dielectric | diamond block |
| 9 | Coated dielectric | exposed/weathered/oxidized copper and waxed variants |

Exact values: [material extension](MATERIAL-EXTENSION.md). Reflective classes trace a
reflection ray that can hit off-screen geometry, the player and external meshes; a
miss falls back to the procedural sky, faded by skylight so caves do not reflect a
blue sky. Metals tint their Fresnel with the captured albedo; dielectrics use a
neutral F0. Rough reflectors use a wider reconstruction filter (up to 16 taps),
not extra rays. This is a filtered mirror model, not microfacet path tracing.

Water refracts the opaque copy, absorbs red faster than blue, and uses Fresnel
reflections with native hits when available. Rain accumulates wetness and
procedural puddles on exposed top faces; porous materials darken but never become
mirrors. Snow is a top-face overlay; lightning brightens the sky.

## Quality

| Quality | RT output | Reconstruction | Bloom | Clouds |
|---|---|---|---|---|
| Low | ceil(viewport / 4) | up to 4 taps | off | base octaves |
| Medium | ceil(viewport / 4) | up to 4 taps | 9 taps | base octaves |
| High | ceil(viewport / 2) | up to 16 taps on rough reflectors | 9 taps | extra octaves |
| Ultra | ceil(viewport / 2) | up to 16 taps on rough reflectors | 25 taps | extra octaves |

Material capture always runs at full resolution so reduced samples can be validated
against their producing surface (class, roughness, normal, plane). Changing quality
rebuilds the RT targets.

## Controls

32 parameters, in declaration order, update uniforms without rebuilding pipelines.
The pack's Reset button restores defaults.

| Controls | Purpose |
|---|---|
| `exposure`, `saturation`, `toneCurve` | Exposure and blend between soft Reinhard and filmic curves |
| `waves`, `refraction`, `absorption`, `waterOpacity`, `reflection` | Water motion, transmission and reflection strength |
| `sunStrength`, `ambient`, `giStrength`, `rayBlend` | Direct, ambient and native diffuse lighting |
| `fogDensity`, `fogHeight`, `skyBrightness`, `nightBrightness` | Atmosphere and readable nights |
| `cloudCoverage`, `cloudDetail`, `cloudSpeed` | Procedural clouds |
| `bloom`, `bloomThreshold` | Highlight bloom |
| `wetness`, `puddles`, `terrainRoughness`, `snow`, `emission` | Weather response and material accents |
| `volumetric`, `occlusion`, `specular`, `relief` | Ray-traced sun shafts, traced ambient occlusion, sun highlight on every surface, texture relief |
| `weather` | Sky mood: 0 auto (each in-game day clear to overcast), 1 clear, 2 hazy, 3 cloudy, 4 overcast |
| `surfaceDiagnostic` | Debug views: capture normals (1), albedo (2), native ray acceptance (3), traced occlusion (4), sunlit ray fraction (5), raw reflection hits and sun visibility (6) or water wave height (7) |

## Temporal history

The composition pass reprojects captured land from the previous frame and accepts
it only when previous normal, device depth, albedo, material and final scene depth
all agree. Entities, changed materials, water and disocclusion reject history; the
previous colour is fetched from the validated nearest texel. After a lighting
update the accepted colour difference is wider for about 45 ms and fades out by
90 ms. This is bounded accumulation without projection jitter, not TAA or an
upscaler. History resets on resize, resource reload and pack changes.

## Binding contract

`MarineFrame` (288 bytes), `MarineSurfaceFrame` (128) and `RayFrame` (80) are the
standard world-program blocks ([world programs](WORLD-PROGRAMS.md)). Solstice also uses:

- `environment.glsl`: `MarineEnvironment` (128 bytes, eight vec4): Environment,
  Climate, SkyColor, EnvironmentFogColor, Celestial, Moon, Dimension,
  EnvironmentTime. `SkyColor.w` is surface capture readiness.
- `surface_rays.glsl`: `RaySurfaceFrame` (ready, width, height, reach),
  `MarineSurfaceNormalDepth`, `MarineSurfaceAlbedo`, `RaySurfaceLighting` (linear
  irradiance rgb, sun visibility a) and `RayReflection` (linear radiance rgb, hit a).
- `history.glsl`: `MarineHistoryFrame` (160 bytes) with previous projection/view,
  camera delta and validity, plus the previous image, captures and scene depth.

`surfaceBeforeTerrain` on the terrain program selects early capture; reusing the
shipped terrain/surface shader pair enables it by default, so renamed copies of the
pack work unchanged. Depth zero is sky (reversed-Z) and is never reconstructed into
a world position. See [surface rendering](SURFACE-RENDERING.md) for mod access.

## Limits

- The native scene is the loaded baked block-model geometry around the player,
  the local player body and registered external meshes. Other entities, fluids as
  geometry and distant terrain are not ray targets.
- No caustics, multi-bounce transport, volumetric clouds or shadow cascades.
  Clouds are procedural layers; stars and moon are analytic.
- [LabPBR block maps](LABPBR.md) supply smoothness, F0, normal/AO and emission;
  animated material maps, POM and external Kender texture maps remain separate work.
- The material library covers the blocks listed above; other blocks are opaque.
- Performance depends heavily on the GPU; measure at a fixed pose, resolution and
  quality with profiling enabled (F6) rather than relying on these descriptions.
