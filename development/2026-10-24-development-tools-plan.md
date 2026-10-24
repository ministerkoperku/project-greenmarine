# GreenMarine development work implementation plan

Goal: build a separate optional development addon with in-game shader compilation,
editable materials and diagnostics while keeping the normal renderer shared.
Architecture: independent Fabric mod under development-tools/, compile-only against
GreenMarine0.9.10. No tooling entrypoints, compiler dependencies or source changes
are injected into the ordinary GreenMarine mod. Java25/Minecraft26.3; Android
uses the host's Shaderc library. Slang is optional and requires a usable compiler.

## Branch and delivery

- [x] Isolated worktree at ../project-greenmarine-development, main-development.
- [x] Create GitHub main-development initially identical to main.
- [x] Preserve local0.9.10 material work as a separate shared-renderer baseline
  commit0efb967; original main checkout/files remain untouched.
- [x] Development README: do not merge dev commits into main. Common renderer
  commits originate on main and flow into main-development. The local0.9.10
  baseline is not yet published on main; do not claim histories are synchronized.
- [x] Separate addon jar; ordinary GreenMarine must contain no tools classes.

## Implement now: usable authoring addon

- [x] DT-01: independent Gradle project, metadata, F9 and touch launcher.
- [x] DT-02: GLSL vertex/fragment/compute compiler; bounded imports and diagnostics;
  optional Slang executable invocation without a shell; honest missing capability.
- [x] DT-03: bounded editable workspace, source editor, compile-before-save,
  resource reload/application, preserve source on compile failure.
- [x] DT-04: material canvas, palette painting, undo, editable PNG+JSON export,
  texture preview and explicit resource-pack enable/reload behavior.
- [x] DT-05: in-game pack/pass/resource diagnostics and separate CPU/GPU timings.
- [x] DT-06: full tests, Android-compatible artifact checks, native/GUI probes,
  independent review, development branch publication and verified Android export.

Each task starts with behavioral tests, fails for its missing behavior, implements
only its contract, and passes focused checks before integration. Path traversal,
symlinks, invalid numeric values, failed compilation, absent Slang, empty catalogs,
resize and screen close during background work are explicit failure cases.

- [x] DT-07: toggleable750ms auto reload with stable snapshots, declared-stage
  validation, read-only material preflight, last-good runtime retention and verbose debug.
- [x] DT-08: conservative Iris source conversion, editable projects with reports,
  preserved sources, real supported fullscreen translation and standalone CLI distribution.

## Subsequent renderer milestones (not implied by an editor button)

- GM-01: shared material/lighting contract and linear HDR across host/external
  renderer paths; moving-model visual acceptance on Android and desktop Vulkan.
- GM-02: expose the existing public graph's dependency/resource machinery to
  shaderpack manifests, including compute/storage and lifetime diagnostics.
- GM-03: shared GLSL/Slang shaderpack ABI and SPIR-V ingest for actual pack stages,
  compatible GLSL fallbacks, specialization and validated persistent cache.
- GM-04: previous transforms/bones, motion vectors, camera-cut/disocclusion masks
  and bounded temporal reconstruction for terrain plus moving mods.
- GM-05: attachment previews and detailed per-pass allocation/timing analysis.
- GM-06: independent per-effect resolution/memory budgets, sustained frame-time
  benchmarks on phones, and capability-driven profiles.

Existing graphs, material addons, SPIR-V ingest, temporal history and profiling
must be reused. These later tasks require runtime evidence; no parity/FPS claims
follow from compiling the development addon. Do not replace installed game jars.

## Implementation decisions and review

- Owner selected the separate-addon option. Root renderer implementation files
  match shared baseline0efb967 byte-for-byte. There is no development build flag
  altering the renderer; development-tools owns all tools and probe code.
- Slang is optional external compilation, not a bundled Android compiler or a
  newly implemented Slang shaderpack runtime. GLSL uses the existing host Shaderc.
- Iris import is a supported fullscreen subset plus preserved/manual projects.
  Full Iris compatibility is not claimed by compiling the source language.
- Independent review reproduced wrong-stage Save, stale clean drafts, symlink
  material exports, partial material saves and incomplete SPIR-V acceptance.
  Fixes include extension/stage policy, dirty-only drafts, bounded transactional
  material writes, and stage-aware structural checks.
- Auto reload review additionally caught deleted resource bypasses, invalid
  material layers and accidental validation of preserved Iris originals. The
  read-only ReloadValidator checks declared stages/dependencies and API1 material
  semantics without changing global catalogs.
- Parallel Gradle invocations initially raced over class outputs. Subsequent
  Gradle verification is parent-owned and sequential; final root verification
  started with clean, eliminating transient build contamination.
