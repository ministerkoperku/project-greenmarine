# Interrupted Eclipse conversion continuation

Recovered the latest root session and three interrupted tasks from 31 October.
Continue the existing Eclipse conversion and terrain sidecar plans in this linked
worktree on `main-development`; preserve all previous uncommitted work.
The required parent `../AGENTS.md` is absent; repository instructions apply.

## Verified so far

- The first build reproduced an interrupted test edit: LWJGL's physical-limit
  constructor expects a native address. Corrected the test allocation call.
- Full Java suite then reproduced two unfinished regressions: geometry output
  limits omitted built-in components, and background programs could share an
  incompatible named world pass. Both tests failed before production fixes.
  Count declared geometry built-in outputs and reject that background combination.
- `./gradlew test build` passes after these fixes (455 tests, 13 skipped).
- `cargo test` passes: 17 tests, 14 explicit GPU tests ignored.
- Fresh host Vulkan world MRT readbacks pass for both borrowed main color plus
  two named targets and owned main color with eight named attachments.
  Both have zero mismatches. Evidence is under
  `build/verification/continuation-2026-11-04/`.
- The 31 October graphics-stage probe already has successful real GPU evidence;
  the fresh `graphics-stages` run also passes four geometry/tessellation draws,
  depth/no-depth variants and intermediate-only uniforms with zero mismatches.
- Development-tools `../gradlew test build` passes: 130 tests, five skipped.
- Terrain fixture first failed with no captured records. Registering its seven
  mixins then passed: four signed sections, 1408 vertices, 584 fluid vertices,
  112 sloped-fluid vertices and 45056 GPU bytes, zero mismatches.
- Extended fixture verifies 604 untampered tinted vertices, 28 vertices with
  nontrivial AO and generation-driven GPU retirement.

## Current task

Terrain capture classes and mixins were left unregistered and unverified.
Added a real mesher/Vulkan-buffer readback fixture first. It checks actual block
mapping, arithmetic UV centers, signed block-center offsets, fluid slopes and
unchanged host vertex order across positive/negative sections.
The hooks are enabled. Independent review found two important lifecycle defects:
the host releases old meshes on workers, and actual compiler/model replacement
uses `invalidateCompiledGeometry`, not just `resetLevelRenderData`.
Real GPU worker-release regression failed before the fix; destruction is now
queued for render-thread collection with immediately visible CPU retirement.
The real host lifecycle fixture also failed both compiler-generation and
exception-cleanup checks. Added a compiler replacement hook and a finally-wrapped
compile scope. Shader draw binding remains a separate step.

## Final verification and next step

`terrain-final/terrain-attributes-result.json` passes all capture/readback and
lifecycle checks, including worker retirement, compiler replacement, failed
compile cleanup and pack generation. Each lifecycle failure was reproduced
before its fix; intermediate rejected runs remain as `terrain-*-red` evidence.
The pack reset fixture specifically reproduced `packGeneration=false` after
compiler replacement and exception cleanup were already corrected.

Fresh review: two important findings fixed. The additional worker-context
cleanup observation is fixed too. Minor deferred: disabled fluid capture still
eagerly resolves its legacy block state; no measured CPU impact is established.
No performance improvement is claimed from these readback fixtures.

Remaining conversion work starts with per-draw terrain sidecar binding and
semantic input adapters. Entity attributes, imported shadow passes and complete
Eclipse visual/runtime acceptance also remain; keep converted workspaces marked
`runtimeReady=false`. Do not rerun the old conversion/design work from scratch.

The legacy Fullpack variant was not tested: its compile-only KoperLib 0.1.1-alpha
JARs are absent from this workspace. Base and optional reflective integration
sources were compiled and tested. No Rust sources changed in this continuation.

Final `./gradlew test build` after all production fixes: successful, 455 tests,
zero failures/errors, 13 skipped. Converter build: 130 tests, zero failures/errors,
five skipped. Rust: 17 passed, 14 explicitly ignored GPU tests. `git diff --check`
is clean. Final audit validated the four accepted GPU JSON results above and
found no running development clients. All work remains uncommitted in the
existing worktree; no integration, release export or installation was performed.

## Decisions

- Work in this existing linked worktree, as previously authorized. Do not move
  the large interrupted diff, commit, publish or replace installed game JARs.
- Local Vulkan verification uses the existing owned test game under
  `build/verification/volume-game`. It is separate from installed game instances.
- Geometry limit checks conservatively count declared built-ins, following the
  [Vulkan limits contract](https://docs.vulkan.org/spec/latest/chapters/limits.html).
  The interface maps used for stage linking still contain only user locations.
- Compilation/readback here does not establish full Eclipse world/shadow parity.
  No Android release or export is part of this continuation checkpoint.
