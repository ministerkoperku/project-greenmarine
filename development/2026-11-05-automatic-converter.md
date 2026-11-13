# Automatic conversion continuation

Owner clarification: conversion must be automatic and reusable for other Iris
packs. Eclipse is the main complete acceptance case, with separately controlled
GreenMarine RT added after preserving its original effects. Never hand-patch the
generated Eclipse project or advertise the entire pack as working from stage
compilation alone.

## Implemented and verified

- Native scene passes accept an optional `vertex` resource and `vertices` count.
  Existing constructors and default three-vertex drawing remain compatible.
  Catalog snapshots require custom vertex sources transactionally; uniform ABI
  checks cover both endpoints, and pipeline caching distinguishes vertex sources.
- `IrisFullscreenAdapter` preserves original vertex calculations and matched
  varyings. It supplies the actual unit quad and source vertex indices, using
  six procedural triangle vertices and the Iris fullscreen matrix conventions.
- `IrisExecutableExporter` automatically emits native scene/compute pass graphs,
  per-program uniform resources, named targets and original shader assets when
  all their requirements are supported. Every source world also receives a
  candidate and explicit unmet requirements under `conversion/native/`.
  Unsupported geometry/shadow dispatch, dimensions, samplers/host inputs,
  render-state directives or resource semantics block selectable publication.
- `IrisNativeResources` lowers target formats/clears/extents, image/SSBO
  declarations, output mappings, pass phases, flips, mip requirements and compute
  extents. Unsupported semantics remain explicit diagnostics. It is independent
  of Minecraft classes. Resource tests cover the real owner Eclipse ZIP too.
- `IrisTerrainAdapter` adapts semantic attributes using the actual host inputs
  `Position`, `Color`, `UV0`, `UV2`, native matrices and terrain sidecar SSBOs.
  Geometry/tessellation keep Iris clip conventions until the last producing
  stage. Standalone acceptance covers ten real Eclipse terrain/water programs,
  35 stages across five populated world sets. This does not connect all Eclipse
  world programs to the native manifest executor yet.
- Native terrain draw binding and readback passed on the actual host Vulkan
  device. See `2026-11-04-terrain-draw-binding-evidence.md`. Later generated
  adapter integration exposed and fixed host attribute-name matching; the
  final decoder runtime rerun remains pending after the previous agent's limit.
- Sidecars now capture intrinsic block/fluid emission in byte offset 28, using
  the formerly reserved word. This supports `at_midBlock.w` independently of
  render type. Old packing callers retain zero emission through an overload.

## End-to-end automatic fixture

Original fixture source is under
`build/verification/automatic-converter-2026-11-05/source/`.
It was converted through `irisConvert` into the owned test game's external pack
directory, without modifying any generated shader. The generated manifest runs
host-color seed, original `composite4` and original `final`, including its source
vertex calculation. `AutomaticImportProbe` reads the resulting native target.

Accepted evidence:
`build/verification/automatic-converter-2026-11-05/runtime-format-verified/automatic-import-result.json`:
810000 pixels, three passes, zero mismatches, RGBA8_UNORM. The earlier runtime
fixture incorrectly interpreted RGBA8 bytes as floats; that rejected result is
retained separately. The corrected fixture checks the actual texture format and
rejects nonfinite values. This controlled unrelated pack is not Eclipse proof.

Final verification: root `./gradlew test build` passes 461 tests (13 skipped);
development-tools `../gradlew test build` passes 168 tests (seven skipped).
The additional render-state test reproduced false publication when blending
directives were ignored, then passed after unsupported state blocked emission.
No Rust sources changed. Existing source archive, installed game JARs, branch
and commits were not modified. The native fixture lives only in the owned dev
game under `build/verification/volume-game`.

Two rejected test launches raced shared Gradle output or the root JAR being
rebuilt while converter discovery read it. Subsequent coordinated full suites
above passed. Run only one Gradle test task for each project at a time; build
the root JAR before running converter tests against it.

## Remaining before full Eclipse acceptance

1. Connect automatic native world/terrain program emission, exact host pipeline
   selectors and capture activation to the tested semantic adapter and draw ABI.
2. Supply entity/item attributes, draw-specific inputs, depth aliases/conventions,
   imported shadow geometry/maps/matrices, and remaining sampler bindings.
3. Implement resource requirements the compiler diagnoses rather than bypassing
   them: relevant formats, clears, flip/mip behavior, custom texture upload,
   relative allocations/dispatch and aliases used by the actual source pack.
4. Implement per-dimension selection and option/profile rebuilds, preserving the
   original configured effects and optional integrations where actually present.
5. Regenerate the entire Eclipse from its original ZIP, exercise real fixed and
   moving scenes/options/dimensions, then add separately controlled GreenMarine RT
   and measure CPU/GPU costs independently. Keep full Eclipse non-runnable until
   required execution paths are present and distinguish that from GPU acceptance.

The owner asked how much remains after repeatedly reaching usage limits. Answered
plainly: several substantial implementation/integration stages and many sessions;
no reliable completion percentage or delivery date is established.
