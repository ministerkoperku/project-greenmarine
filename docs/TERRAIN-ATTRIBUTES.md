# Vanilla terrain attribute capture

`TerrainCapture` opts into additional records beside Minecraft 26.3's unchanged
28-byte terrain vertices. It captures vanilla section meshes; alternative chunk
meshers, including Sodium, need their own adapter.

```java
TerrainCapture.configure(
    TerrainCapture.MappingSnapshot.capture(state -> resolveMaterialId(state)),
    TerrainCapture.Profile.IRIS_SEPARATE_AO);
```

Mapping snapshots materialize all registered block states once. Section workers
never call the supplied mapper. An unmapped state uses `-1`, which is a valid
material ID. Fluid records map the fluid's legacy block state, so a waterlogged
solid does not supply the water's material ID.

Rebuild visible sections after configuring capture. The caller owns selecting
the mapping/profile when its pack changes. Configuration, world resets, pack
resets, resource reload and host compiler/model replacement invalidate older
generations. Parameter previews keep the renderer and capture generation.

The current profile stores one 32-byte record per emitted host vertex, preserving
quad order and transparency sorting:

| Byte offset | Data |
| --- | --- |
| 0 | Quad-diagonal normal, signed normalized bytes |
| 4 | Geometry/UV tangent with signed handedness |
| 8 | Signed 32-bit material ID |
| 12 | Signed-byte block-center offsets and render type |
| 16 | Arithmetic quad UV midpoint, two floats |
| 24 | RGB tint before lighting, raw AO in alpha |
| 28 | Intrinsic block emission, low byte clamped to 0–15; remaining bytes zero |

Midpoint bytes retain their signed magnitudes; divide by 64 for section-local
offsets. Render type is `-1` for ordinary vanilla blocks and `1` for fluids.
Normals come from actual emitted positions, including sloped fluid surfaces.
Tint and AO are captured before directional lighting and tint multiplication.

CPU records transfer from compile results to the exact compiled mesh identity.
Cancelled or failed compilation cannot publish a current record. GPU upload and
retirement use the render thread and GreenMarine-owned buffers. Mesh release on
a worker immediately makes its records unavailable and queues GPU destruction.
Borrowed Minecraft buffers remain Minecraft-owned.

`TerrainDrawBindings.configure(true)` opts into actual per-section drawing. The
host keeps its section extraction, transparency order, vertex buffers and index
buffers. GreenMarine selects Minecraft's separate-draw implementation and adds
two storage descriptors through each existing draw's uniform callback. This
currently trades indirect batching for per-section descriptors; no performance
improvement is claimed.

The shader ABI uses these exact block names:

```glsl
layout(std430) readonly buffer MarineTerrainVertices { uvec4 records[]; };
layout(std430) readonly buffer MarineTerrainDraws { ivec4 draws[]; };
```

Each vertex occupies two `uvec4` records with the 32-byte layout above.
`draws[gl_InstanceIndex]` contains `(hostBaseVertex, sidecarBase, vertexCount,
ready)`. Separate host draws have instance index zero and bind one metadata
entry. Resolve `vertex = draw.y + gl_VertexIndex - draw.x`; read records only
when `draw.w != 0` and `vertex` is inside the declared range. Missing, retired
or older-generation capture binds initialized storage with readiness false.
The material ID `-1` remains a valid unmapped material, independently of readiness.
Descriptor binding numbers come from the pipeline layout. Host terrain inputs
must retain both their native names and locations: `Position` (`vec3`, 0),
`Color` (`vec4`, 1), `UV0` (`vec2`, 2) and `UV2` (`ivec2`, 3). Renderpearl
matches vertex-format elements by name, even when GLSL has explicit locations.

Disable drawing when the owning pack unloads. Capture configuration and visible
section rebuilds remain the caller's responsibility. Owned per-draw metadata
retires with its captured mesh; worker release never destroys GPU resources.

Native pack manifests can own this lifecycle through an optional `terrain` field:

```json
"terrain": {
  "profile": "iris_separate_ao",
  "blocks": {
    "minecraft:oak_log:axis=x": 37,
    "minecraft:oak_log": 42,
    "minecraft:water:level=0": 111
  }
}
```

Rules follow declaration order; the first matching block-state selector wins.
IDs are signed 32-bit integers, with `-1` for unmapped states. Property filters
are exact. This profile describes the stored sidecar layout; the converted
shader separately applies the source pack's `separateAo` setting. Current native
capture requires explicit vertex programs selecting only
`minecraft:pipeline/solid_terrain`, `minecraft:pipeline/cutout_terrain` and
`minecraft:pipeline/translucent_terrain`. It does not support surface replay.
GreenMarine activates capture, rebuilds visible geometry and disables owned
capture on pack unload or initialization failure. Native Vulkan, the vanilla
mesher, regular transparency and filled terrain are required. Unsupported host
modes fail initialization or rendering instead of reporting a supported path.
Packs without this field keep the existing explicit Java capture contract.

The Vulkan probe verifies four signed sections, mapping, fluid slopes, tint/AO
and lifecycle invalidation. Its draw mode also checks real host callbacks plus
vertex-stage readback using borrowed host terrain allocations, actual base
vertices, reversed indices and a nonzero first index. This does not establish
full Eclipse world/shadow support, Sodium compatibility or Android runtime
acceptance.
