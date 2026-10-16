# Solstice RT

Solstice RT is an independently authored GreenMarine pack with warm sunlight,
cool ambient shadows, cyan water, procedural sky and restrained bloom. It uses
GreenMarine's terrain and surface replay API. The owner-supplied Eclipse Unstable
archive informed the visual direction and controls; no shader code or assets
were copied. Feature coverage does not establish a matching image.

Select **Solstice RT** in GreenMarine's F6 settings. It is built in; the separately
packaged resource archive is an editable distribution of the same pack. Enable
the resource archive and reload resources to override the built-in declaration.
The shaders require GreenMarine's Solstice surface/environment/history additions
and named scene-pass inputs.
They do not load directly in Iris or OptiFine.

Solstice declares `surfaceOnly` so its raster surface rays share the model scene
without duplicating the legacy primary-camera dispatch. Scene shader inputs and
public mod imports are described in [surface rendering](SURFACE-RENDERING.md).

## Materials and atmosphere

The terrain vertex shader preserves Minecraft's chunk interface, lightmap
inputs, camera transforms and multidraw handling. Water uses the host atlas UV
rectangles and world anchored, periodic waves. A shared fragment implementation
keeps alpha tests and material decisions identical between visible terrain,
OIT depth/transmittance/accumulation and the independent surface replay.
Capture outputs world normals and device depth, plus linear tinted albedo and
material class/roughness. Land, exposed wet ground and water use classes 1, 3
and 4. The composition supports class 2 metal from other material producers;
Solstice does not guess metal identities from atlas color.

Water refracts the independent opaque copy, rejects foreground refraction
samples, absorbs red faster than blue and uses Fresnel reflections. When native
surface rays are ready, reflected rays can hit loaded world model geometry,
including objects outside the screen. A miss retains procedural sky reflection.
Water rays require matching captured water class, depth and normal, and fade
at the finite scene boundary; glass captured in front of water retains the sky
fallback. Hardware Off keeps waves, refraction, absorption and sky reflection. Wetness
persists through the host environment input; exposed top faces receive noise
based puddle patches. Puddle reflections use the same hit/miss behavior.

The sky has a height dependent color gradient, warm sun horizon, moon phases,
stars and two moving procedural cloud layers. Procedural horizon haze follows
the submitted sun and weather rather than the host clock fog; composition also
fills dark, depth-zero regions outside the host sky mesh. Minecraft's cloud geometry also
receives weather and day/night coloration. Height fog responds to camera
altitude, rain and thunder; underwater fog has a separate cyan absorption tint.
The dimension input selects Nether/End fog colors. Climate inputs add lightning
brightness and a limited exposed top-face snow overlay. Vanilla precipitation,
entities, block entities, particles, hand and GUI keep their host shaders.

Native surface illumination preserves warm direct sunlight using the sunlight
visibility channel, then adds diffuse irradiance. Its effect fades through the
finite loaded scene boundary. The shader changes irradiance ratios; it never
divides radiance by dark material albedo. Atlas color and vanilla block light
remain readable. The emission control is a bright, strongly block-lit material
accent; native reflections/GI additionally receive real host material emission.
This is not a PBR emission-texture implementation.

## Controls and quality

All 28 controls keep declaration order within the unchanged 32-slot MarineFrame
budget. They update uniforms without rebuilding world pipelines.

| Controls | Purpose |
|---|---|
| `exposure`, `saturation`, `toneCurve` | Exposure and blend between soft Reinhard and filmic tone curves |
| `waves`, `refraction`, `absorption`, `waterOpacity`, `reflection` | Water movement, transmission and surface reflection |
| `sunStrength`, `ambient`, `giStrength`, `rayBlend` | Direct, ambient and native diffuse illumination |
| `fogDensity`, `fogHeight`, `skyBrightness`, `nightBrightness` | Atmosphere and readable night lighting |
| `cloudCoverage`, `cloudDetail`, `cloudSpeed` | Procedural cloud appearance and motion |
| `bloom`, `bloomThreshold` | Restrained highlight bloom |
| `wetness`, `puddles`, `terrainRoughness`, `snow`, `emission` | Material accents and weather response |
| `waveDiagnostic`, `surfaceDiagnostic` | Wave displacement, capture normals (1) or linear albedo (2) |

