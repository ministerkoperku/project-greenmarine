# Pack uniform blocks

Schema 1 can declare ordered std140 uniform resources and select them independently
for each scene or compute pass. A GLSL block name does not identify a layout across
an entire pack: two linked programs can both declare `IrisUniforms` with different
members. Each pass therefore maps its block name to a specific resource.

```json
{
  "schema": 1,
  "id": "demo:uniforms",
  "resources": {
    "uniforms": {
      "CompositeFrame": [
        {"name": "frameTime", "type": "float", "source": "frameTime"},
        {"name": "projection", "type": "mat4", "source": "gbufferProjection"},
        {"name": "brightness", "type": "int", "arrayLength": 2, "source": "eyeBrightness"}
      ]
    }
  },
  "passes": [
    {
      "name": "composite",
      "fragment": "demo:composite",
      "uniforms": {"IrisUniforms": "CompositeFrame"}
    }
  ]
}
```

Array order defines member order. `arrayLength` is optional and must be a positive
integer when present. The member properties are exactly `name`, `type`, `source`
and optional `arrayLength`; unknown properties fail validation. Source keys are
exact, case-sensitive host-map keys matching `[A-Za-z_][A-Za-z0-9_.]{0,191}`.
A dotted source key is a single map key, not an expression or object traversal.
Declaring a source does not implement a host provider for that value.

`UniformLayout` supplies the layout and packing rules: 32-bit `float`, `int`,
`uint`, `bool`, their GLSL vectors, column-major `mat2` through `mat4` including
rectangular matrices, and fixed arrays. Unsupported types, duplicate member names,
empty blocks and overflowing layouts fail validation. Values use scalar numbers or
booleans and flat primitive arrays or lists; matrices are flattened column-major.
Padding is initialized during packing. There are at most 64 uniform resources per
pack and 64 uniform bindings per pass, with at most 64 KiB per resource (4 MiB of
packed data across 64 maximum-sized resources).

Resource names share the existing image and buffer namespace. They must not alias
colour targets or pass names. Uniform binding names must not alias reserved engine
bindings, pass names, input samplers, images, buffers, targets or a different uniform
resource. A binding may have the same name as its selected uniform resource.
References must name a declared uniform resource. Lightmap passes reject custom
world uniform bindings. The fixed `MarineFrame` ABI remains 288 bytes and cannot be
replaced through these declarations.

## Prepare values in Java

```java
PackUniformBlock block = pack.resources().uniforms().get("CompositeFrame");
java.nio.ByteBuffer bytes = block.pack(java.util.Map.of(
    "frameTime", 0.05f,
    "gbufferProjection", projectionColumnMajor,
    "eyeBrightness", new int[]{0, 240}));
```

`members()`, `layout()` and the declaration-ordered `sources()` map are immutable.
The latter maps member names to host keys. `pack(hostValues)` allocates a packed
block; `write(destination, hostValues)` prepares an existing writable buffer without
changing its position, limit or byte order. The host map may contain unrelated
values, but every declared source must be present, non-null, correctly shaped and
representable by its GLSL type. Missing or invalid values throw an exception;
preparation supplies no implicit zero, identity matrix or other fallback. Failed
writes leave the destination unchanged.

## Runtime binding

Scene and compute passes resolve only selected blocks' source keys from one
[Iris frame snapshot](IRIS-UNIFORMS.md) per rendered frame. The native default sun
path is zero degrees; the future imported pipeline adapter must supply its pack
rotation before claiming Eclipse parity. Unsupported source keys fail explicitly.
Custom expression outputs and arbitrary application-provided host maps are usable
through the Java preparation API; they are not yet registered as manifest runtime
sources. Lightmaps cannot request world bindings.

A pass maps shader block names to resource names. Blocks upload once per frame
into immutable host transient slices shared by its selected passes. Reusable
Arena-backed CPU scratch is bounded by the selected declarations and released on
replacement, removal and reset. The host copies those bytes immediately; freeing
or reusing the CPU scratch cannot overwrite a recorded pass's uploaded values.
The runtime does not call `pack()` and allocate a new direct buffer every frame.

Before binding, the declaration is checked against the actual compiled SPIR-V,
including optional binaries and cached shader modules. Member order, physical
types, array lengths, offsets, byte sizes, array/matrix strides and matrix order
must agree. Optimized-out blocks can be unused. Vulkan's physical uint storage for
GLSL bool/bvec is ABI-compatible with the corresponding boolean declaration.
Unsupported structs, doubles and multidimensional arrays fail when mapped.

Vulkan uploads also check the actual host physical device's
`maxUniformBufferRange`. Other backends use a conservative 16 KiB limit because
the host's portable DeviceLimits does not expose this capability; their runtime
rendering has not been verified. The schema's 64 KiB ceiling does not promise
that every device accepts that size.

Existing `PackResources(images, buffers)` and `PackPass` constructors retain empty
uniform declarations. Pure schema/preparation APIs make no GPU calls, and a
uniform-only raster pack does not require compute storage resources. MarineFrame
remains 288 bytes.

Vulkan acceptance on RX 9070 XT exercised one compute and four scene passes,
three mixed-format attachments and four frames with zero readback mismatches.
It checked real dimensions/frame index, matrix/inverse layout, vec3/scalar packing
and a std140 integer array. A second probe recorded two different payloads from
reused scratch, freed CPU storage before submission, and recovered both expected
results without mismatches. These are uniform and resource tests, not complete
Eclipse rendering acceptance.
