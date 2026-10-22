# Solstice RT

## 0.9.3 partial producer support and copper patina

Reconstruction now tracks accepted sample weight against the entire intended
footprint. A small remaining producer no longer becomes full-strength native
lighting/reflection just before it disappears. Surface-local composition applies
this support once to native coverage, retaining normalized native radiance and
sun visibility. The old six-argument `solSurfaceRays` include function remains;
its hit validity includes support. A seven-argument overload returns support
separately for callers that blend native lighting and reflection together.
The fully represented interior response, output dimensions, ray/tap budgets and
frame layouts remain unchanged. The actual GLSL partial-footprint fixture passed
five subpixel reconstruction phases. This addresses a verified edge-popping
contributor, not every possible temporal aliasing or gameplay movement artifact.

Exposed, weathered and oxidized full copper cubes now use explicit coated
dielectric profiles, with increasing roughness and neutral 0.04 F0. Waxed IDs
reuse their stage's actual vanilla texture/profile. Patina is not treated as a
bright green conductor. See [material extension](MATERIAL-EXTENSION.md) for the
exact profile values, supported textures and remaining builtin-library limits.

The local-player path now optionally captures KAvatar's actual posed cube body
and texture instead of always substituting vanilla geometry. Tested KAvatar
0.3.0 Hyper Chibi/Baby bodies fit the unchanged 16-box, one-texture actor budget.
Unsupported custom bodies/accessories have explicit capture diagnostics. See
[actor reflections](ACTOR-REFLECTIONS.md) for precise compatibility and limits.

## 0.9.2 surface-local native reflections and local player

Terrain capture and native tracing now finish before opaque terrain color draws.
The material shader applies native irradiance to the diffuse lighting and blends
the traced reflection with its gated sky fallback once. Block light and emission
retain their raster contributions; the final scene pass no longer multiplies an
already reflected image or adds another reflection overlay. A cleared private
capture depth avoids importing a primary entity silhouette into the ray origins.
Water keeps its existing surface-local reflection and refraction path.

The optional native [actor extension](ACTOR-REFLECTIONS.md) can hit the local
player's animated vanilla body and enabled outer skin layers, including in first
person. It uses the actual skin UVs, slim/wide models, vanilla root transforms,
skin alpha and nearest-block occlusion. Armor, capes, held items and other entities
are not actor targets. The player does not cast native sunlight shadows or enter
diffuse GI. Actor capture is optional; its status/reason appears in surface RT
readiness details. A missing actor extension keeps the legacy surface tracer.

`surfaceBeforeTerrain` is an optional terrain program flag, independent of pack
identity. Reusing the shipped Solstice terrain/surface pair selects it by default,
so renamed external packs still work. Other existing shaders retain late capture;
custom shaders that compose native output while drawing terrain must opt in.
All old constructors, core frame sizes and version-1 native entrypoints remain.

The local player extension requires active hardware surface RT. The raster-only
fallback remains an environment reflection and does not gain a full body mirror.
Ray/output/tap budgets are unchanged, but player intersections add bounded work
on reflective native pixels. No new Android FPS or gameplay acceptance is claimed.

## 0.9.1 reflection reconstruction and materials

Reduced native ray samples now interpolate from their actual full-resolution
capture texel centres. Surface matching accounts for the elongated pixel
footprint at grazing incidence, while retaining normal, class, roughness and
plane rejection. This corrects holes on a controlled near oblique plane without
increasing ray counts, reconstruction tap budgets or output resolution.

Metal and solid gemstone reflection weights use Fresnel without a second
roughness energy multiplier; roughness still controls the bounded reconstruction
filter. Metallic F0 uses linear captured albedo as an approximation, retaining
its color. This remains a filtered mirror model, not microfacet path tracing.
Water reflection energy is unchanged. Captured translucent surfaces now respect
closer final scene depth instead of unconditionally covering foreground objects.

Diamond and netherite blocks have explicit atlas profiles through the separate
[`MarineMaterialExtension`](MATERIAL-EXTENSION.md). Diamond uses solid dielectric
class 8 (F0 0.17), not transmission or caustics. Netherite uses rough metal class 2.
The original 32-profile block, public frame layouts and native ABI remain intact.