Low skips bloom. Medium and High use a nine-sample bloom kernel; Ultra uses
25 samples. Cloud noise adds octaves at High and Ultra. The full-resolution
`solstice_scene` pass evaluates linear scene lighting and height fog once into
RGBA16F. Half-resolution bloom reads that retained image through
`CachedColorSampler`, keeping the same threshold, tap spacing and quality
kernel. Composition reads `SolsticeScene` plus the preceding bloom image;
Low reads the same scene cache and skips bloom. Final tone mapping happens once
in composition. Bilinear cache sampling can change bloom samples near surface
edges compared with evaluating lighting and fog independently at each tap;
this removes repeated scene evaluation without promising identical pixels.
The same compose program is the explicit hardware fallback, and readiness
checks prevent sampling missing capture or native output as live surface data.

The final pass opts into `history` and `lightingHistory`, allowing its shader to
transition across native lighting scene publications. Other packs keep the
previous reset behavior unless they explicitly enable `lightingHistory`.
It reprojects captured land and checks current/previous final scene depth,
previous normal/device depth, linear albedo and encoded class/roughness. Opaque
entities in either frame, changed materials, water/wet surfaces and disocclusion
reject accumulation. The previous color fetch uses the validated nearest texel,
so adjacent rejected entity colors cannot enter through bilinear interpolation.
After a scene publication it permits a larger bounded color difference for
45 ms, fades that allowance out by 90 ms, and uses a 22 ms exponential response
based on actual frame elapsed time. Outside that window it clips previous color
to a narrow current-color range. The host invalidates incompatible
history on resource/configuration/view changes. This is bounded temporal
accumulation without projection jitter; it is not jittered TAA or an upscaler.

## Binding contract

The original MarineFrame288, MarineSurfaceFrame128 and RayFrame80 are unchanged.
`environment.glsl` declares MarineEnvironment128 with eight vec4 fields:
Environment, Climate, SkyColor, EnvironmentFogColor, Celestial, Moon, Dimension,
EnvironmentTime. `SkyColor.w` is surface capture readiness. The fog field has a
separate name because Minecraft's fog include already declares FogColor.

`surface_rays.glsl` declares RaySurfaceFrame16 (`SurfaceRayInfo`: ready, width,
height, reach), MarineSurfaceNormalDepth, MarineSurfaceAlbedo, RaySurfaceLighting
and RayReflection. Surface lighting RGB is linear irradiance and alpha is sun
visibility. Reflection RGB is linear hit radiance; alpha is hit validity. Capture
class and roughness are encoded as `class + roughness * 0.5`.

`history.glsl` declares MarineHistoryFrame160: previous projection/view matrices,
current-minus-previous camera delta, and HistoryInfo (color valid, normal valid,
age, elapsed submission seconds). HistorySampler and HistoryNormalDepth hold the
previous final image and captured surfaces. Lighting-history passes also receive
HistoryAlbedoMaterial and HistorySceneDepth for material/entity rejection. Depth clear zero is sky and is never reconstructed
as an infinite world position.

## Verification and limits

`python3 -m unittest discover -s tools -p 'test_*.py' -v` compiles the actual
GLSL/resources against cached Minecraft 26.3 includes. It checks Vulkan and
OpenGL shader stages and SPIR-V validation, classic/multidraw/alpha-cutout/OIT
variants, manifests, parameter limits and declared resource availability.
Offline compilation is separate from actual Vulkan/OpenGL rendering acceptance.
The parent session records GPU captures, readiness and timing evidence separately.

The native scene remains finite loaded baked block-model geometry. Entity and
fluid geometry are not native ray targets; water is a raster ray origin and
reflector. Distant geometry, caustics, multiple transport bounces, physically
volumetric clouds, shadow cascades, PBR texture maps and automatic exposure are
not implemented by this pack. Clouds are procedural plane layers, stars/moon
are analytic, snow is a visual overlay and wetness uses host state. Bloom is a
compact highlight filter. No equivalent-image, full Iris compatibility, jittered
TAA, FSR4 or universal performance claim follows from these features.
