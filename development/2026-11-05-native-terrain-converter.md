# Native terrain conversion continuation

The owner prioritizes the original Eclipse raster path through a reusable Iris
converter. GreenMarine RT additions remain deferred. Work continues in the
existing `main-development` linked checkout and preserves the earlier uncommitted
changes. The required parent `../AGENTS.md` is absent.

## Implemented

- Automatic conversion emits ordinary native world programs for the three actual
  Minecraft 26.3 terrain pipelines. Each pipeline receives its own source variant;
  original fullscreen programs present the resulting named world color without
  overwriting it with a host-color seed.
- `PackTerrain` stores ordered, immutable signed material mappings with exact
  block-state predicates. Conversion selects `block.properties` conditionals,
  preserves first-match precedence and distinguishes a missing file, whose Iris
  legacy defaults remain unsupported, from an intentionally empty file.
- Manifest-owned capture activates immutable worker mappings, separate host draws
  and storage descriptors, then rebuilds visible terrain. Switching packs and
  failed initialization release owned capture. Existing explicit Java capture
  callers retain their lifecycle.
- The adapter binds source atlas/lightmap semantics to actual host descriptors,
  lowers the atlas texture matrix, preserves separate AO when requested and
  multiplies raw tint by AO with alpha one for the default source setting.
- Cutout variants implement the original default `alpha > 0.1` behavior after
  the original fragment main, including early returns. Solid and water variants
  keep their respective alpha behavior.
- All resolved host requests are checked, including implicit fallbacks. A shared
  terrain/basic source cannot qualify unimplemented block/entity/hand draws.
  Missing legacy mappings, unsupported material directives, forward fragment
  depth requirements and intermediate world stages remain explicit blockers.
- Native capture rejects Sodium, OpenGL, OIT and wireframe execution; these paths
  have no complete capture adapter. Rejection closes the initialized script and
  prevents rendering instead of losing the failure state on the next tick.
- Real Eclipse conversion exposed a null sampler alias in a storage-image
  declaration. The exporter now reports unresolved samplers without throwing.

## Verification

Evidence root: `build/verification/raster-continuation-2026-11-05/`.

`terrain-runtime-accepted/automatic-terrain-result.json` verifies an unrelated
original Iris fixture regenerated entirely through `IrisImporter.convert`:
810000 readback pixels, 139400 matching solid pixels, 54480 cutout pixels,
197826 water pixels, zero nonfinite pixels and zero rejected-alpha leaks.
Separate MRT channels verify atlas and lightmap contributions for 391706 pixels.
Ordinary host draws activate capture without a fixture calling its configuration
API. Pack switching and an intentionally rejected OIT initialization both clean
up capture; the initialization rejection also leaves no live script.

`adapter-runtime-final/terrain-draws-result.json` verifies four signed sections,
1428 captured vertices, 112 sloped-fluid vertices and 45696 exact GPU bytes.
5040 generated-adapter vertex checks cover attributes, signed offsets, separate
AO and multiplied AO. All mismatches are zero. Worker/generation retirement,
nonzero host base vertices, compiler replacement, failed compile cleanup and
pack generation checks pass. This run uses its own fresh development game.

Final root `./gradlew test build`: 465 tests, zero failures/errors, 13 skipped.
Converter `../gradlew test build`: 175 tests, zero failures/errors, seven skipped.
`cargo test` in `engine/`: 17 passed, 14 explicit GPU tests ignored. Rust sources
were not changed. A read-only review found source-semantic and initialization
issues; a second review of the fixes found no outstanding Important or Critical
findings.

Rejected runs remain available. They include the previous adapter's host-name
failure, a fixture format mismatch, a camera falling out of view, a frame-lifetime
error in asynchronous verification and old decaying leaves in a reused test
world. The last case is why adapter acceptance now uses a separate fresh game.
The native lifecycle regression also caught misuse of `resetLevelRenderData`,
which tears down the host view area. Activation/release now use actual geometry
invalidation/rebuild. Its failure and subsequent passing runtime are retained.

The first optional real-Eclipse suite had exactly one failure:
`IrisTranslationTest.importerReportsWholePackReadinessWhenConfigured`, caused by
the null sampler alias above. Other optional whole-pack and terrain compilation
tests passed. Its XML results are retained in `converter-eclipse-red-results/`.

## Original Eclipse re-conversion

The final command completed successfully in 3m 7s:

```sh
cd development-tools
../gradlew test \
  --tests com.koper.greenmarine.development.iris.IrisTerrainAdapterTest.nativeTerrainProgramsCompileWhenPackConfigured \
  irisConvert -PirisPack=/home/koper/Pobrane/Eclipse-Shader-Unstable.zip \
  -PirisDestination=/home/koper/Projekty/greenmarine-dev/build/verification/raster-continuation-2026-11-05/eclipse-converted
```

The targeted test compiles ten original terrain/water programs (35 stages) across
five populated world sets. The regenerated whole-pack inventory contains 293
programs, including 21 disabled programs, 528 preprocessed enabled stages and
272 linked groups. Preprocessing/link failures are zero. All 434 core stages
compile; the 42 compilation failures belong to optional integrations. These
counts include all source world sets and differ from canonical IR-1's 250 stages.

All five native world candidates remain `executable=false`, with zero selectable
manifests and `runtimeReady=false`. Requirements include intermediate world
graphics stages, host geometry requests, host uniforms/samplers, source render
state, material layer rules, dimensions and the native manifest size limit.
These diagnostics identify missing execution; compilation does not bypass them.

Every one of the 757 preserved files was compared byte-for-byte with the original
ZIP after stripping its enclosing directory. The original archive SHA-256 is
`ff12a1b35d5f811c0dce6142527f500fc94a0f32c95ced37bd6186a5eb1bb934`.
The machine-readable check is `eclipse-verification.json` beside the generated
workspace; its full command output is `eclipse-final.log`.

## Remaining

Full Eclipse is not accepted or runnable. Continue imported intermediate world
stages, entity/item/hand attributes and draws, original shadow passes/maps,
fragment/depth-buffer conventions, required resource semantics and dimension/
option/profile selection. Regenerate from the original owner archive for each
full-pack acceptance attempt. Original source lighting must execute before RT
extensions are introduced. Source compilation and these controlled GPU fixtures
are separate from complete Eclipse visual acceptance.

No installed game JAR was replaced, no commit or release export was performed,
and no Android/Bionic runtime acceptance is claimed.

## Local PC diagnostic preview

At the owner's preview request, `build/previews/greenmarine-pc-raster-preview-2026-11-05.zip`
packages the built mod and the unchanged automatically converted diagnostic fixture.
It includes manual setup instructions and no runtime-probe classes. This is a
Linux/glibc x86_64 preview, not an Eclipse visual comparison or Android release.
The bundled JAR and native library were compared against their build outputs;
every ZIP entry was checked after packaging. ZIP SHA-256:
`bf16c91c59c479db29b39cba5198868b3e9fe7018a726ea6062bf0c2901d0276`.
