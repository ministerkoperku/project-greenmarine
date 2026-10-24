# Compute, storage buffers and storage images

GreenMarine packs and mods can run compute shaders on Minecraft's own Vulkan device,
inside the frame's command stream. Compute is the base for histograms and auto
exposure, sky/atmosphere LUTs, light culling, temporal accumulation, particle
simulation, GPU-side voxel work and anything else that doesn't fit a full-screen
fragment pass.

Renderpearl (MC 26.3) has no compute facade, no storage buffers and no storage
images. GreenMarine adds them as a host extension: storage usage goes only on
resources GreenMarine creates for this purpose, and every dispatch is recorded on
the host encoder between full memory barriers, in the GENERAL layout that all host
images already use.

Compute is a **Vulkan-only path**. It is reported as available only after a real
pipeline was built on the host device (`compute` in pack capabilities,
`frame.capabilities.compute` in Lua, `MarineRenderer.computeCapabilities()` in Java).
On OpenGL, compute passes are skipped and `"requires":"compute"` passes take their
fallback.

## In a shaderpack

```json
{
  "schema": 1,
  "id": "demo:exposure",
  "name": "Auto exposure",
  "resources": {
    "images":  {"Exposure": {"format": "r32f", "size": [1, 1]},
                "Bloom":    {"format": "rgba16f", "scale": 0.5}},
    "buffers": {"Histogram": {"size": 1056}}
  },
  "passes": [
    {"name": "histogram", "stage": "compute", "compute": "demo:compute/histogram", "groups": "screen"},
    {"name": "average",   "stage": "compute", "compute": "demo:compute/average",   "groups": [1, 1, 1]},
    {"name": "bloom",     "stage": "compute", "compute": "demo:compute/bloom",     "groups": "image:Bloom"},
    {"name": "tonemap", "fragment": "demo:core/tonemap", "requires": "compute",
     "fallback": "demo:core/tonemap_fixed", "inputs": {"ExposureTex": "Exposure", "BloomTex": "Bloom"}}
  ]
}
```

### Resources

`resources.images` are persistent 2D storage textures; `resources.buffers` are
persistent storage buffers. Both keep their contents across frames, so temporal
techniques are just a read-modify-write. They are zero-cleared on pack change,
resource reload and failure recovery; scaled images are also re-created (and
cleared) when the window size changes.

| Field | Values |
| --- | --- |
| image `format` | `rgba8`, `rgba16f` (default), `rgba32f`, `r32f`, `rg32f`, `r16f`, `rg16f`, `r32ui`, `rg32ui`, `rgba32ui`, `r11g11b10f` |
| image size | `"size": [w, h]` (1..8192) or `"scale"`: 1, 0.75, 0.5, 0.25 of the scene |
| buffer `size` | bytes, multiple of 4, up to 64 MiB (128 MiB per pack) |

At most 16 images and 16 buffers. Names follow GLSL identifier rules and must not
collide with passes or reserved bindings (`MarineFrame`, `ColorSampler`, ...). A
format that the device can't use as a storage image fails the pack load with a clear
message. The `glsl()` spelling of each format is what goes in the shader's
`layout(...)` qualifier.

### Compute passes

`"stage": "compute"` passes go in the same `passes` array and run in declaration
order with the scene passes, so a compute pass can read the output of an earlier
fragment pass and a later fragment pass can sample what compute wrote. A pass takes
`compute` (the program id), `groups` and optionally `inputs`, `requires` and
`minQuality`. Compute passes don't count toward the eight raster passes; a pack
can have up to 16.

The program `demo:compute/histogram` loads
`assets/demo/shaders/compute/histogram.csh` (Iris uses the same extension).
`#include <demo:file.glsl>` works as in fragment shaders.

`groups` sets the dispatch size:

- `"screen"`: enough workgroups to cover the scene (`ceil(width / local_size_x)`,
  `ceil(height / local_size_y)`, 1).
- `"image:<Name>"`: enough to cover that pack image.
- `[x, y, z]`: fixed workgroup counts.

### Binding by name

There are no binding tables. Declare what you need and GreenMarine binds the resource
with that name:

| GLSL declaration | Bound to |
| --- | --- |
| `uniform MarineFrame { ... }` | the same 288-byte frame block that fragment passes receive |
| `buffer <Name> { ... }` (block name) | pack buffer `<Name>` |
| `layout(<fmt>) uniform image2D <Name>` | pack image `<Name>`, read/write |
| `uniform sampler2D <Name>` | `inputs` key, then `ColorSampler` / `SceneSampler` / `DepthSampler` / `LightmapSampler`, then pack image `<Name>` |

`ColorSampler` is the scene chain's current image at that point (the original world
image if no fragment pass has run yet). `DepthSampler` and integer images use nearest
filtering, everything else uses linear clamp-to-edge. An image can't be written and
sampled in the same dispatch. Use `layout(binding = N)` only if you want to; any
binding you leave out is assigned automatically. Unknown names fail the pack with a
message naming the pass and the resource.

