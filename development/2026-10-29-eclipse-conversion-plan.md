# Eclipse conversion execution

## Owner clarification: 2026-11-04

The deliverable is an automatic, reusable Iris shaderpack converter, with Eclipse
as the primary complete runtime acceptance case. Do not hand-author or patch the
generated Eclipse pack. Every accepted test must regenerate from original source
through the same generic rules used for unrelated packs. Preserve the original
effects; add GreenMarine RT as a separately controlled extension after baseline
execution works. Do not mark a pack runnable while required stages/resources or
integrations remain unsupported. No source-name-specific shortcuts.

Current parallel implementation is divided at independent boundaries:
host terrain draw bindings, semantic source adapters, native resource lowering;
the root owns automatic native manifest emission and execution integration.
Full-screen source vertex programs must execute their actual code and quad
semantics, not be discarded in favor of a canonical triangle vertex shader.

The owner requests an editable GreenMarine conversion of their local Eclipse ZIP,
preserving all effects, and authorizes expanding the translator and API wherever
needed. This continues the existing IR-1 through IR-7 design and later API-parity
handoff; Vulkan is the acceptance backend. Work stays in the owner's current
main-development checkout. Source Eclipse files and generated derivatives stay in
ignored local verification/workspace output and are never committed or published.

## Execution order

1. Run the existing importer against the actual owner ZIP and retain its report.
   Establish exact missing runtime capabilities without dropping source stages.
2. Link translated stage interfaces and shared descriptor/uniform layouts, with
   tests for differently ordered declarations, matrices/arrays and intermediate
   geometry/tessellation stages. Re-run whole-pack translation and compilation.
3. Add genuine host-device 3D storage/sampled images for LPV: public resource
   specification, host image/view creation, reflection and binding validation,
   pack declarations and three-dimensional dispatch sizing. Preserve all 2D APIs.
   Verify compute writes and reads multiple depth slices on the host GPU.
4. Emit translated whole-program assets and pipeline/resource metadata into an
   editable conversion workspace, recording compile, link and runtime coverage
   separately. Preserve custom textures, includes, settings and original licenses.
5. Extend native pack execution for named MRT targets and their format, clear,
   flip, history and mip semantics; supply Iris uniform data without changing the
   288-byte MarineFrame contract. Test real multistage raster/compute chains.
6. Extend world capture/execution for the required terrain/entity/material
   attributes and MRT, then shadow passes and optional stage/integration support.
7. Run the full converted Eclipse on Vulkan, compare fixed scenes and movement,
   exercise options/dimensions, and package the local converted pack only after
   complete runtime acceptance. Do not substitute a simplified visual effect or
   equate source compilation with full runtime feature preservation.

## Validation

Use failing behavior tests before each production change. Root Java and Rust
checks follow AGENTS.md; development-tools has its own Gradle tests/build and
opt-in whole-pack test. Native resource changes also require actual Vulkan
readback/runtime evidence. Use the actual host device and command encoder; only
GreenMarine-owned resources may be destroyed. Record each completed step and
remaining dependencies below so interrupted work can resume accurately.

## Execution ledger

- 2026-11-05: sampled depth inputs now execute through owned forward R32F images
  at the actual opaque/world boundaries. Regenerated unrelated Vulkan fixtures
  verify all 810000 pixels across early/deferred/composite/water/compute reads,
  real host depth and independent terrain references, plus pack/failure cleanup.
  Whole-owner-ZIP regeneration preserves 757 originals and compiles 434 core
  stages; old depth sampler blockers are absent in all five candidates. Full
  Eclipse remains non-runnable. Original hand ordering, nonterrain/shadow draws
  and remaining resource/state/dimension/option requirements remain. Root477,
  converter192 (real ZIP enabled) and portable Rust17 pass. See
  `2026-11-05-sampled-depth.md`; no installed game JAR or Android export changed.

- Baseline: clean main-development at e04a7fe; previous root Java/Rust/Python
  suites pass. Existing IR-1: 250/250 default stages; integrations 28/50.
- Design choice: extend the native GreenMarine API and output editable packs,
  as requested in the existing owner handoff. No external shader runner and no
  replacement of Eclipse lighting algorithms.
- Existing importer baseline ran on the owner's ZIP and returned runnable=false,
  preserving all sources under build/verification/eclipse-conversion-2026-10-29/baseline.
- Linked canonical Eclipse program compilation now passes 250/250 stages in 129
  programs, with shared UBO/descriptors and explicit stage interfaces. This still
  does not prove native world/shadow execution.
- Pipeline inventory covers five populated dimension/program sets (570 stages),
  including Aether/Twilight variants omitted from canonical IR-1 coverage. The
  existing property file also declares an empty worldx mapping; preserve/diagnose it.
- 3D schema/reflection tests observed RED then GREEN. Actual VolumeProbe on the
  RX 9070 XT Vulkan host passed 96 voxels across three slices: compute imageStore,
  sampler3D compute read, sampler3D raster read and full-volume zero clear all had
  zero mismatches. Evidence: volume-runtime-unsigned-fixed/volume-result.json.
- Runtime caught two real integration problems and both were corrected: the host
  clear method requires a render attachment, so volume zeroing now records a
  transfer clear; current RADV reports uint32 MAX workgroup count X, so cached
  Java limits now clamp unsigned values to the positive Java dispatch range.
  The latter has an observed failing then passing portable regression.
- Additional resource dependency: Eclipse uses a 2048-texel 1D RG32UI block table;
  extend the same owned host-image path to 1D before claiming resource parity.

- 2026-10-30: 1D RG32UI block-table resources and 3D W-addressing now pass actual
  GPU readback. The initial W-boundary probe reproduced REPEAT rather than CLAMP;
  extending host V addressing to W corrected both boundaries without changing 2D
  sampling. Volume/line probes have zero mismatches.
- Native named-target MRT/feedback supports mixed float/uint attachments, typed
  clear, retained pass snapshots and per-frame clears. Actual multiframe MRT test
  passed; world-family MRT, source flip/mip semantics and phase scheduling remain.
- UniformLayout, bounded UniformExpressions, explicit per-pass uniform resources,
  immutable transient GPU uploads and real IrisFrameInputs are implemented.
  Actual shader ABI reflection validates declarations before binding, and the
  actual physical device limits the UBO range. Manifest compute plus four scene
  passes passed real Vulkan readback. Separate same-encoder A/B payload reuse/free
  passed too. These do not yet execute custom shader.properties expressions in
  imported passes or supply per-draw/shadow inputs.
- Host coverage: 48 of the default expression set's 51 external host references;
  three DH references remain unsupported. Native DH and Iris use different far
  formulas, so scalar substitution would reconstruct the wrong depth. Bind real
  DH depth/projections and respect readiness before integrating those values.
- Current converted workspace remains runtimeReady=false. Required next work:
  imported phase scheduling; world/entity attributes and MRT; shadow passes;
  geometry/tessellation execution; custom expression/option wiring; actual DH
  matrix/depth integration and optional host adapters. Preserve original source
  licenses and do not publish a simplified pack under the Eclipse name.
