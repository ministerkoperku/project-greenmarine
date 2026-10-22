# External renderers

`com.koper.greenmarine.api.external.ExternalRenderers` (API version 1) lets a mod that
draws its own geometry, outside Minecraft's pipelines, take part in GreenMarine's
surface ray tracing without giving up its fast path. A native Vulkan instancer
(for example KoperLib Kender: moving block grids, model batches, Create contraptions)
keeps rendering exactly as before and additionally:

| Part | What the external renderer does | What it gets |
|---|---|---|
| Ray geometry | Uploads meshes once, places instances every frame | Casts sun shadows, appears in reflections, contributes GI |
| Material capture | Draws into GreenMarine's open material pass | Its pixels are traced like terrain |
| Surface outputs | Samples GreenMarine's RT images in its own shader | RT lighting and reflections on its own pixels |

Each part is independent and optional. Everything fails soft: when hardware RT is
off, unsupported, or the native module is older, calls report unavailability and
nothing is drawn or traced. GreenMarine never links back to the caller.

## Optional dependency pattern

Depend on GreenMarine `compileOnly` and guard every call:

```java
if (FabricLoader.getInstance().isModLoaded("greenmarine")) GreenMarineBridge.install();

final class GreenMarineBridge {           // only loaded when GreenMarine is present
    static void install() {
        if (ExternalRenderers.API_VERSION != 1) return;
        ExternalRenderers.registerRayGeometry("kender:grids", GreenMarineBridge::instances);
        ExternalRenderers.registerMaterials("kender:grids", GreenMarineBridge::materials);
    }
}
```

Registrations return a `Registration`; closing it removes the contributor. A
contributor that throws is disabled (logged once) until it is registered again.

## 1. Ray geometry

### Triangle ABI

A mesh is `n * 96` bytes: 24 floats per triangle, in **mesh-local** space.

| Floats | Meaning |
|---|---|
| 0-3, 4-7, 8-11 | Vertices a, b, c as `x, y, z, 0` |
| 12-15 | `u0, v0, u1, v1` |
| 16-17 | `u2, v2` |
| 18 | Surface mode: `0` opaque atlas texel, `1` alpha-tested atlas texel, `2` untextured tint |
| 19 | Emission as the block light level 0..15 (terrain uses `BlockState.getLightEmission()`) |
| 20-22 | sRGB tint 0..1 (biome/vertex colour multiplied with the texel; the whole albedo in mode 2) |
| 23 | Sunlight transmission 0..1 (0 opaque, glass about 0.98) |

UVs address Minecraft's **block atlas** (`AtlasIds.BLOCKS`). Model textures that live
outside the block atlas should use mode `2` and put their average colour in the tint.
Triangles are two-sided; winding does not matter.

### Meshes

```java
RayMesh mesh = ExternalRenderers.createMesh(buffer); // copies; any byte order
mesh.prepare();   // optional: upload now, from any thread (big meshes off the render thread)
mesh.close();     // when the shape changes or disappears
```

A mesh is immutable. When a grid's blocks change, build a new mesh and close the old
one; frames still in flight keep the old BLAS alive natively. GreenMarine keeps the
CPU copy and re-uploads by itself after a device change. Budgets: 262,144 triangles
per mesh, 4,096 meshes and 256 MiB of mesh memory; uploads over budget are skipped
(the instance is simply absent) rather than evicting other meshes.

### Instances

```java
static void instances(RayFrame frame, RayInstances out) {
    for (Grid grid : visibleGrids)
        if (!out.add(grid.rayMesh, grid.x, grid.y, grid.z, grid.rotationScale)) break;
}
```

The contributor runs on the render thread right before each surface RT recording.
Positions are absolute world coordinates (doubles); the 3x3 basis carries rotation
and scale (it must be invertible). GreenMarine converts to its scene origin, so
large coordinates keep precision. At most 512 instances per frame; `add` returns
false once the budget is spent. Placing only a transform costs one TLAS rebuild per
frame, no BLAS work. `rayGeometryAvailable()` reports whether tracing is active.

## 2. Material capture

