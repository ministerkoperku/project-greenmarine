# Actor memory and microgrid lighting follow-up

The owner supplied a direct-memory OOM at PlayerReflectionKAvatar.capture after
about fourteen minutes, with ZGC, DisableExplicitGC and an 8096 MiB direct limit.
They also report incorrect Mechanics Dream microblock lighting. This is the active
priority before continuing the still incomplete Eclipse native runtime conversion.

## Actor and scene storage

Confirmed repeated allocateDirect calls in actor builders, KAvatar concatenation,
atlas remapping, ABI padding and scene builders. Reproduced the same 18448-byte
reservation failure in a separate VM with ZGC, DisableExplicitGC, Xmx256m and a
1 MiB direct limit. The old code fails well before 50000 frames.

Actor intermediate buffers now use heap memory; render-thread body/entity/part
builders and atlas remap storage are reused. Final actor ABI payloads use a bounded
Arena-backed pool of at most eight slots (native supports four flights). A snapshot
owns a lease until RayWorld's host fence and native submission completion both
finish; cancelled/unsubmitted captures return it, and legacy synchronous record
calls copy CPU data before the lease is returned. Reset drains before freeing the
pool. Immutable zero payloads are allocated once for hidden/unavailable actors.

TriangleScene and retained external mesh CPU copies are heap-owned too. The
NativeInput helper borrows direct input, or copies heap input into a confined
native allocation, freed on return from the synchronous native prepare/upload.
Rust scene/mesh factories consume and upload the complete slice before returning;
they do not retain the Java pointer. Native ABI layouts and versions are unchanged.

JOML's Unsafe ByteBuffer serializers assume direct buffers. Staging matrix writes
and test readers were changed to explicit float access after the JVM regression
test caught this assumption; no unverified crash-bearing intermediate is shipped.

Evidence under build/verification/actor-memory-2026-10-29:

- Actor and scene soak tests each pass 50000 iterations under the 1 MiB direct
  budget after observed matching OOM failures before the changes.
- Pool tests retain independent in-flight bytes, enforce exhaustion, reuse freed
  slots, reject reset while leased, clear tails and verify Arena invalidation.
- Actual Vulkan High TorchShadowProbe completed placed/held/dropped/empty-hand
  and water states. Native actor pool: two slots, two pending, 36896 bytes.
  Runtime and shutdown complete normally. This is not a fourteen-minute fullpack
  acceptance run or a measured improvement in 1%-low FPS.

## Microgrids

Confirmed mismatch: Mechanics Dream's Fabric emitQuads draws scaled pieces from
chunk attachments, but collectParts delegates to the container's fallback model.
GreenMarine's scanner previously read collectParts only, tracing the wrong shape
and container material. Attachment geometry edits also call
ClientLevel.setSectionDirtyWithNeighbors without changing the BlockState, which
previously did not invalidate the RT cache.

Added a generic BlockRayModels API and an optional integration-source-set adapter.
The adapter reads actual MicroGridData pieces, retains each material's tint,
emission and transmission, scales its baked quads and omits fully hidden internal
faces. Missing attachment data produces an empty ray model, not a phantom cube.
Client section notifications invalidate nearby cached/in-progress geometry.

Actual Mechanics Dream and KoperLib fixtures ran on RX 9070 XT Vulkan. The
adapter returned two pieces; exactly twelve emitting triangles occupy the actual
glowstone quarter, not its container. Solstice High rendered and shut down
normally. Evidence: build/verification/microgrid-2026-10-29/vanilla-comparison.
A full stone cube beside the quarter has matching skylight and face illumination
in diagnostic 10. Dark vertical faces also occur on ordinary stone; this does not
justify changing the pack's ambient strength. Diagnostic 3 now distinguishes
reversed normals (magenta) from successful full coverage/support (cyan).

The separate KoperLib Khysics bridge now queries the same API for moving pieces,
preserves their material/scale/body-local coordinates and hashes local NBT to
invalidate changed meshes. KoperLib root compile/tests and Khysics build passed:
140 Khysics tests, including six geometry/API checks. Mechanics Dream compiled
against the rebuilt library. Moving Vulkan visual acceptance is still pending;
static-world evidence is not moving-body evidence. No installed game jars or
Mechanics Dream sources were replaced; bridge changes are in KoperLib source.

Read-only OOM review found no actionable lifetime regression and independently
verified synchronous host uniform uploads. Atlas upload uniforms also use bounded
Arena storage, avoiding direct-buffer churn when texture plans change. The CPU
50000-frame regression and short Vulkan test passed; a fourteen-minute fullpack
acceptance run has not been performed.

## Remaining Eclipse work

Keep all existing uncommitted converter/API work. The local converted workspace
is build/verification/eclipse-conversion-2026-10-29/converted: all source/license
bytes preserved, 528 enabled stages, 272 linked groups, 434 core stage compiles,
42 optional integration compile failures, runtimeReady=false. Native MRT mixed
float/uint feedback probe passed. Volume and line boundary probes pass: the
host sampler's implicit REPEAT on W first reproduced lower Z=3, upper Z=1;
extending V addressing to W corrected these to 1 and 3. All 96 volume texels,
three slices and 17 RG32UI line texels have zero mismatches. Evidence:
volume-line-boundary-{red,fixed} logs and fixed JSON. UniformLayout and
UniformExpressions are implemented and unit tested. Real host inputs and scene/
compute uniform binding pass Vulkan readback, and malformed layouts are rejected
against actual shader reflection before binding. Loader bounds now fit converted active
assets while excluding preserved sources/metadata from active snapshots. Full
scene/world/shadow/custom uniform execution is not complete.
