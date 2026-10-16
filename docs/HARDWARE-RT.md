# Aurora hardware world rays (0.5)

Select **Aurora Hardware RT** in F6 with **Hardware** or **Auto** rays. The
standalone mod needs Minecraft 26.3, Java 25 and Loader 0.19.5+. Vulkan must be
selected before launch (`--graphicsBackend vulkan` game argument). The bundled
native engine currently supports Linux x86_64; JVM native access is required.
FSR4 remains deferred. Existing Portable/Advanced/Radiance packs keep their APIs.

`build/shaderpacks/aurora-rt-example-0.5.0-prototype.zip` is a complete resource
pack example. Enable it in Minecraft resource packs, then select
`aurora:rt_example` in F6. Its editable JSON/Lua are in `examples/aurora-rt`;
`./gradlew exampleShaderpack` packages those and the tested builtin GLSL.
This uses GreenMarine schema1 and is not an Iris/OptiFine-format pack.

F6 shows Disabled/Loading/Loaded/Fallback/Failed and a reason; chat announces
terminal state transitions. Loaded requires all selected pack programs to submit
and required current-frame RT data to be ready. Script-only packs explicitly
report script readiness without rendering passes. Packs without hardware_rt passes retain their portable voxel selection.
Unsupported features, OpenGL,
missing native engine or Rays Off use the declared fallback and report Fallback.
A hardware-capable GPU alone is insufficient. Failure details are logged and
reported; render-path changes refresh status even while the game is paused.

The native path builds actual Vulkan BLAS/TLAS from loaded baked block-model
triangles and UVs. Ray queries sample Minecraft's borrowed block atlas and test
cutout candidates. Primary rays honor the submitted world projection, including
translated bob/hurt effects. Each matched surface receives sun visibility and
four diffuse rays with 12-block reach; secondary materials include emission and
geometry outside the visible image. The sun direction/weather follow vanilla's
render state. These are actual world rays, not screen-space color lookups.

Java incrementally collects a 96x64x96 scene on a stable 16-block grid, skips
empty/unloaded sections, and publishes complete immutable snapshots. There is a
one-million-triangle budget. Replacements/resize/reload may wait for the device;
steady frames reuse resources without device idle. Acquiring scene changes can
lag movement or edits. RT shadow distance is finite. The example uses the same
global lighting baseline across the world and fades finite-scene shadow/GI
contribution over eight blocks rather than revealing a hard camera-local patch.

Scene shaders may include `<greenmarine:rays.glsl>`:

```glsl
layout(std140) uniform RayFrame {
    vec4 RayInfo;       // ready, width, height, triangle count
    vec4 RayBoundsMin;  // camera-relative scene minimum xyz
    vec4 RayBoundsMax;  // camera-relative scene maximum xyz
    vec4 RaySun;        // normalized world direction xyz, daylight strength
    vec4 RayOptions;    // ambient, GI strength, scene age seconds (0..1), scene present
};
uniform sampler2D RayLighting;    // RGBA16F linear irradiance, alpha=hit
uniform sampler2D RayNormalDepth; // RGBA32F world normal xyz, reverse depth
uniform sampler2D RayAlbedo;      // RGBA16F linear atlas color, alpha=hit
```

These initialized bindings are scene-only. Test `marineHardwareRayReady()`.
Ready textures are half-resolution; unavailable inputs are distinct 1x1 zero
textures of the corresponding formats. MarineFrame remains exactly 288 bytes.
The public graph imports are `worldLighting`, `worldNormalDepth`, `worldAlbedo`
and `FrameContext.worldRaysReady()`. Borrowed textures remain renderer-owned.

Exposure, sunStrength, giStrength and blend control the example. Diagnostics:
0 normal, 1 native albedo, 2 normals, 3 alignment (green matched/red unmatched).
Filtering operates on irradiance with plane/normal rejection, independent of
texture color; highlight compression uses a shared scale to preserve hue.
Sampling patterns are world anchored rather than moving with screen pixels.

Limits: vanilla rasterization/composition remains; entity, fluid and special-renderer geometry are absent from acceleration
structures. Translucent block models are approximated with alpha tests; transmission
and refraction are not implemented. Alpha
silhouettes can mismatch because primary tracing is half resolution with
mip0 alpha tests. Biome tint/normal/PBR maps are not yet captured. Diffuse samples
are fixed low count, with spatial filtering and no temporal accumulation;
this is not multi-bounce path tracing or reflections. Existing baked vanilla
lighting/fog is modulated rather than physically separated. Other OSes/GPUs,
device loss and broad shader-mod coexistence remain unverified.

Actual captures, moving-camera alignment checks, resource lifecycle regressions
and separate CPU/GPU timings are recorded in
`development/evidence/hardware-rt/README.md`. Measurements cover the RT stage,
not whole-frame FPS or a universal performance promise.
