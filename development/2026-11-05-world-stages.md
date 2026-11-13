# Native intermediate world stages

Continuation of `2026-10-29-eclipse-conversion-plan.md`, execution step 6.
The owner requested continued complete Eclipse conversion. Existing decisions
remain binding: generic automatic conversion from original source, original
raster effects before RT, actual host Vulkan draws, no installed JAR changes.
The existing linked `main-development` worktree and all uncommitted work remain.
The parent `../AGENTS.md` is absent.

## Execution ledger

Ruling: continue the established design and plan without reopening design
approval. Add geometry/tessellation to ordinary manifest-owned world programs,
preserving the existing constructors and endpoint behavior. Surface replay and
background need separate adapters and must reject intermediate declarations.

Pre-flight: the converter already adapts all terrain stages and the backend
executes intermediate stages for `ShaderProgram`, but manifest/catalog/world
compilation currently drops or rejects them. Connect those interfaces, capture
all stage sources transactionally, and validate intermediate uniform ABI too.
Tessellation input patch size is independent of TCS output size; automatically
converted ordinary terrain uses the host's triangular index stream (3 vertices).
Other primitive requirements must remain explicit failures.

Tasks:
1. Validate and snapshot manifest-owned intermediate world stages.
2. Route those stages through existing host-device compilation, descriptor
   discovery and exact uniform ABI checks; emit them in generic conversion.
3. Regenerate an unrelated fixture with stage-specific output; verify real
   terrain/cutout/water draws and actual GPU readback.
4. Reconvert the original Eclipse ZIP and retain precise remaining blockers;
   run full Java/converter/Rust verification and independent code review.

Baseline root `./gradlew test build` succeeded (all tasks up to date).
Evidence directory: `build/verification/world-stages-2026-11-05/`.

## Implementation and observed verification

Task 1: complete. Three new behavior tests failed against the old parser/catalog:
missing stages were published and invalid tessellation/background/surface
declarations were ignored. The catalog now loads every required intermediate
source and include atomically and checks registered snapshots. Older public
constructors retain their original meaning.

Task 2: complete. The original converter fixture failed with explicit
`WORLD_GRAPHICS_STAGES` requirements before emission was added. A separate
real-world launch then reproduced the missing world-to-graphics compiler route
(host rejected the vertex interface block). World sources now provide the
complete chain to the existing backend, use actual host capability checks and
validate intermediate-only uniform layouts before pipeline creation. Two
compiler tests cover include snapshots, stage-only discovery, invalid uniform
ABI and failed-build scope cleanup. Unsupported input primitives remain blocked.

Task 3: complete. Fixtures regenerate through `IrisImporter.convert` and preserve
their generated output without hand edits. Actual world renders use captured
host terrain vertices and normal host draws. Geometry writes an independent
proof value of 0.5. Tessellation control adds 0.125, evaluation adds 0.25 and
geometry adds 0.5, giving an independently checked 0.875. These uniforms are used
only by intermediate stages. Neither test configures capture directly.

`geometry-accepted/automatic-terrain-result.json`: 518400 pixels, 89257 solid,
37379 cutout and 102456 water pixels; 229092 exact stage-proof matches, zero
stage mismatches, nonfinite pixels or cutout leaks. Atlas/lightmap evidence and
pack-switch/failed-initialization cleanup pass.

`tessellation-accepted/automatic-terrain-result.json`: 518400 pixels, 95176 solid,
27725 cutout and 99370 water pixels; 222271 exact stage-proof matches, zero stage
mismatches, nonfinite pixels or cutout leaks. The same cleanup and independent
sampler checks pass. Both run on RX 9070 XT/RADV through actual Vulkan host draws.

Rejected results are retained. The first runtime proves the compiler route was
missing. A second run used the initial fixture's default RGBA8 proof target and
was rejected by the format check; the fixture source now explicitly declares
RGBA16F and was reconverted before acceptance. An attempted reconversion into
an existing directory was correctly rejected; subsequent conversion used a new
destination. Original source and generated shaders were never hand-patched.

Root `./gradlew test build`: 470 tests, zero failures/errors, 13 skipped.
`cargo test` in `engine/`: 17 passed, 14 explicit GPU cases ignored. Rust sources
were not changed. Converter full-suite, original Eclipse regeneration and
independent review are being finalized below.

