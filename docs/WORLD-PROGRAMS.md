# World shader programs (0.6)

## Consistent scene depth (0.8.8)

Enabled GreenMarine effects request Minecraft's `consistentDepthRequired` flag
before `LevelRenderer` constructs its frame graph. Always-on-top gizmos then
render into Minecraft's separate gizmo depth attachment and integrate it, rather
than clearing world depth before GreenMarine post processing. This applies to
any gizmo producer, including connector overlays, without linking its mod/API.
Existing host requirements remain enabled when GreenMarine is toggled off.
Minecraft retains ownership of all gizmo targets and their frame-graph lifetime.

Regression tests cover the enable/disable/re-enable matrix and verify the mixin
selector/argument against actual 26.3 bytecode. The owner's complete camera-motion
artifact still requires gameplay confirmation; these tests do not render a game.

## Background stage (0.8.6)

An optional `{"target":"background","vertex":"demo:core/background","fragment":"demo:core/background"}`
program draws a fullscreen triangle after opaque geometry and before deferred
classic/OIT transparency. Its color target is the actual host format. Reversed
depth uses EQUAL against clear depth zero, without depth writes or attachment
clears, so opaque terrain/entities remain intact. The vertex program must emit
clip Z 0 for zero-to-one hosts and -1 otherwise (`Context.x`), with W 1.
The host supplies `MarineFrame`, `MarineSurfaceFrame` and `MarineEnvironment`.
This stage has no sampled color/depth inputs and rejects ray/surface-capture flags.
Resource ownership and failure/reset behavior follow the world-program lifecycle.

Solstice uses this stage for its single procedural sky/cloud layer. Its original
Overworld sky/cloud fragments discard host geometry; other packs and global
Minecraft cloud settings are unaffected. The scene pass no longer decides sky
coverage from pixel brightness. Custom background shaders should explicitly gate
dimensions in which they do not replace the sky.

Schema 1 now optionally declares actual world vertex/fragment programs alongside
its existing fullscreen and lightmap passes. The original `ShaderPack` constructor,
parameter order, MarineFrame 288-byte block, existing packs and native ABI remain
available. Base GreenMarine remains standalone; Fullpack is optional.

```json
{
  "schema": 1,
  "id": "example:water",
  "parameters": {"waves": {"default": 1, "min": 0, "max": 2}},
  "passes": [],
  "programs": [
    {"target": "terrain", "vertex": "example:core/terrain",
     "fragment": "example:core/terrain", "worldRays": true}
  ]
}
```

Resources resolve to `assets/example/shaders/core/terrain.vsh` and `.fsh`.
Vertex is optional: omission retains the Minecraft vertex program. Includes use
namespaced `#include` identifiers and are captured together with explicit pack
sources when the catalog reload succeeds. A failed catalog reload keeps the last
valid snapshot. Java mods can register `PackCatalog.LoadedPack` through
`GreenMarine.PACKS.register` and close the registration on removal. Vertex snapshots
use `vertex|<id>` keys; fragment keys retain their original names. `ShaderProgram.fromPack`
and `ProgramSources` support stage-aware lookup even when the two stages share an id.

## Host interface and pipeline state

Targets are `terrain`, `block`, `entity`, `sky`, `clouds`, `weather`, `particles`.
They select known Minecraft shader families during world rendering. Weather is
identified by pipeline location because rain/snow shares the particle shader.
Unknown/custom pipelines are preserved. GUI and hand rendering after the world
keep their original programs; this is not an entity-material registry for every mod.
Terrain, sky and clouds are exercised by the delivered example. Block/entity/weather/
particle targets have routing support; independent example packs for all their
variant interfaces have not been accepted in gameplay.

A replacement must implement the selected host's shader interface: vertex attributes,
varying locations, existing uniforms, fragment attachments and preprocessor defines.
GreenMarine clones vertex buffer slots (including sparse multidraw slots), topology,
depth/stencil, culling, polygon mode, color formats/write masks, blending, push constant
size, defines and bind groups. Extra bindings append after existing flattened uniform
slots. Shader-only changes do not replace Minecraft's chunk meshes, sorting or draw
submission. `WorldPipeline.decorate` is public for host-state-preserving declarations.

Terrain programs must handle `MULTIDRAW_TERRAIN`, `ALPHA_CUTOUT` and all OIT defines.
Water displacement and alpha must agree in the alpha/depth-bounds and accumulation
phases. Seabreeze retains Minecraft's OIT and fog include functions for those phases.
The current host uses reversed depth: zero is the far/sky clear. Avoid reconstructing
that sentinel into an infinite world position.

## Automatic world inputs

All world variants receive initialized bindings, including unavailable ray/voxel data:

| Binding | Meaning |
|---|---|
| `MarineFrame` | Original 288-byte std140 frame, inverse submitted projection/view and ordered pack parameters |
| `MarineSurfaceFrame` | 128-byte std140 world/material block described below |
| `MarineOpaqueColor`, `MarineOpaqueDepth` | Independent full-resolution copies taken after opaque world rendering and before transparency |
| `RayFrame`, `RayLighting`, `RayNormalDepth`, `RayAlbedo` | Current-frame hardware inputs, or matching initialized zero placeholders |
| `VoxelFrame`, `VoxelMaterials` | Selected voxel snapshot/materials, or initialized unavailable data |

