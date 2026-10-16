# Dense-scene freshness implementation plan

Goal: improve real movement freshness, remove the lighting pop and reduce
avoidable CPU/GPU work while preserving public contracts and visual quality.
Spec: `2026-10-15-performance-design.md`. Java25/MC26.3, Renderpearl and Rust FFM.

- [x] Capture cold and warmed natural 32-chunk baseline with the same probe,
  copied run data and JFR. Keep raw samples and original source hashes.
- [x] Write failing cache regressions for overlapping ticket retention, no
  camera-only preemption, actual urgent edits and bounded pending bookkeeping.
  Implement planner/scanner changes; verify full Java suite.
- [x] Test immutable exact-size assembly semantics and measure geometry-scaled
  packing allocations/time before and after; remove avoidable full buffer copies.
- [x] Write actual GPU regressions for source-texel-centred reconstruction and
  layered alpha/opaque occlusion. Implement native shader fixes; reject the
  slower first-hit candidate after measured A/B/A; check Rust,
  SPIR-V and optional ABI/legacy availability without changing frame layouts.
- [x] Write failing parser/API/render scheduling tests for named earlier outputs,
  rejected forward/alias/unknown inputs and all old constructors. Implement
  retained output bindings and Solstice cached linear scene; Vulkan/GL compile.
- [x] Reproduce visible update discontinuity with readbacks. Implement and verify
  a short compatibility-checked transition if required; retain honest readiness.
- [ ] Run warmed same-route updated comparison, full dynamic/water/legacy GPU
  acceptance, base/Fullpack/Rust release/Python checks, independent review and
  local artifacts. Record measured gains and remaining limits in handoff/docs.

Parent owns scanner, scene scheduling, presentation, integration and all runtime
sequencing. Native shader and named-pass domains may execute independently under
dispatching-parallel-agents. No concurrent builds/CPU-heavy tests during profiles;
no agents launch GPU tests or clients without the parent's explicit test window.