The actual reconstruction GLSL passed an offscreen desktop GL fixture at three
distances, three slopes and both native output divisors, plus a distinct-color
closer parallel surface rejection case. These controlled checks establish the
reconstruction defect and its correction, not Android gameplay appearance,
frame-rate improvements or absence of every source of reflection flicker.

## 0.8.9 quality budgets and redundant work

Solstice's native surface-ray output now follows quality. Capture stays at full
resolution to preserve producing-surface and material/normal/plane validation.
Other packs retain their previous half-resolution native output.

| Quality | RT output width/height | 1920x1080 output pixels | Reconstruction footprint |
|---|---|---:|---|
| Low / Medium | ceil(viewport / 4) | 129600 | Up to 4 taps |
| High / Ultra | ceil(viewport / 2) | 518400 | Up to 16 taps for rough reflectors |

This is a 75% reduction in Medium's dispatched output pixels and its two owned
output images relative to the former half-size dispatch. It is a quality/work
budget, not a measured 75% GPU-time reduction or a game FPS guarantee. Lower ray
resolution can soften small reflections; missing incompatible samples keep the
raster fallback. These settings affect a running hardware surface path; Auto's
existing policy still turns rays off below High. A ray-free Medium session does
not acquire a new hardware path from this update.

Ordinary and porous land skip unused ray-reflection texture reads. Dry, vertical
or dark surfaces skip zero-contribution puddle noise. Fully filtered cloud
harmonics skip noise evaluation while preserving the filtered result. Temporal
material capture remains enabled because history still uses it without RT.

Native target allocation logs record pack, quality, output and capture dimensions
for subsequent performance reports. Compare at the same pose, world/weather,
resolution, effective ray mode and warmup; switching quality rebuilds resources.
No local Android gameplay benchmark reproduces the reported Medium/High FPS
inversion yet. Controlled work-count and offscreen sky fixtures are separate
from game CPU/GPU timings.

## 0.8.7 visual corrections

Sand, red sand, gravel, dirt, coarse dirt and sandstone use a porous material
profile (class 7). Rain can darken them but cannot turn them into mirror puddles.
Sandstone top/bottom sprites share that profile. Other unknown materials retain
the default opaque response; this is a bounded builtin library, not universal PBR.

Cloud lattice noise repeats at the 4096-block floating-origin boundary. Integer
harmonic octaves and a pixel-footprint filter avoid jumping noise and unresolved
high-frequency detail near the horizon. Subpixel, zero-mean ordered dithering at
the sky and final output boundaries reduces 8-bit gradient banding.

Rough metal, ice and puddle reflections use a bounded reconstruction footprint
(up to 16 taps rather than 4), retaining material/normal/plane rejection. Diffuse
lighting keeps its original footprint. No extra native rays are issued; the extra
texture work on rough reflective pixels has not been benchmarked on the owner's
Android GPU. This is reconstruction filtering, not a stochastic rough-ray model.

The green diagnostic light no longer spawns automatically when entering a world.
F7 still places it manually. Authors can opt into automatic placement with
`"automaticDemoLight": true` in `config/greenmarine.json`; existing constructors
remain available. Old configuration files default this missing option to false.

The controlled desktop GL fixture verifies sky math and origin continuity, not
Minecraft gameplay or Vulkan acceptance. Fresh Android captures are still needed
for the precise sand/near-block artifact and overall aesthetic assessment.

## 0.8.6 single cloud layer

Procedural sky/clouds now fill clear-depth background pixels in one explicit
pass after opaque terrain and before transparency. Host cloud geometry and the
old Overworld sky mesh are suppressed for this pack, so vanilla cloud cubes do
not overlap the procedural layers. Nether/End sky behavior is preserved.

The old post-process brightness threshold used to refill missing sky is removed.
Sky coverage is determined by depth, covering the full clear background rather
than the host sky mesh shape or its existing color. Water/transparent features are
rendered afterwards and retain their composition over the sky. Minecraft cloud
options and other shaderpacks are not changed. The owner-reported rectangular
artifact still needs a new gameplay capture to verify its exact appearance.

## 0.8.5 reflection correction

Metal Fresnel now uses the captured linear albedo per channel, preserving a warm
copper response instead of applying one neutral white coefficient to every metal.
This remains an approximate material model, not measured optical constants.

