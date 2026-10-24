# PC test plan (RX 9070 XT), 2026-10-26

For the owner's desktop run of `main-development` (GM 0.9.11-prototype: compute API,
raster storage, player-shadow fix). Everything below was verified only on lavapipe
on the phone so far; the PC run is the first hardware-RT and real-GPU evidence.
Assumes Linux x86_64 with Java 25, Rust and `glslangValidator`/`spirv-val`.

The phone builds only aarch64/Android natives, so build on the PC:

```sh
git fetch && git switch main-development && git pull
./gradlew test build                                  # 282+ tests, jar with linux-x86_64 native
(cd engine && cargo test && GREENMARINE_GPU_TEST_DEVICE="RX 9070 XT" cargo test -- --ignored --nocapture)
python3 -m unittest discover -s tools -p 'test_*.py'
```

## 1. Compute + raster storage (new, most important)

```sh
./gradlew -PruntimeProbe -PcomputeProbe runClient
cat run/probe-output/compute-result.json
```

Pass = `"status": "complete"`. Check in the JSON:

- `computeCapabilities.compute/storageBuffers/storageImages` true, and the four raster
  flags (`rasterStorageReads`, `fragmentStorageWrites`, `vertexStorageWrites`,
  `formatlessStorageImages`). RADV should report all of them.
- `api.mismatches` 0, `api.rasterStorageMismatches` 0, `api.fragmentAtomicCount` 256,
  `api.fragmentStorageMatches` 256.
- `pack.lutMismatches` 0, `pack.counterFrames` = `pack.fragmentHits` = `packComputeDispatches`
  = `sceneFrames`, `pack.fragmentLutMatches` = `fragmentHits`.

If it fails: the log has `Compute probe failed` with the stage; also send
`logs/latest.log` lines with `Compiled pack compute` / `Couldn't compile pipeline`.

## 2. Regressions (the new mixins touch every pipeline compile and descriptor push)

```sh
./gradlew -PruntimeProbe -PrenderApiProbe runClient   && python3 tools/verify-render-api.py run/probe-output
./gradlew -PruntimeProbe -PnamedPassProbe runClient   && python3 tools/verify-named-passes.py run/probe-output/result.json
./gradlew -PruntimeProbe -PhardwareRtProbe runClient  && python3 tools/verify-hardware-rt.py run/probe-output
./gradlew -PruntimeProbe -PworldProgramsProbe runClient && python3 tools/verify-world-programs.py run/probe-output
./gradlew -PruntimeProbe -PsolsticeProbe runClient    && python3 tools/verify-solstice.py run/probe-output
```

(The verify scripts take the output folder, named passes takes its `result.json`;
clear `run/probe-output` between runs.)

## 3. Shadows vanishing while moving (owner report from 2026-10-24)

Symptom (Solstice RT, 9070 XT, modded game): walking from place to place, shadows were
gone for about a second, then came back together with a small hitch. Owner's
correction: probably not only the player, the whole scene looked unloaded (no shadows
anywhere), not confirmed visually.

Two separate causes, one fixed and one only instrumented:

1. **Player only (fixed):** while the GPU is busy, `RayWorld.renderSurface` skipped frames
   ("RT submission slots busy") but kept `playerShadowTraced` from the last traced
   frame, so `EntityShadowMixin` removed the vanilla blob with no RT shadow drawn.
   Now cleared up front; a skipped frame gives the blob back.
2. **Whole scene (instrumented, not changed):** the RT scene (96×64×96 around the
   camera's section) republishes only once *every* section in the new bounds is
   scanned (`publicationSnapshot`). Late or heavy chunks (mods) can keep that pending
   while the camera walks toward the old scene's edge, where traced shadows fade out.
   The publish (BLAS/TLAS swap) would be the hitch.

Test: play Solstice RT on the usual modded instance, walk/sprint/fly across fresh
terrain for a few minutes, then grep `logs/latest.log` for:

- `RT scene lagging: camera N blocks from the published scene edge; pending sections=…,
  preparing=…, prepared=…, last publish … ms ago`: what held the scene back. Pending
  sections > 0 means the scan/chunk wait; `preparing=true` means the worker build;
  `prepared=true` means the publish is blocked on busy submission slots.
- `RT scene caught up after N ms (...)`: how long the shadows were degraded.
- `Surface RT skipped N frame(s) in a row` / `Surface RT resumed after N skipped
  frames`: GPU stalls around the publish (the hitch).

Send those lines (and whether shadows still vanish). The fix for (2) depends on which
counter is the culprit: publishing partial scenes instead of waiting for every section,
moving the anchor with hysteresis, or making the publish itself cheaper.

## 4. Feel / performance

- Solstice RT and Corona RT, F6 profiling on: CPU/GPU pass times before (0.9.10 jar)
  and after (this build) at the same spot; the storage mixins should be noise.
- Any new validation errors with `VK_LAYER_KHRONOS_validation` enabled are worth a
  log excerpt, especially around `vkCmdPushDescriptorSetKHR` and compute barriers.
