# Dynamic ray-scene freshness and submission design

The owner's follow-up reports shadows appearing only after waiting while walking,
and a visible hitch when they arrive. Continue Solstice and legacy packs while
fixing this before delivery. Existing autonomous authorization applies.

## Verified source causes, pending measured reproduction

RayWorld currently refreshes only on unpaused game ticks, waits for the complete
96x64x96 scan, and starts periodic refresh after40ticks. The8192cell cap takes
72ticks in a dense full-visit case; this is not a measured universal lower bound,
because empty and missing sections skip. The1.5ms budget may slow dense scans.
Native replacement unconditionally waits for the device, reallocates full scene
resources and then waits again to mutate surface descriptors. The old ready scene
remains until complete replacement, explaining delayed coverage after movement.

## Selected architecture

Cache immutable section-local baked geometry with integer origins and revision
stamps. Reuse overlap when bounds shift, prioritize nearby dirty/new sections and
publish completed sections without waiting for the distant queue. Revalidate
loaded chunk identity; no persistent null/chunk snapshot across frames. Invalidate
neighbor sections on actual setBlocksDirty,3x3columns on load/biome updates,
drop unloaded columns and invalidate all on tint/model/resource changes.
Model extraction stays on the render thread under a small per-frame time budget.

Pack immutable cache snapshots and prepare native scene allocations/uploads on a
single worker. No background access to live Minecraft models/world state. Join
or discard preparation on world/device reset; never publish stale context tokens.

An additive async RT ABI owns bounded reusable flight slots. Each submitted flight
holds immutable descriptors, uniform/output storage and an immutable scene
generation until the actual host GPU fence completes. Java creates a fence before
encoder.submit and polls awaitCompletion(0); completion releases native references.
Busy preserves valid state and does not drain the GPU. No device_wait_idle in the
steady asynchronous record/scene-publication paths. Legacy ABI calls and teardown
retain their safe synchronous behavior. Borrowed views remain alive through fences.

## Acceptance and limits

Tests first: overlap reuse120/144X,108/144Y; near edit priority; dirty generation
rejection; load/unload/tint reset; stable packing; native multiple in-flight scene
generations and completion-only resource reuse. Run actual Vulkan movement/block
edit probes. Measure scan CPU and wall age, packing/prepare/publish CPU, GPU build
and tracing, edit-to-submission latency and frame p95/p99/max separately. Compare
the same fixture baseline and updated implementation; do not claim universal FPS.

The finite loaded scene and absent entity/fluid secondary geometry remain limits.
This addresses geometry freshness, not complete Iris/Eclipse parity or FSR4.