Reduced-resolution lighting/reflection texels are reconstructed from their actual
full-resolution capture centres. Each weighted sample must match the current
material class, roughness, normal and world plane; incompatible foreground/edge
samples are rejected. Compatible samples are renormalized, and unavailable samples
retain the raster result. Reflection hit colors are unpremultiplied before blending
their coverage. Opaque visibility uses a tighter depth check. Existing water,
glass and ice depth handling remains distinct from opaque surfaces.

The owner's near-block report occurs in first person. No fresh capture of that
specific artifact is available yet; source/compilation tests do not establish a
live visual fix. The native scene still does not contain a skinned player/entity
mesh, so offscreen avatar reflections and animated avatar RT shadows are not added
by this correction. A screen-depth silhouette is not such geometry.

## 0.8.4 material and fallback updates

Solstice has accumulated rain wetness and procedural puddles; it does not make
every block wet in clear weather. Reflection defaults are reduced from 1 to 0.55.
Existing saved parameter overrides remain; use the pack's Reset button if testing
the new defaults against an older personal configuration.

The new bounded builtin material library distinguishes glass/stained glass, ice/
packed ice/blue ice and iron/gold/copper blocks. Unknown blocks are rough and
nonmetallic. Material responses are identified by atlas sprites; custom packs
that reuse one sprite for different blocks share its raster material. This is
not a complete PBR texture-map system or a physically accurate rough-reflection
integrator. Fire/lava emissive block geometry can appear in reflections, but
animated fire particles/entities are not native secondary geometry.

Glass transmits direct RT sunlight through per-triangle throughput metadata;
tinted glass blocks it. Stained glass currently attenuates intensity without
colored transmitted light. This is not colored caustics, light transport through
arbitrary layered materials, or a new artificial point-light transmission model.

The terrain program explicitly opts into local voxel sun-shadow fallback. Select
Voxel, or use Hardware/Auto at High/Ultra when hardware RT is unavailable. Rays
Off continues to disable ray shadows. A ready 32x32x32 voxel snapshot supplies
one bounded solar visibility trace; shadows fade over two cells at its edge.
Full opaque block cells and tinted glass occlude; non-solid emissive cells do
not become full cube shadow casters. Slabs, foliage, entities and distant shadow
cascades are outside this fallback's geometry contract. Snapshots are incremental
and can briefly lag edits. This fallback does not require the native library.

Synthetic sky reflections now fade with local skylight, so a zero-skylight cave
does not receive a procedural blue-sky reflection. Actual geometry-ray hits retain
their colors. This skylight heuristic is not directional sky visibility for every
possible cave opening. RT no longer dispatches unused reflection queries for dry
ordinary opaque material pixels; this is a work reduction, not a measured FPS claim.

World programs receive `MarineMaterialFrame` (1040 bytes): 32 UV rectangle vec4s,
32 property vec4s (roughness, reflectance, sunlight transmission, class), then one
count vec4. `BlockMaterials` documents the Java layout. Terrain classes 1/2/3/4
retain opaque/metal/puddle/water meanings; 5/6 add glass/ice. Triangle metadata
remains 96 bytes and the formerly reserved final float carries sunlight
transmission. Legacy builder overloads emit zero transmission. `voxelShadows`
is opt-in and old world-program constructors retain their prior policy.

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
material class/roughness. Classes are 1 ordinary land, 2 metal, 3 puddles,
4 water, 5 glass, 6 ice and 7 porous land. The bounded builtin atlas library
identifies supported materials by sprite identity rather than atlas color.

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
the submitted sun and weather rather than the host clock fog. An explicit
clear-depth background pass supplies the procedural sky before transparency,
with host Overworld cloud geometry suppressed for this pack. Height fog responds to camera
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

The native scene remains finite loaded baked block-model geometry plus the optional
local player skin boxes described above. Other entities and
fluid geometry are not native ray targets; water is a raster ray origin and
reflector. Distant geometry, caustics, multiple transport bounces, physically
volumetric clouds, shadow cascades, PBR texture maps and automatic exposure are
not implemented by this pack. Clouds are procedural plane layers, stars/moon
are analytic, snow is a visual overlay and wetness uses host state. Bloom is a
compact highlight filter. No equivalent-image, full Iris compatibility, jittered
TAA, FSR4 or universal performance claim follows from these features.
