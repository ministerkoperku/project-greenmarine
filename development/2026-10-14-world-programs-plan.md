# World programs implementation plan

> Execute inline with superpowers:executing-plans and test-driven-development.

Goal: correct Prismarine whiteness and deliver actual material/world shader
programs plus Seabreeze water/terrain example, continuing0.5.
Spec: development/2026-10-14-world-programs-design.md.
Constraints: MC26.3/Java25, schema1/MarineFrame288/original constructors preserved,
standalone base/optional Fullpack, Vulkan priority, GL fallback, FSR4 deferred,
no commits/installed jars/user archives/siblings.

Review focus: host state clone fidelity, current-frame camera/RT/opaque data,
classic vs OIT alpha/displacement, GPU in-flight lifetimes, status/failure isolation.

- [x] Task1: failing manifest/source/clone tests; public PackProgram and snapshots,
  clone host pipeline state, fix Prismarine example arithmetic and immutable ZIP.
- [x] Task2: world frontend source selection/bindings/caching/failure status; early
  current-frame RT, opaque color/depth capture and classic/OIT hooks; unit/runtime.
- [x] Task3: Seabreeze actual terrain/water/sky sources and ZIP, controlled visual
  fixture including move/bob/night/resize/reload and errors on Vulkan/OpenGL;
  separate CPU/GPU metrics, base/Fullpack/Rust verification, review, docs/handoff.

Ruling: existing autonomous authorization replaces routine skill design approval
and commits. Project is untracked inside dirty parent; use current workspace and
preserve all owner changes. Do not end at a plan or call this Iris parity.

Completed with final Vulkan/OIT, OpenGL/classic, GPU wave-vertex proof and legacy
Aurora regression. Review findings fixed; see evidence/world-programs/README.md.
