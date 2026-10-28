# Optimization estimates and Iris conversion assessment

## Scope and measurement limits

The owner's approximate observations are 150 FPS with Solstice, 230 FPS with
Eclipse and 400 FPS without Solstice while using DH. These were not a controlled
comparison of identical cameras, resolutions, scenes and warmed-up workloads.
They are useful reference points, not an attribution of time to individual passes.
The fixes in this commit have no measured fullpack FPS or 1%-low improvement yet.

At 150 FPS a frame takes 6.67 ms. Reaching 200 FPS requires 5.00 ms, a 25% reduction
in the limiting frame time; reaching 230 FPS requires 4.35 ms, a 35% reduction.
CPU submission and GPU execution overlap, so their measured times cannot simply
be added or independently translated into FPS gains.

The client Spark profile exposes CPU work and GPU waits, not per-pass GPU time.
The scanner and actor snapshot account for about 9.74% of sampled render-thread
time before the CPU changes in this commit. Even eliminating both entirely would
give only about 10.8% more FPS under a purely CPU-bound interpretation of that
profile. Remaining optimization of these methods has a smaller ceiling. See
[profile and runtime evidence](2026-10-28-zero-mip-profile-evidence.md).

## Candidates for the next optimization round

These deliberately broad ranges are engineering planning hypotheses, not benchmark
results or confidence intervals. They concern additional work beyond this commit.
They assume the affected subsystem is on the frame's critical path; a CPU-bound
scene may receive no FPS benefit from a GPU optimization, and vice versa.

| Candidate | Planning range for whole-frame FPS | Reference result from 150 FPS | Main qualification |
|---|---:|---:|---|
| Reuse unchanged actor capture and reduce scene scanning work after invalidation | 0-5% | 150-158 | Preserve moving models, biome tint and block updates; likely more relevant to allocation spikes than a large average-FPS gain |
| Reject actor clusters with world-space bounds before transforming a ray into every box | 0-8% | 150-162 | Current shader iterates up to 64 boxes; benefit depends on populated actor geometry and ray coverage |
| Adaptive GI sampling with separately validated lighting history | 0-20% | 150-180 | Upper end requires GI to dominate GPU time; disocclusion and motion need new rays; this is a quality/performance tradeoff |
| Remove avoidable full-resolution copies and combine compatible scene work | 0-5% | 150-158 | Measure bandwidth first; retain history, HDR formats and depth/material captures where required |
| Budget volumetric rays independently and skip provably inactive regions | 0-8% | 150-162 | Sun shafts already have quality scaling and night gating; additional gains are scene dependent |

Do not add these percentages. A useful investigation target is 15-35% more FPS
overall (about 173-203 from the owner's 150 reference), conditional on discovering
and removing substantial GPU cost. This is not a forecast that the current build
already achieves it. Matching 230 FPS is an ambitious follow-up goal requiring
approximately 35% less limiting frame time. Returning to 400 FPS while preserving
the current effects is not a justified promise.

First collect fullpack CPU and GPU times separately, including material capture,
native surface rays, reconstruction/history, volumetrics, bloom and final copies.
Use identical fixed poses plus a repeatable moving-camera path, warm up resources,
and record frame-time percentiles as well as average FPS. The isolated small probe
GPU timings in existing evidence must not substitute for those measurements.

High's native output reduction to one pixel per 3x3 region, early shadow exits,
tint memoization and removal of duplicate actor serialization are already in this
commit. They must not be counted again as future savings. The modpack's 1179x1179
water texture also disables the whole atlas's mipmaps; correcting that asset is a
separate owner/mod task with unmeasured performance impact.

## Iris converter: current state and remaining work

There are two distinct working foundations in development-tools:

1. `IrisImporter` preserves source packs and can emit a selectable GreenMarine
   pack for a conservative single fullscreen effect. Complex packs receive a
   report and preserved sources rather than a false successful conversion.
2. `iris/IrisTranslator` and `IrisCoverage` preprocess and translate whole program
   stages to Vulkan GLSL and compile them with Shaderc. The existing IR-1 evidence
   records 250/250 default Eclipse stages across three dimensions, including
   compute, geometry and tessellation. A fresh run against the owner's local
   `Eclipse-Shader-Unstable.zip` confirms 250/250 again: 109 vertex, 109 fragment,
   20 compute, 6 geometry and 3+3 tessellation stages. Optional mod integrations
   remain 28/50 and are reported separately. This proves source translation for
   that fixture and option set, not linked pipelines or rendered output.

The current base API already has compute passes and persistent 2D storage images
and buffers. The older conversion plan's statement that none of this exists is
outdated. Conversely, [the compute API](../docs/COMPUTE.md) still lists 3D images,
image arrays and mip chains as unsupported. Eclipse's LPV needs 3D resources;
successful compilation alone does not supply them.

Full conversion still needs a target pipeline capable of expressing Iris render
semantics: colortex formats and multiple attachments, clear/flip/mipmap rules,
pass ordering and resource barriers, custom uniforms and textures, world vertex
attributes/material identifiers, terrain/entity/water MRT rendering, shadow-map
passes, matched stage interfaces, and geometry/tessellation execution where used.
DH and other optional integrations require separate acceptance tests. Emitting
editable GreenMarine packs remains the desired result; an Iris-only runner is
not a substitute for extending the GreenMarine API.

Assessment: a useful converter for a documented subset is very feasible and already
partly implemented. Faithful automatic conversion of advanced Eclipse/Bliss packs
is technically plausible but is a substantial renderer/API project, not a small
GLSL rewrite. Treat it as multiple independently verified milestones. Compilation
coverage must stay separate from runtime coverage, visual agreement and performance.
Automatic substitution of RT for arbitrary pack shadow/GI code is a further
problem: those shaders can use shadow buffers for more than visibility, so opt-in
pack adapters are more credible than universal replacements.

Existing references:
[conversion plan](2026-10-24-iris-conversion-plan.md),
[later owner/API direction](2026-10-25-handoff-api-parity.md),
[fullscreen importer guide](../development-tools/IRIS-IMPORT.md).
The importer guide's old archive limits are stale; the implementation currently
permits 8192 files and 256 MiB. This assessment does not change importer behavior.

## Commit verification

Fresh `./gradlew test build`, `cargo test`, `cargo build --release` and all 65 Python
tests passed. Default Rust tests pass 17 with 14 GPU tests opt-in. The earlier
explicit Vulkan and runtime results and their two unrelated baseline GPU failures
are documented in the linked evidence; this fresh default run does not replace
them or count the opt-in tests as passing. A final independent integration review
found no critical or important blocker and checked changed SPIR-V validation,
packaged shader/native identity and exclusion of probe classes from the release JAR.

Fresh command logs, including the Eclipse coverage run, are under
`build/verification/commit-2026-10-28/`. No Eclipse sources or converted pack are
included in the commit. No push is requested.