```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16) in;
layout(std140) uniform MarineFrame { vec4 Frame; vec4 Context; mat4 InverseProjection; mat4 InverseViewRotation; vec4 Parameters[8]; };
layout(std430) buffer Histogram { uint bins[256]; uint count; float exposure; };
uniform sampler2D SceneSampler;

shared uint local[256];
void main() {
    uint i = gl_LocalInvocationIndex;
    local[i] = 0u; barrier();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(p, ivec2(Frame.xy)))) {
        float l = dot(texelFetch(SceneSampler, p, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
        atomicAdd(local[uint(clamp(log2(l + 1e-4) * 16.0 + 128.0, 0.0, 255.0))], 1u);
    }
    barrier();
    atomicAdd(bins[i], local[i]);
}
```

### Using compute results in fragment passes

Scene passes see pack resources two ways:

- **Directly as storage (Vulkan):** declare `buffer <Name> { ... }` or
  `layout(<fmt>) uniform image2D <Name>` with the resource's own name and it is
  bound, no `inputs` entry needed. Reads always work; writes and atomics
  (`atomicAdd` into a counter, scattering into an image) need
  `fragmentStorageWrites` (see capabilities), which GreenMarine requests from the
  device as an optional feature.
- **Filtered sampling:** list the image in `inputs` like an earlier pass output:
  `"inputs": {"ExposureTex": "Exposure"}` and `uniform sampler2D ExposureTex;`.
  The sampler name must differ from the resource name.

```glsl
#version 450
layout(std430) readonly buffer Histogram { uint bins[256]; uint count; float exposure; };
uniform sampler2D ColorSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() { fragColor = vec4(texture(ColorSampler, texCoord).rgb * exposure, 1.0); }
```

Fragment passes that use storage need Vulkan; give them `"requires": "compute"` and
a fallback so OpenGL keeps working.

## In Java (`RenderSession`)

```java
var program = session.compute(new ComputeProgram(Identifier.parse("mymod:blur"), glsl, includes));
var target  = session.storageTexture("blur", TextureSpec.scaled(GpuFormat.RGBA16_FLOAT, 0.5), w, h);
var data    = session.storageBuffer("cells", 4096, 0);           // zero-filled
session.dispatch(encoder, program.bind()
        .texture("Source", frame.texture("sceneColor"), FilterMode.LINEAR)
        .image("Blurred", target)
        .buffer("Cells", data),
    program.groupsX(target.texture().getWidth(0)), program.groupsY(target.texture().getHeight(0)), 1);
```

- `ComputeHandle.bindings()` lists the reflected resources (name, binding, access)
  and `localSize()` the workgroup size. `groupsX/Y` size a dispatch to cover a
  texture.
- `ComputeBindings` checks every name, kind, buffer range and storage capability,
  and rejects a texture bound as both storage and sampled.
- Storage textures are ordinary `TextureHandle`s. They can be sampled by materials,
  used as draw attachments or graph imports.
- In a render graph, declare `storageTexture(name, spec)` and call
  `ctx.dispatch(bindings, x, y, z)` from a pass, with `ctx.storage(name)` for the
  image view. Storage images must be declared writes of the pass and sampled textures
  declared reads, so graph ordering stays correct.
- `MarineRenderer.computeCapabilities()` reports the initialized path, workgroup
  limits, the formats usable as storage images on this device and the raster
  storage flags (`rasterStorageReads`, `fragmentStorageWrites`,
  `vertexStorageWrites`, `formatlessStorageImages`). Write flags come from features
  the host device actually enabled.

### Storage in raster programs

Renderpearl's `UniformType` has no storage kinds, so declare a storage buffer as
`UNIFORM_BUFFER` and a storage image as `COMBINED_IMAGE_SAMPLER` in the
`BindGroupLayout`, then mark them on the program:

```java
var program = session.compile(new ShaderProgram(pipeline, sources, Map.of(), Map.of())
    .withStorage(Map.of("Cells", ShaderProgram.Storage.BUFFER, "Painted", ShaderProgram.Storage.IMAGE)));
ctx.draw(ctx.material(program).storageBuffer("Cells", cells).storageImage("Painted", "painted"), List.of("out"), null, null, true);
```

GreenMarine's mixins then make those bindings real storage descriptors (reflection
type check, set layout, descriptor push). `storageImage` takes a graph storage
texture; it is read-only unless the pass declares the texture as a write. A pipeline
location always keeps the same storage names.

Every dispatch is one transient command buffer with a barrier before and after.
That's correct by construction, but small back-to-back dispatches pay for the full
barriers. Batching several dispatches between one pair of barriers is planned.

## Not yet

- 3D images, image arrays, mip chains, push constants and separate samplers.
- Indirect dispatch and draws fed by compute.
- Compute in world programs (terrain/entity) and in the lightmap stage.
