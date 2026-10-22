# Indexed and instanced meshes

`RenderSession.indexedMesh` uploads one per-vertex buffer plus unsigned native-endian
SHORT/INT indices. Each triangle has three indices. Ranges, stride, count and index
bounds are validated before GPU allocation. Input positions/limits are preserved;
an index upload failure releases the already-created vertex buffer.

```java
var mesh = session.indexedMesh("quad", vertexFormat, 4, vertexBytes,
    IndexType.SHORT, indexBytes); // 0,1,2, 2,3,0 in native byte order
// Inside a declared graph pass; normal material/attachment ownership checks apply:
context.draw(material, List.of("color"), "depth", mesh, true, 64, 0);
```

The final arguments are instance count and first instance. This issues one host
indexed draw for all 64 instances. The old five-argument draw overload still uses
one instance. Non-indexed meshes also support instancing. Nonzero firstInstance
requires the host's `DeviceFeatures.nonZeroFirstInstance()` capability; Renderpearl
rejects unsupported requests. The shader must use the appropriate backend instance
builtin to index uniform data or generate transforms; instancing alone does not
supply per-instance transforms or change a shader's output.

Meshes still use one per-vertex binding and TRIANGLES. Multiple instance vertex
bindings, skinning, indirect draws and storage buffers are separate future work.
This API does not automatically batch Minecraft terrain/entities or put player
meshes into the native ray scene. Only session-owned buffers are closed.

Tests exercise command arguments/count and allocation/failure/close boundaries
with a fake backend. These are not Android gameplay or new GPU-render acceptance.
