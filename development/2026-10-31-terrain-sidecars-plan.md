# Vanilla terrain attribute sidecars

Implement opt-in, generation-stamped capture beside Minecraft 26.3's unchanged
28-byte BLOCK vertices. Capture the actual block-state mapper id (fluid legacy
state for liquids), quad-diagonal normal, geometry/UV tangent, arithmetic UV
midpoint, signed byte midpoint offsets and tint RGB with separate raw AO.
Capture occurs on section worker threads before directional shading and tint
multiplication. Preserve the exact host vertex order and all index sorting.

Attach owned CPU records to the compile results and transfer them to the exact
CompiledSectionMesh/layer identity before host MeshData closes. Upload only on
the render thread into GreenMarine-owned storage buffers; never close or mutate
borrowed host buffers. Pack/profile, world and atlas changes invalidate the
capture generation, so stale or cancelled section builds cannot publish.

Verification proceeds with failing packing/math and generation/lifetime tests,
then real mesher capture plus host Vulkan readback across positive/negative
sections, fluid/sloped geometry and real mapper ids. GPU source attribute binding
uses per-layer draw metadata: hostBaseVertex and sidecarBase. Lookup is
sidecarBase + gl_VertexIndex - hostBaseVertex, indexed by gl_InstanceIndex.
The converter must select semantic adapters by name because source terrain and
water use different location 3 attributes. Entity draw attributes are deferred.
Compilation alone does not establish terrain shader binding/runtime acceptance.