## Original Eclipse regeneration

The full owner ZIP was reconverted with the generic importer, plus the original
terrain adapter compilation test, in 3m 6s. `eclipse-verification.json` records
757 byte-identical preserved originals and archive SHA-256
`ff12a1b35d5f811c0dce6142527f500fc94a0f32c95ced37bd6186a5eb1bb934`.
All 434 core stages compile; all 42 compilation failures are optional
integrations. Preprocessing and ABI linking have zero failures.

The previous `WORLD_GRAPHICS_STAGES` blocker is absent in all five native world
candidates. All still have `executable=false` and zero selectable manifests.
Removing the earlier stage gate exposes forward fragment-depth requirements
also in `gbuffers_terrain`; terrain/cutout/water remain blocked on those depth
semantics, while entity/shadow/dimension/state/resource requirements remain.
Do not treat the generic GPU fixtures as full Eclipse visual acceptance.

## Review and authoring follow-up

The independent scoped review found no Important or Critical issues. Its minor
request was a three-input patch with a different TCS output count. The fixture
now includes a four-output TCS and consumes its fourth value in TES while the
native manifest retains three input vertices; GPU verification follows below.

Ruling: integrate these source extensions into the existing development editor
and validators before delivery. Without this, required generated stages cannot
be opened and invalid edits pass the development reload preflight. This is
necessary for the already requested editable conversion, not a new renderer.
Two authoring regressions reproduced both failures before implementation.
GLSL compiler stages, source extension selection, workspace editability, project
validation and reload validation now include geometry/tessellation. Intermediate
Slang compilation remains explicitly unsupported. Focused authoring tests pass.

Review set-asides: unrelated pre-existing changes are preserved; full Eclipse
execution remains incomplete and Android acceptance is not claimed. No installed
game JAR was replaced, no commit/publish/export performed and no Android release
artifact was produced.

## Final verification

`tessellation4-accepted/automatic-terrain-result.json` passes on the real host
Vulkan world: 810000 pixels, 145177 solid, 44774 cutout and 156453 water pixels;
346404 exact stage-proof matches, zero mismatches/nonfinite values/cutout leaks,
and both cleanup checks pass. Its TCS output count is four, the host input patch
size is three and TES consumes the fourth output's proof. This addresses the
reviewer's minor test request. Together the three runtime fixtures verify 797767
world pixels with exact intermediate-stage proof values.

The authoring follow-up's independent review also found no Important or Critical
issues. Final development-tools `../gradlew test build` passes 179 tests with
zero failures/errors and seven explicit skips. The root full build remains
470 tests with zero failures/errors and 13 explicit skips; portable Rust remains
17 passing with 14 explicit GPU skips. `git diff --check` passes.

Reproduction (from the repository root unless indicated):

```sh
./gradlew test build
(cd engine && cargo test)
(cd development-tools && ../gradlew test build)
(cd development-tools && ../gradlew test \
  --tests '*IrisTerrainAdapterTest.nativeTerrainProgramsCompileWhenPackConfigured' \
  irisConvert -PirisPack=/home/koper/Pobrane/Eclipse-Shader-Unstable.zip \
  -PirisDestination=/path/to/new/eclipse-workspace)
(cd development-tools && ../gradlew automaticTerrainFixture \
  -PterrainStages=tessellation4 -PterrainSource=/path/to/fixture-source \
  -PterrainDestination=/path/to/dev-game/shaderpacks/greenmarine/automatic-terrain)
./gradlew -PruntimeProbe -PautomaticTerrainProbe -PworldStageProof=0.875 \
  -PprobeRunDir=/path/to/dev-game -PprobeOutput=/path/to/evidence runClient
```

Use fresh destinations for conversion. The `geometry` fixture expects a proof
value of 0.5; `tessellation` and `tessellation4` expect 0.875. Controlled runtime
fixtures are separate from Eclipse gameplay acceptance. Next: generic forward
fragment-depth semantics and actual depth aliases, followed by original entity/
hand/shadow and remaining resource/dimension/option execution. RT remains deferred.
