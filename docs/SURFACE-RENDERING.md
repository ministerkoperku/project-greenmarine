# Surface rendering API (0.7 prototype)

This adds material capture, environment and temporal pass inputs to schema1.
Existing constructors, shader sources, MarineFrame288, core ABI1 and legacy RT
ABI1 remain usable. Surface RT has a separate optional ABI version1.

[Dynamic ray scenes](DYNAMIC-RAY-SCENES.md) describes per-frame geometry updates,
bounded preparation and GPU submission ownership.

```json
{"target":"terrain","vertex":"example:core/terrain",
 "fragment":"example:core/terrain","surface":"example:core/materials",
 "worldRays":true,"surfaceOnly":true}
```

`surface` is an explicit fragment resource, snapshotted with its includes during
atomic reload. Initially it is supported only on terrain. Its vertex interface
matches the visible program. Output location0 is RGBA32F: world-space unit normal
and device depth. Location1 is RGBA16F: linear biome-tinted albedo and
`materialClass + roughness * 0.5`. Classes: 1 diffuse, 2 metal, 3 wet, 4 water.
The shader must use identical vertex displacement, alpha cutout and material
decisions. Zero normals denote missing data; reversed depth0 denotes sky.

GreenMarine copies opaque depth to a private depth image and replays opaque then
transparent terrain with GEQUAL depth writes and unblended attachments. Entities
occlude the replay through the copied depth but are not material replay producers.
Unknown host geometry and wireframe capture are unsupported. Capture shaders must
not sample their active output attachments. Visible water must validate matching
capture material, normal and depth before consuming an RT reflection.

`surfaceOnly` explicitly skips the legacy primary-camera ray dispatch. It requires
a ray-enabled surface program and defaults false in all preserved constructors.
Use it when the pack consumes raster surface rays exclusively. Legacy world-ray
images then remain initialized and unavailable; the surface images have separate
readiness. This avoids running two independent primary-lighting pipelines.

## Initialized shader inputs

All scene and decorated world programs receive the following additional inputs;
lightmap shaders retain their original restricted bindings.

| Binding | Meaning |
|---|---|
| `MarineSurfaceFrame` | Existing 128-byte camera/sun/water-atlas block, also available to scene shaders |
| `MarineEnvironment` | Eight vec4 values, 128-byte std140 block below |
| `MarineSurfaceNormalDepth` | Full-resolution nearest captured terrain normal/device depth |
| `MarineSurfaceAlbedo` | Full-resolution linear tinted albedo/material/roughness |
| `RaySurfaceFrame` | vec4 SurfaceRayInfo: actual output readiness, width, height, maximum ray reach |
| `RaySurfaceLighting` | Half-resolution RGB irradiance, alpha direct sunlight visibility |
| `RayReflection` | Half-resolution RGB traced hit radiance, alpha hit1/miss0 |

```glsl
layout(std140) uniform MarineEnvironment {
    vec4 Environment;       // camera altitude, rain, persistent wetness, underwater
    vec4 Climate;           // biome temperature, snow precipitation, lightning, thunder
    vec4 SkyColor;          // host sky RGB, current surface capture readiness
    vec4 EnvironmentFogColor;
    vec4 Celestial;         // moon phase, moon angle, day index, sun angle
    vec4 Moon;              // world direction XYZ, visible strength
    vec4 Dimension;         // Overworld, Nether, End, procedural sky support
    vec4 EnvironmentTime;   // seconds, day fraction, frame marker, paused
};
```

Capture readiness is independent of RT readiness. Gate surface samples on
`SkyColor.w`; when unavailable their initialized fallback normal/depth source is
not a valid raster depth producer. Native RT readiness uses `SurfaceRayInfo.x`.
Environment updates even for scene-only packs. Wetness advances once per game
tick and dries gradually; paused frames do not advance it. Lightning respects the
host option to hide lightning flashes.

Java rendering contributions import `surfaceNormalDepth`, `surfaceAlbedoMaterial`,
`surfaceLighting`, `surfaceReflection` from FrameContext. `surfacesReady()` and
`surfaceRaysReady()` are callback-scoped flags; old FrameContext constructors
initialize both false. Imported textures are borrowed and must not be closed.
Existing `worldRaysReady()` remains specific to the legacy primary-ray images.

## RT scope and fallback

Raster surface origins include water and bob/hurt camera transforms. Vulkan Ray
Query traces actual baked model triangles with atlas alpha, biome tint and host
emission, sharing the existing BLAS/TLAS. Reflection rays can hit offscreen models;
the ray scene does not contain fluid/entity geometry as secondary hit producers.
The loaded scene is finite (96x64x96, anchored to a16-block grid), so shaderpacks
must fade native contributions toward a consistent global raster baseline.

Hardware initialization requires enabled host features, successful native pipeline
creation and submitted output. OpenGL/Hardware Off keep the raster material and
water paths. Missing optional native surface symbols or an unavailable surface
pipeline report Fallback, preserving the pack's raster rendering. Broken pack
GLSL reports Failed and restores the host world shader. F6/chat expose the state.

Temporal history is opt-in per scene pass; see [shaderpack contract](SHADERPACKS.md).
The delivered filter reprojects prior output and rejects inconsistent surfaces;
it is not jittered TAA. No Iris pack-format compatibility or full feature parity
is claimed. FSR4 remains deferred.
