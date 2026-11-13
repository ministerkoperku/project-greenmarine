# Sampled depth continuation

Continues the existing Eclipse conversion plan, step 6. The task remains full
automatic Eclipse raster execution on Vulkan, followed by separate optional RT.
Preserve all previous uncommitted changes and original source assets.

## Current investigation

The latest whole-pack conversion compiles 434 core stages but rejects sampled
`depthtex0/1/2`. Native manifest inputs can only reference pack images/targets.
The host uses reversed depth. Merely aliasing the attached host depth would both
invert source semantics and create attachment/sampling hazards in world draws.
The actual host draws its hand after world post processing and clears its chosen
HUD depth attachment. Full original hand execution/order remains a separate
requirement; sampled depth support must not silently claim hand coverage.

## Implementation and verification

- [x] Add failing native input validation and automatic conversion tests.
- [x] Add owned full-resolution R32F depth snapshots with forward source values,
  initialized to 1 each frame, captured after opaque and after world drawing.
  Use explicit native input producers, shared by raster/compute/world programs.
  Sample only owned images outside the host depth attachment.
- [x] Regenerate an unrelated fixture and verify actual GPU values, opaque
  retention through water drawing, frame reset, and cleanup on pack changes.
- [x] Regenerate original Eclipse and retain remaining blockers; run root and
  converter test/build and portable Rust checks. Continue remaining world/hand/
  shadow requirements rather than treating this step as complete Eclipse.

Ruling: use physical R32F forward-depth snapshots rather than rewriting each
GLSL texture operation. This preserves texture/texelFetch/gather/size semantics
and avoids reads from a live depth attachment. The cost is a fullscreen depth
conversion per necessary host boundary and storage for requested snapshots.

Reference: https://shaders.properties/current/reference/buffers/depthtex/

Baseline: root `./gradlew test build` succeeds (up to date); portable Rust tests
pass. Converter baseline is running. Parent `../AGENTS.md` does not exist in
this workspace; the repository and supplied contributor instructions were read.

## Evidence so far

API tests observed three failures before implementation. The runtime discovered
the scheduler's separate producer validation and required COPY_DST usage for host
clear operations; both are corrected. A fresh independent review found a zero
vertex draw (host draw arguments are count/instances/first/first-instance) and
resource-free compute dispatches retaining zero/stale viewport dimensions.
The draw failure reproduced 418400 wrong world-depth pixels, zero world/opaque
differences and failing opaque/deferred/composite/water checks. The compute size
regression observed RED for 801x601 with local size 8x8 (expected 101x76 groups),
and resize to 320x180 (expected 40x23). Both fixes passed the root suite.

The original terrain-only depth reference disagreed at one pixel with the full
host opaque draw, which can contain nonterrain entities/particles. The controlled
fixture removes other entities, clears particles and hides HUD/hand; it now
compares all 810000 pixels without increasing tolerance or skipping pixels.
Rejected runs are retained. This proves terrain snapshots, not entity/hand parity.

`runtime-cleanup/automatic-terrain-result.json` passes on RX 9070 XT/RADV:
world, opaque, early, deferred, composite, water and compute mismatches are all
zero. It records 166015 water witness pixels, 166001 actual world/opaque depth
differences, zero invalid/nonfinite values and zero cutout leaks. Depth producer
lookups become unavailable after both a pack switch and rejected initialization.

The first full converter run had 192 tests, one failure and one explicit GPU
skip: `IrisTerrainAdapterTest.actualHostAtlasAndLightmapSamplersRetainBuiltinTextureCalls`
failed to load GraphicsSpirv while root JAR builds ran concurrently. That class
exists in the resulting JAR. The isolated rerun uses a fixed copy at
`build/verification/sampled-depth-2026-11-05/immutable-test-api.jar`; do not rewrite
a dependency JAR during a running converter suite. It is still in progress.

Ruling: keep hand/world ordering as an explicit unfinished requirement. Both
opaque snapshots share the existing actual host boundary until hand integration
is implemented; accepting full Eclipse from this fixture would be incorrect.

Review fixes are covered by the GPU RED-to-GREEN run and portable viewport test.
The minor compute/disposal evidence gaps raised by review are now covered by
actual compute readback and unavailable public depth lookups after cleanup.

Final root `./gradlew test build` succeeds with 477 tests, zero failures/errors
and 13 explicit skips. An additional RED-to-GREEN optional-feature regression
ensures packs without depth inputs do not query R32 capabilities or need a host
target. Portable Rust remains 17 passing tests and 14 explicit skips; no Rust
source changed and no new native release was required.

## Final original-ZIP verification

The isolated converter `test build irisConvert` run succeeds in 9m39s, with all
192 tests accounted for, zero failures/errors and one explicit GPU skip. The
actual owner-ZIP tests were enabled. Source verification compares all 757 files
against the ZIP bytes, with zero mismatches and SHA-256
`ff12a1b35d5f811c0dce6142527f500fc94a0f32c95ced37bd6186a5eb1bb934`.
All 434 core stages compile; preprocessing/link failures and native stage failures
are zero. The existing 42 optional integration failures remain. Every candidate
has all three depth sources and none has the old depth sampler requirement.

Latest workspace: `build/verification/sampled-depth-2026-11-05/eclipse`.
Its world0 candidate has 30 depth bindings (15 world, ten opaque, five no-hand),
and 386 remaining requirements versus the prior 416. Other candidates retain
352, 369, 356 and 386 requirements. These are individual diagnostics, not feature
counts or a completion percentage. Full Eclipse is still non-runnable: original
entity/hand/shadow programs, other resources and samplers, state, dimensions and
options must execute before acceptance. No selectable Eclipse manifest exists.

Verification receipt: `eclipse-verification.json` beside that workspace, generated
by `verify-eclipse.py`. `git diff --check` passes. No commit, publish, installed
JAR replacement or Android release export occurred. The complete owner task is
unfinished; this ledger only completes its sampled-depth step.
