# Dense-scene freshness and rendering cost

The owner still observes delayed lighting, a visible sudden update, and about
160 FPS with Solstice. Earlier tiny-floor acceptance does not close this report.
Priorities are fresh lighting during ordinary movement, continuous presentation,
and measured CPU/GPU savings without silently reducing quality. Vulkan remains
primary, OpenGL portable, old packs/public constructors/ABI remain compatible.

Natural-terrain baseline uses copied dev data, 32-chunk render/simulation distance,
Ultra and a fixed 96-block route. Record frame intervals, scanner/publisher age,
native and composition GPU stages, and JFR. First cold run shows 68 publications
during movement, 1.32 seconds cumulative packing and .53 seconds preparation.
JFR identifies pendingSections scans on block invalidation as a real CPU cost.
An independently warmed repeat separates server generation from mod changes.

Implement the following measured changes as one next iteration:

1. Cache pending counts and stable column membership; preserve valid extraction
   across overlapping moves. Actual edits/unloads/reset still reject stale work;
   only urgent changed geometry preempts valid unfinished coverage extraction.
   Use exact-size snapshot assembly and immutable buffer ownership to remove
   full-size growth/finalization copies. Test entry lifetime and memory contracts.
2. Fix half-resolution ray reconstruction to use the actual captured texel centre.
   Add alpha-aware first-hit sun occlusion while preserving nearest radiance rays.
   Verify actual GPU output for different source/output dimensions and cutouts.
3. Add opt-in named prior scene-pass inputs, validated against declaration order,
   with dedicated retained per-frame outputs that cannot alias ping-pong targets.
   Preserve old PackPass constructors and default bindings. Solstice evaluates
   linear scene lighting/fog once, then bloom samples that image and composition
   samples both scene and bloom. Retain threshold, quality kernel and tone mapping.
4. Investigate presentation after freshness is fixed. Blend only valid compatible
   world-space lighting updates over a short bounded interval, without delaying
   camera reprojection or hiding stale geometry for seconds. Changes at depth,
   normal, material, teleports, Off/failure and dimension changes reject history.
   Add a moving native-light readback/transition fixture before accepting this.

Alternatives: lowering quality would change the requested appearance; merely
increasing scan budgets would consume more render CPU; persistent per-section
BLAS would remove whole-scene rebuilds structurally but is a larger next step.
First remove verified waste and measure remaining build/coverage bottlenecks.
No whole-renderer/Iris superiority or universal FPS claim follows from this work.
FSR4 stays deferred. No installed jars, commits or owner clients are modified.

The owner has already authorized autonomous implementation and testing; no
additional design approval or commit step is required. Source remains in the
original workspace; build and gameplay probes run in isolated cache copies; the original 0.7
source/native JARs remain frozen references.


Temporal verification found two concrete rejection gaps before release. Lighting
history now also stores prior material and final scene depth (opt-in allocations
and copies), checks entities/materials in both frames and fetches the validated
nearest color texel. Actual GPU fixtures reproduce both rejected behaviors and
verify the fixes. The 160-byte history block retains layout and uses former
reserved W for elapsed successful-submission time; the 80-byte public RayFrame
uses former reserved option fields for scene publication age/presence. Native
TraceFrame320 options/world origins are unchanged.