Surface RT traces from a material buffer captured before the world is drawn. To be
lit, an external renderer must also draw into that capture:

```java
static void materials(MaterialTarget t) {
    // t.vkCommandBuffer(): VkCommandBuffer inside an open dynamic-rendering pass
    kenderNative.drawMaterialPass(t.vkCommandBuffer(), t.width(), t.height(),
        t.projection(), t.viewRotation(), t.cameraX(), t.cameraY(), t.cameraZ());
}
```

Contract of the pass:

| Attachment | Format | Write |
|---|---|---|
| Color 0 | `VK_FORMAT_R32G32B32A32_SFLOAT` (109) | world-space unit normal xyz, `gl_FragCoord.z` |
| Color 1 | `VK_FORMAT_R16G16B16A16_SFLOAT` (97) | linear albedo rgb, `surfaceClass + roughness * 0.5` |
| Depth | `t.depthFormat()` (host VkFormat) | reversed-Z: compare `GREATER_OR_EQUAL`, write on |

- Clip position: `projection * viewRotation * (world - camera)`; Minecraft renders
  camera-relative, `viewRotation` has no translation.
- No blending; cutout textures should `discard` below their alpha threshold.
- Surface classes: 1 opaque, 2 metal, 4 water, 5 glass, 6 ice, 7 porous, 8 gem,
  9 coated dielectric. `BlockMaterials.forBlock(id)` returns GreenMarine's profile
  (class, roughness) for a block id, so grid blocks can match static terrain.
- Contributors run last in the pass; Vulkan state may be left dirty. Do not end the
  pass, begin another one, or submit.
- The callback runs on the render thread once per frame while a pack with surface
  capture (Solstice RT) is active.

## 3. Surface outputs

`ExternalRenderers.surfaceOutputs()` (render thread) returns raw `VkImageView`
handles in `GENERAL` layout, valid from the world pass until the next frame's
recording:

| View | Size | Content |
|---|---|---|
| `lightingView` | `width x height` (often half resolution) RGBA16F | linear irradiance rgb, sun visibility a |
| `reflectionView` | same | linear reflected radiance rgb, coverage a |
| `normalDepthView` | framebuffer RGBA32F | the material capture, color 0 above |
| `albedoView` | framebuffer RGBA16F | the material capture, color 1 above |

`ready` is false whenever RT did not run this frame; shade normally then. Sample
all of them with `gl_FragCoord.xy / framebufferSize` (lighting and reflection with
linear filtering). `assets/greenmarine/shaders/include/external_surface.glsl` holds
the reference arithmetic Solstice uses:

```glsl
vec4 captured = texture(NormalDepth, uv);
if (greenmarineExternalSameSurface(captured, normal, gl_FragCoord.z)) {
    vec4 light = texture(Lighting, uv), refl = texture(Reflection, uv);
    color = greenmarineExternalShade(albedo, light, refl,
        greenmarineExternalFresnel(normal, toEye, roughness));
}
```

The same-surface test matters: a texel can belong to whatever the capture saw at
that pixel, so only use it when the normal and depth agree with the fragment.

## Order within a frame

1. Camera captured; contributors' meshes may upload.
2. Material pass: terrain, then material contributors.
3. Surface RT recording: ray geometry contributors are asked for instances, the TLAS
   is rebuilt, rays traced into the lighting/reflection images.
4. World pass: terrain composes RT; external renderers draw and may sample
   `surfaceOutputs()`.

## Verifying on hardware

Native behaviour is covered by an explicit GPU acceptance test (reflection identical
to world geometry, shadow casting, per-frame motion, tint mode, flight leases and
cancelled recordings):

```sh
cd engine
GREENMARINE_GPU_TEST_DEVICE="RX 9070 XT" cargo test --lib surface_gpu_external -- --ignored --nocapture
```

`GREENMARINE_GPU_TEST_DEVICE` selects any GPU by name substring (all GPU tests use
it). In the phone's proot environment, lavapipe (Mesa 26, aarch64) crashes while
building acceleration structures, also on code predating this API, so ray-query
tests there need real hardware; plain compute tests run on lavapipe.
