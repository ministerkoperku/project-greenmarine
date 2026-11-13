# Actual terrain draw descriptors

Opt-in `TerrainDrawBindings` now associates each real host draw with capture by
borrowed vertex-buffer identity, layer and actual base vertex. Minecraft 26.3
bytecode provides `ChunkSectionsToRender.DrawSeparate` with a per-draw uniform
callback. The integration selects this existing host path while opted in and
adds storage descriptors through the callback, preserving host vertex/index
buffers, first indices, base vertices, transparency ordering and ChunkSection
uniforms. It does not rewrite mesh vertices or index sorting.

The public shader contract is documented in `docs/TERRAIN-ATTRIBUTES.md`.
Missing or retired captures bind initialized storage with readiness false.
Owned metadata lives with each captured layer and retires alongside its GPU
records on the render thread. Separate draws replace indirect batching for
opted-in packs; the performance tradeoff has not been benchmarked.

The initial new Java regression failed compilation because the expected
`TerrainDrawBindings` API did not exist (also observed by the root build), then
passed with the implementation. Targeted command:

```
./gradlew test --tests com.koper.greenmarine.client.terrain.TerrainDrawBindingsTest
```

Actual AMD Radeon RX 9070 XT / RADV Vulkan acceptance:

```
./gradlew -PruntimeProbe -PterrainDrawsProbe \
  -PprobeRunDir=build/verification/volume-game \
  -PprobeOutput=/home/koper/Projekty/greenmarine-dev/build/verification/terrain-draw-bindings-final \
  runClient
```

`build/verification/terrain-draw-bindings-final/terrain-draws-result.json`
reports complete with zero mismatches. Four signed sections contain 1,408
captured vertices, including 584 fluid, 112 sloped-fluid, 604 tinted and 28 AO
vertices. Actual normal world draws invoked 66,033 descriptor callbacks before
verification. The fixture borrows actual host terrain uber buffers and uses
actual allocation base vertices, reversed verification indices and first index
5. Seven section layers produce 1,264 exact vertex-stage material/UV midpoint/
host-position pixel checks through the same draw callbacks. Four retired draws
produce 568 readiness-false pixel checks. Eleven original draw callbacks are
preserved. Worker retirement, metadata retirement, pack/compiler generations and
failed-compile scope cleanup all pass.

The owned development client stopped normally. No installed game JAR, export,
release installation or Rust source changed for this binding work. This is
native terrain draw evidence, separate from complete Eclipse world/shadow
acceptance, optional RT acceptance or Android runtime evidence.
