# Iris fragment depth continuation

Continues step 6 of `2026-10-29-eclipse-conversion-plan.md` and the next action in
`2026-11-05-world-stages.md`. Owner authorization remains autonomous continuation
of the generic automatic converter. Preserve all previous uncommitted work.

## Rulings and scope

Implement source forward window depth on the actual reversed-depth Vulkan host.
Adapt fragment coordinate reads and fragment depth writes after semantic linking;
keep source code in its original convention until the final output. Use one
adapter for terrain and fullscreen programs, including early returns, helpers,
cutout discard and conservative depth declarations. Preserve comments, source
resource layouts and the no-depth-write case. Unsupported redeclarations must
fail explicitly. Do not infer sampled depth support: `depthtex0/1/2` require real
phase-correct host snapshots and remain separately blocked.

Alternative: rewrite every depth expression at its call site. Rejected because
compound writes, helper arguments and early returns are harder to preserve.
Chosen implementation: per-invocation source-coordinate helper and source-depth
variable; a final main wrapper converts the written depth exactly once.

## Implementation plan

Use `superpowers:executing-plans` inline with test-driven verification.

- [x] Add a failing converter behavior test for forward-depth terrain and
  fullscreen programs; add compiler tests for conservative qualifiers, early
  returns, no writes and comments. Implement `IrisFragmentDepth` in the converter
  iris package, then connect terrain/fullscreen adapters and remove the old gate.
- [x] Regenerate an unrelated original terrain fixture with independent depth
  proof outputs. Extend the owned runtime probe to compare actual Vulkan depth
  with the source result; verify solid/cutout/water and cleanup without editing
  generated shaders. Keep rejected evidence.
- [x] Reconvert the original owner ZIP, preserve source byte comparisons and
  precise unsupported requirements. Run root `./gradlew test build`, converter
  `../gradlew test build` and `cargo test` in `engine/` (no Rust edits planned).
  Review the scoped change and update the session handoff. No commit, publish,
  installed game modification or Android release export.

Review focus: source `gl_FragCoord` used as a whole vector or indexed; writes
through out/inout arguments; declarations without writes; conservative inequality
reversal; returns/discard inside wrapped source main; no new depth writes for
programs that only read interpolated coordinates.

## Implementation and review evidence

Six initial behavior tests failed against the previous converter. The shared
`IrisFragmentDepth` adapter now lowers source coordinate reads, detects static
depth writes from the compiler's SPIR-V DepthReplacing execution mode, preserves
source depth through out/inout calls and wraps actual fragment main definitions.
The wrapper executes after source returns; discard still terminates execution.
Depth declarations and invariant metadata remain on the actual host output,
including embedded comments. The forward host-depth adaptation modes do not
change fragment depth. Explicit coordinate-layout redeclarations remain blocked.

The independent review found valid main prototypes and invariant output metadata
were rejected, and embedded declaration comments were lost. New regressions
reproduced those failures, then passed after definition-only renaming and
metadata protection. Conservative output bounds also account for floating-point
round-trip error: source greater maps to a host minimum, less to a maximum,
unchanged uses the exact host interpolated value. The independent follow-up
confirmed the bound directions and static DepthReplacing interpretation.

First actual RX 9070 XT/RADV Vulkan terrain run:
`build/verification/fragment-depth-2026-11-05/runtime/automatic-terrain-result.json`
verifies 341923 forward-coordinate/write pixels against independent noperspective
source-depth interpolation and 186536 actual D32_FLOAT host depth pixels. Both
source early-return/fallthrough paths, cutout discard, atlas/lightmap descriptors,
pack-switch cleanup and failed-initialization cleanup pass, with zero mismatches.
Water proof includes its source writes; the host translucent pipeline intentionally
has depth writes disabled, so water is excluded from stored host-depth checks.

An expanded fullscreen proof was correctly rejected because its fixture used a
single-line block format directive, leaving the target RGBA8. The original test
source now uses the supported multiline format directive; a converter regression
reproduced the bad target before the fixture correction. No generated asset was
patched. The final fixture includes conservative terrain depth and an independent
fullscreen source clip Z of -0.5 (expected forward window Z .25, source output
depth .375). Its complete runtime acceptance is recorded below when available.

The first full owner Eclipse regeneration preserves all 757 source files
byte-for-byte and compiles all 434 core stages. All five candidates now contain
three native terrain programs and have no native compile failures or old
FRAGMENT_DEPTH_CONVENTION diagnostics. They remain non-executable; required depth
snapshots, entity/hand/shadow dispatch, resource/state and dimension/option
semantics remain. The earlier 42 optional integration stage failures persist.

## Final Vulkan acceptance

