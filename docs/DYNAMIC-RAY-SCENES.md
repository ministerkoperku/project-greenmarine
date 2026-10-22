# Dynamic ray scenes

GreenMarine extracts loaded block-model geometry on the client render thread.
Immutable section meshes are cached at integer world origins. Moving the finite
96x64x96 scene by one section reuses 120 of 144 sections horizontally or 108
vertically. Valid unfinished section extraction survives overlapping camera moves. Actual
changed geometry can preempt it; camera distance changes alone cannot repeatedly
restart it. Pending counts and cached column membership are maintained without
rescanning every section on each block invalidation. The cache retains at most 216 sections and two million
triangles (192 MB of raw mesh data). Inactive meshes are evicted first; an active
overflow rejects the replacement. Snapshots, staging and native preparation add
transient allocations, so this is not a total process-memory limit.

Extraction runs each rendered world frame with a one millisecond target and an
8192-cell cap. Individual model extraction and chunk validation can exceed the
target; this is a scheduling budget, not a hard frame-time guarantee. Actual block
edits invalidate affected sections and boundary neighbors. Chunk/biome updates,
unloads, tint changes and resource/world resets invalidate the corresponding
cached geometry. Missing chunks are checked again instead of cached permanently.

One worker assembles immutable snapshots and prepares native memory. Live world,
model and tint APIs stay on the render thread. Work is bounded to one preparation
and one result awaiting publication; newer revisions coalesce while it runs.
The previous valid RT scene remains available. Publication uses the new bounds
once every loaded section has a mesh, including valid retained meshes of dirty
sections. While new outer sections are scanned it can publish actual edits
inside the still-covered previous bounds. It does not introduce missing outer
occluders through successive partial scenes. Known unloaded sections are valid
empty coverage; chunk arrival invalidates them. Exact-size immutable assembly
avoids geometric buffer growth and the final full-scene copy, and unchanged
section metadata skips packing entirely. A changed world or incompatible
scene origin cannot publish an obsolete result. Reset joins the worker before
the borrowed Vulkan device is detached.

The optional async native ABI1 extension uses up to four submission slots. Each
slot owns its descriptors, uniforms and intermediate output buffers, and retains
the immutable acceleration-structure generation it uses. Java creates a host
GPU fence before submitting the encoded batch and polls it with zero timeout.
Only completed submissions release their native resources. Busy slots use the
initialized raster fallback for that frame; they do not reuse another camera's
lighting or wait for the device. Surface targets are drained before replacement.

AS building is recorded into the real host queue with the first trace of a new
generation. Steady async preparation, publication and tracing do not call
`vkDeviceWaitIdle`. Reset, device teardown and compatibility with older native
libraries retain their synchronous cleanup behavior. History is invalidated
when a new lighting scene is published, except opted-in `lightingHistory` passes.
Those packs must reject incompatible surfaces and bound their transition;
Solstice does so using scene depth, normals and material history.

All native ABI versions are 1; older native libraries without the async extension
use the synchronous ray API. Freshness applies inside the finite scene; fluids and
entities are not secondary geometry, and RT does not cover the full render
distance. Moving geometry from other mods uses
[external renderers](EXTERNAL-RENDERERS.md) instead of rescanning.

On an RX 9070 XT fixture, a block edit reached the GPU sunlight result in
9.88–14.18 ms (a full rescan scheduler took about 5 seconds). Coverage caught up within 8.70 ms during its section crossings;
2509 actual walking GPU readbacks preserved the shadow and unblocked control.
Steady scene updates added zero native device-idle calls. These are local fixture
results, not universal latency or frame-rate guarantees.
