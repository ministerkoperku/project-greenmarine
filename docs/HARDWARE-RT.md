# Hardware ray tracing

GreenMarine traces real Vulkan ray queries against the loaded world, recorded into
Minecraft's own command stream on Minecraft's own device. Nothing is screen-space:
hits can be off-screen, behind the camera or occluded in the image.

## Requirements and status

- Vulkan backend: add `--graphicsBackend vulkan` to the **game** arguments.
  Minecraft picks its device before mods load; GreenMarine never switches it.
- A GPU and driver exposing `VK_KHR_acceleration_structure` and
  `VK_KHR_ray_query`. GreenMarine asks Minecraft to enable them when available.
- The bundled native engine for the platform (Linux x86_64, Linux ARM64, Android
  ARM64) and `--enable-native-access=ALL-UNNAMED` in the **JVM** arguments.
- Ray mode **Hardware**, or **Auto** at High/Ultra quality, and a pack that
  declares hardware passes (Solstice RT).

A capable GPU alone is not enough: the host device, enabled features, native
pipeline and current scene must all be ready. F6 and chat report
Loading / Loaded / Fallback / Failed with a reason; *Loaded* means the selected
programs actually submitted with ready RT data this frame. OpenGL, Rays Off, a
missing native library or unsupported features select the pack's declared
fallback and report *Fallback*.

Ray modes:

| Mode | Behaviour |
|---|---|
| Off | No ray work |
| Auto | Off below High; at High/Ultra uses hardware when ready, else voxel |
| Voxel | Shader DDA through a local 32³ occupancy grid, both backends, no native code |
| Hardware | Native Vulkan ray queries; falls back when unavailable |

## The scene

Java incrementally scans loaded chunks into a 96x64x96-block region on a stable
16-block grid around the camera, skipping empty or unloaded sections, and builds
triangles from baked block models with their atlas UVs, biome tint, emission and
sunlight transmission. Complete snapshots are uploaded on a worker thread and
published atomically as a BLAS/TLAS pair; budget one million triangles. Block
edits invalidate their section. Scene changes can lag fast movement or edits by a
few frames; lighting fades out over the last blocks of the region instead of
ending at a hard edge.

On top of the world, the surface tracer adds:

- the local player's posed body as textured boxes ([actor reflections](ACTOR-REFLECTIONS.md));
- meshes registered by other renderers, placed per frame through instance
  transforms ([external renderers](EXTERNAL-RENDERERS.md)).

Entities, fluids as geometry, particles and distant terrain are not ray targets.

## Two tracers

**Surface rays** (used by Solstice) start from a material capture of the visible
terrain: sun visibility, four diffuse GI rays and, on reflective materials, a
reflection ray per surfel, written at quarter or half resolution and reconstructed
against the full-resolution capture. Bindings: `surface_rays.glsl`, described in
[surface rendering](SURFACE-RENDERING.md) and [Solstice RT](SOLSTICE-RT.md).

**World rays** start from the camera's own primary rays and export half-resolution
irradiance, normals and albedo for scene passes. Shaders include
`<greenmarine:rays.glsl>`:

```glsl
layout(std140) uniform RayFrame {
    vec4 RayInfo;       // ready, width, height, triangle count
    vec4 RayBoundsMin;  // camera-relative scene minimum xyz
    vec4 RayBoundsMax;  // camera-relative scene maximum xyz
    vec4 RaySun;        // normalized world direction xyz, daylight strength
    vec4 RayOptions;    // ambient, GI strength, scene age seconds (0..1), scene present
};
uniform sampler2D RayLighting;    // RGBA16F linear irradiance, alpha = hit
uniform sampler2D RayNormalDepth; // RGBA32F world normal xyz, reversed depth
uniform sampler2D RayAlbedo;      // RGBA16F linear atlas colour, alpha = hit
```

Test `marineHardwareRayReady()` before sampling; unavailable inputs are 1x1 zero
textures of the same formats. The render-graph imports are `worldLighting`,
`worldNormalDepth`, `worldAlbedo` and `FrameContext.worldRaysReady()`.
`examples/aurora-rt` is a small resource-pack example built on world rays
(`./gradlew exampleShaderpack`).

## Resources and timing

Steady frames reuse native resources: up to four in-flight submissions each own
their descriptors, uniforms and per-frame acceleration data, and are reclaimed in
order as Minecraft's fences complete. Scene replacement, resize and reload may wait
for the device. Profiling (F6) reports separate CPU and GPU timings for the RT
stages; they cover RT work, not whole-frame FPS.

## Limits

- Translucent blocks are alpha tested; glass transmits sunlight but does not
  refract rays. No caustics, multi-bounce transport or path tracing.
- Diffuse lighting uses four fixed directions with spatial filtering, so small
  occluders give soft, low-frequency GI rather than converged results.
- Vanilla baked lighting and fog are modulated rather than physically separated.
- Validation hardware: AMD RX 9070 XT (desktop) and Adreno on Android (Zalith).
  Other GPUs, operating systems and device-loss recovery are untested.
