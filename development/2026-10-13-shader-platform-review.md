# Shader platform final review and rulings

One independent read-only review was performed against the complete 0.2
implementation. The reviewer read sources and supplied evidence, did not edit
files, build, launch clients or run tests. Assessment: ready with fixes; no
Critical findings, three Important findings and one Minor handoff finding.
The implementer applied one fix pass; no second review is requested.

## Important findings

1. **Retained Lua metatables escaped memory inspection.** Accepted. A bounded
   regression retained 64 KiB strings through metatable chains for 40 ticks;
   it failed before the fix. The heap walker now traverses real metatables
   with identity tracking. The chain is rejected within the memory budget,
   and a self-referencing metatable remains valid. Eight Lua tests pass.
2. **Point-light profiling continued after the F6 Off button.** Accepted.
   The live Vulkan regression failed with the exact message in
   `evidence/shader-platform/profile-red.txt`. Settings/script lifecycle now
   closes point-light profiling, clears its warmup, and guards timestamp
   sampling. F6 On -> Off -> On succeeds with the ray mode unchanged on
   Vulkan and OpenGL; the result files and settings screenshots record it.
3. **Standalone mod assets were incorrectly advertised as auto-discovered.**
   Accepted as a public-contract defect. Authoring docs now require mounted
   resource assets (e.g. a resource loader), or direct immutable `LoadedPack`
   registration without Fabric API. The example supplies its own Lua/GLSL.
   The runtime failure fixture registers a Java pack without a resource
   loader and then removes its lease, exercising this standalone alternative.

## Minor finding and extra parser correction

The 0.1.1 handoff was still marked current. The 0.2 handoff supersedes it and
keeps earlier evidence explicitly historical. No review finding is deferred.
A separate bounded parser regression reproduced coercion of schema 1.5 to 1;
version parsing now requires numeric value 1 and rejects string versions.
The full suite after fixes contains 54 passing Java tests.

## Verification scope

Runtime probes deliberately compile invalid GLSL after the visual captures.
The expected shader error disables that pack without stopping Minecraft;
a valid pack then compiles/submits after restoring settings. Those errors
in the logs are injected test evidence, not an unexplained runtime failure.
Metrics are captured before settings transitions reset their timers.

Remaining checks are hardware/platform coverage, dedicated-server startup,
device-loss recovery and broader Sodium/KFX/Fullpack coexistence. Structural
SPIR-V checks are not semantic shader validation; offline spirv-val remains
required. Trusted Java extension allocations are outside Lua's budget.
Hardware RT, FSR4, native renderer replacement and OptiFine packs are deferred
features, not implementation findings. The prototype does not claim a full
acceptance matrix on every GPU or correctness for arbitrary shader programs.
