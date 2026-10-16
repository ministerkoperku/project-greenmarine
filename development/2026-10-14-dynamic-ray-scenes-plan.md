# Dynamic ray-scene implementation plan

1. Preserve0.7Solstice acceptance and legacy Aurora baseline evidence; reproduce
   moving-scene/edit delay with timestamps and separate CPU/GPU scopes.
2. Add deterministic section cache/planner and invalidation tests; implement
   render-thread extraction with incremental snapshot publication.
3. Add optional async FFM/native submission ownership and worker-safe scene prepare
   tokens; test concurrent flight lifetime and preserve synchronous ABI1 functions.
4. Integrate per-render-frame scanning, completion-fence polling and bounded worker
   coalescing in RayWorld; add actual MC26.3 invalidation mixins.
5. Run dynamic movement/edit/unload and old/new pack acceptance; fix review findings.
6. Repeat required base/Fullpack/Rust release/Python checks, package0.7 artifacts,
   record measured latency/remaining limits and update session handoff.

Native and scanner domains execute in parallel under dispatching-parallel-agents;
parent owns RayWorld, mixins, integration and runtime probes. Build/client changes
remain sequential. No installed jars, commits, branches or sibling modifications.

## Completion ledger, 2026-10-15

All six steps are implemented and verified. The full-scanner comparison measured
4.96–5.24-second GPU response; the final cached/async path measured 9.88–14.18 ms
and 2509 valid walking readbacks. A real post-submit failure/reset recovers.
Independent review's submission-exception lifetime gap and cache retention issue
were fixed before final acceptance. Final review found no remaining Important or
Critical issue within this scope. Vulkan water/OIT, OpenGL classic fallback and
legacy Aurora were rerun after integration; an OpenGL run contaminated by another
test workload was retained as rejected and repeated cleanly.

141 Java, 36 Python, 10 portable Rust plus two actual GPU tests and 22 FFM checks
pass, alongside base/Fullpack, clippy/fmt, release native and SPIR-V. Evidence and
artifact hashes are in `evidence/dynamic-rays/` and `evidence/solstice/`.
Finite coverage and geometry omissions remain explicit next-work limitations;
this closes the measured dynamic-update task, not full Iris parity.
