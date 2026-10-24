# Compute API: evidence (2026-10-25)

Branch `main-development`, GreenMarine 0.9.11-prototype. Follows
`2026-10-25-handoff-api-parity.md` step "storage + compute + 3D", with the owner's
direction made explicit: the target is the **GreenMarine pack API** (people writing GM
packs get compute), not an Iris runtime. The Iris converter will emit these same
features later.

## What exists

- Host extension: `HostStorageMixin` adds VK storage usage (image 0x8, buffer 0x20) in
  `VulkanConst.textureUsageToVk/bufferUsageToVk` only inside `HostStorage.create`, so
  host attachments keep their flags (no lost Adreno framebuffer compression).
- `ComputeBackend` (Java/LWJGL, internal): shaderc → SPIR-V (perf opt + debug info so
  OpName survives), spvc reflection (names, set 0, bindings, local size; rejects 3D,
  arrays, push constants, separate samplers), push-descriptor compute pipelines,
  dispatch on a transient host command buffer between full memory barriers (GENERAL
  layout), destruction through the host queue. Capability = real self-test pipeline.
  Recording stays in Java, not the Rust engine: it's thin glue on Renderpearl objects and
  must work without the native library.
- Public Java: `ComputeProgram`, `ComputeHandle`, `ComputeBindings`,
  `RenderSession.compute/storageTexture/storageBuffer/dispatch`, graph
  `storageTexture` + `PassContext.dispatch/storage` (access checked against declared
  reads/writes), `MarineRenderer.computeCapabilities()`,
  `RenderCapabilities.compute` (+ Lua `frame.capabilities.compute`).
- Pack schema v1 (additive): `resources.images/buffers`, `"stage":"compute"` passes
  (`compute`, `groups`), binding by name, pack images as scene-pass `inputs`,
  `"requires":"compute"`. CLI validator understands it. Docs: `docs/COMPUTE.md`.

## Verified

- `./gradlew test build`: 282 tests, 0 failures (new: `PackComputeTest`,
  `ComputeReflectionTest`, which runs real shaderc + spvc natives).
- `python3 -m unittest tools/test_pack_authoring.py`: OK.
- Runtime, Minecraft 26.3 client, lavapipe (llvmpipe, Mesa 26.0.3) under Xvfb:
  `./gradlew -PruntimeProbe=true -PcomputeProbe=true runClient` → `compute-result.json`
  `status: complete`:
  - session API: compute A fills an SSBO, compute B unpacks it into an `image2D`, a graph
    compute pass inverts it into a graph `storageTexture`, a raster pass samples that:
    256/256 texels exact at all three readbacks.
  - pack: compute pass with `groups: image:Lut`, `#include`, `MarineFrame`, an SSBO
    counter and an `rgba8` pack image sampled by the next fragment pass through
    `inputs`: LUT exact, counter = 60 = dispatches = scene frames, MarineFrame width
    = 960 = scene width.
  - 39 storage-capable formats reported on lavapipe.

## Not verified / next

- Adreno (owner's phone) and RX 9070 XT runs; no timing claims (the 28 ms record time
  in the probe includes first graph allocation on lavapipe).
- Storage buffers/images in raster stages (Renderpearl reflection/layout/push mixins:
  handoff steps 1-3) — needed for Iris SSBO packs and for fragment passes reading
  pack buffers directly.
- Dispatch batching under one barrier pair; indirect dispatch/draw; 3D images, arrays,
  mips; compute in world programs.