Include `greenmarine:surface.glsl`, `greenmarine:rays.glsl` or `greenmarine:voxel.glsl`
for their declarations. Surface has eight vec4 slots:

| Byte offset | Value |
|---|---|
| 0 | `SurfaceInfo`: opaque readiness, rain amount, two reserved values |
| 16 | `SurfaceOrigin`: physical camera world position modulo 4096, reserved |
| 32 | `SurfaceSun`: world-space direction xyz, light strength w |
| 48 | `SurfaceSky`: linear approximate sky RGB, rain brightness w |
| 64, 80, 96 | `SurfaceWater[3]`: still/flow/overlay atlas UV rectangles `(u0,v0,u1,v1)` |
| 112 | `SurfaceEye`: relative perspective eye offset from bob/hurt projection, reserved |

Opaque inputs are unready during opaque draws; never sample them without checking
`SurfaceInfo.x`. They become ready before classic transparency and OIT. Copies use
actual host formats and dimensions; resize/reload/pack switches retire owned images.
The classic path closes the original opaque pass, copies outside it, then draws
transparency in a separate pass with preserved color/depth and default uniforms.
OIT already has a corresponding boundary. No shader samples its active framebuffer.

RT dispatch now occurs before world rasterization using the actual submitted camera
projection. `RayLighting` is **linear irradiance**, not already shaded scene color.
Multiply material albedo by irradiance; adding it directly creates white surfaces.
Do not divide by dark albedo. Match normal/depth world planes before using local rays,
and use a shared lighting baseline with a fade at the finite scene boundary.
Night/weather now affect ambient and non-emitter indirect sky illumination.

## Examples and readiness

**Seabreeze World Shaders** is built in and available as
`build/shaderpacks/seabreeze-world-shaders-0.6.0-prototype.zip`.
It changes actual terrain shading, water vertices, refraction, absorption, Fresnel
sky/sun reflections, sky and clouds. Waves use world coordinates with periodic origin
wrapping; camera movement does not attach the wave pattern to the player. Controls
update uniforms without recompiling pipelines. The optional `waveDiagnostic` control
shows the interpolated vertex displacement. Fluid waves are restricted to
transparency, so opaque sprite neighbours preserve their geometry. Reflections are sky approximations;
there are no traced water reflection/refraction rays or physical caustics.

**Prismarine Showcase Corrected** is a separate resource pack in
`build/shaderpacks/prismarine-showcase-corrected-0.6.0-prototype.zip`. It preserves
full-resolution scene detail while applying half-resolution irradiance, retains voxel
GI/bloom fallbacks, and removes camera-orbiting Lua lights. Its shader resources use
`prismarine_fixed`, so the owner's original 0.4 ZIP is unchanged and can coexist.
It is a fullscreen example; use Seabreeze for world/material programs.

F6/chat report Loading, Loaded, Fallback or Failed. World programs only count as
submitted after a real draw is queued and the host encoder submits it. Compilation
failure restores original pipelines and reports Failed; reload/switch permits recovery.
Some packs deliberately choose complementary fallback passes; Fallback still means
the compatible passes loaded, and the detail states whether hardware RT is active.
Unavailable hardware or Rays Off keeps Seabreeze's material programs with raster
lighting; it does not claim to execute voxel rays it never samples.

These are GreenMarine-format shaders, not direct Iris/OptiFine shaderpack compatibility
or feature parity. Existing contributed mesh/material/render-graph APIs continue to
work independently; see [rendering API](RENDERING-API.md). Native RT still has a finite
loaded model scene and does not include fluids/entities, biome-tinted RT albedo,
PBR textures, temporal history or distant shadow cascades. FSR4 remains deferred.

Verification and measured scopes are recorded in
[world-program evidence](../development/evidence/world-programs/README.md).

## Opt-in material capture and surface rays

Schema1 optionally adds a terrain `surface` fragment and `surfaceOnly` tracing
selection. Replay targets, environment inputs, independent readiness, shader
layouts and the finite native scene are documented in
[surface rendering](SURFACE-RENDERING.md). Solstice RT demonstrates this contract;
existing Seabreeze and other world programs keep their old behavior.

`surfaceBeforeTerrain: true` optionally moves that program's replay and native
surface dispatch before opaque terrain color drawing. Replay clears its private
depth rather than copying entity visibility from the main image. This allows
surface-local native shading and avoids late reflection composition over entities.
The flag requires a terrain `surface` program. Old constructors and manifests
retain late capture except the shipped `greenmarine:core/solstice_terrain` and
`greenmarine:core/solstice_surface` pair, which defaults to early capture regardless
of pack ID. Custom shaders reusing the surface-local include under their own
resource names should opt in explicitly. The 8-argument `PackProgram` constructor
also accepts the flag; all prior constructor signatures remain available.