`build/verification/fragment-depth-2026-11-05/runtime-accepted/automatic-terrain-result.json`
passes on RX 9070 XT/RADV. Out of 810000 readback pixels, 345835 independently
verify forward fragment coordinates and source depth writes (138251 solid,
51048 cutout, 156536 water). 189299 stored host D32_FLOAT depths match the source
result reversed to host convention. Early returns cover 172907 pixels and
fallthrough covers 172928. The separate fullscreen pass verifies exactly .25
forward coordinate depth and .375 source output depth for all 810000 pixels.
Depth, fullscreen, nonfinite and cutout-leak mismatch counts are all zero;
atlas/lightmap bindings and both lifecycle cleanup checks pass.

The conservative-depth runtime first found a real integration bug: the named
world-output mapper interpreted built-in gl_FragDepth as an unlocated color
output. A new root behavior regression failed first, then passed when the mapper
excluded that exact built-in declaration without accepting fake color outputs.
The rejected runtime and crash report remain under `runtime-final`/`game-final`.
Acceptance used a fresh game with a byte-verified copy of all 47 automatically
generated fixture files; no generated shader or manifest was edited.

The final independent review has no remaining Important or Critical findings.
Additional combined invariant/precise metadata and placeholder-like comment text
were reproduced with failing regressions and fixed before final verification.

The final root test suite passes 471 tests with zero failures/errors and 13
explicit skips. Rust `cargo test` passes 17 portable tests with 14 explicit GPU
skips; Rust sources were not changed. The full converter suite also passes with
the actual owner archive configured, including whole-pack coverage, automatic
importer readiness reporting and original terrain adaptation tests: 191 tests,
zero failures/errors, one explicit GPU skip.

## Next depth-resource boundary

The [primary Iris depth-buffer contract](https://shaders.properties/current/reference/buffers/depthtex/)
distinguishes all geometry, no transparent geometry, and neither transparent nor
hand geometry; the buffers clear to forward depth one and do not flip. Existing
`OpaqueScene` captures a borrowed host-depth snapshot before deferred transparency,
but does not by itself implement these three source resources. Current host water
draws have depth writes disabled, as the acceptance test explicitly accounts for.
Do not publish all three aliases as the same main-depth view, or sample an active
depth attachment. Plan source-correct depth ownership/state and phase snapshots
together, keep hand/entity boundaries explicit, and verify source values after
forward conversion. Original depth aliases remain blocking diagnostics meanwhile.

## Reproduction

Run the root build before converter tests consume its JAR. Avoid overlapping
root JAR replacement with converter test/CLI discovery.

```sh
./gradlew test build
(cd engine && cargo test)
(cd development-tools && ../gradlew test build irisConvert \
  -PirisPack=/home/koper/Pobrane/Eclipse-Shader-Unstable.zip \
  -PirisDestination=/path/to/new/eclipse-workspace)
(cd development-tools && ../gradlew automaticTerrainFixture \
  -PterrainStages=depth -PterrainSource=/path/to/new/fixture-source \
  -PterrainDestination=/path/to/fresh/game/shaderpacks/greenmarine/automatic-terrain)
./gradlew -PruntimeProbe -PautomaticTerrainProbe -PfragmentDepthProof \
  -PprobeRunDir=/path/to/fresh/game -PprobeOutput=/path/to/evidence runClient
```

Fresh game directories avoid persisted imported-pack selections after rejected
runtime runs. Evidence scripts and logs stay under
`build/verification/fragment-depth-2026-11-05/` and the adjacent
`build/verification/fragment-depth-*.log` files. Source ZIP and generated Eclipse
assets remain local and ignored. No commit, publish, installed JAR replacement
or Android release export is part of this continuation.

## Final verification result

Root `./gradlew test build` succeeds after the runtime mapper fix; its 471 tests
have zero failures/errors and 13 explicit skips. Converter `../gradlew test build
irisConvert -PirisPack=...` succeeds in 9m30s with 191 tests, zero failures/errors
and one explicit GPU skip. This run enabled the real-pack optional tests rather
than skipping them. Rust remains 17 passing portable tests and 14 explicit skips.
`git diff --check` passes. No Rust sources changed.

Latest owner-ZIP workspace:
`build/verification/fragment-depth-2026-11-05/eclipse-final`.
`eclipse-final-verification.json` records 757 preserved originals, zero byte
mismatches and archive SHA-256
`ff12a1b35d5f811c0dce6142527f500fc94a0f32c95ced37bd6186a5eb1bb934`.
All 434 core stages compile; preprocessing/linking failures are zero. The same
42 optional integration stage failures remain. All five native candidates contain
three terrain programs with zero native-stage compilation failures and no old
fragment-depth-convention blocker. All five remain non-executable and no selectable
Eclipse manifest was generated. Full Eclipse gameplay/visual acceptance remains
outstanding, separately from the accepted unrelated Vulkan fixture.
