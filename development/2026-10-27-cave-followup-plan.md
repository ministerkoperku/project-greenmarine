# Cave transport and intermittent frame-cost follow-up

Owner reports after the daylight fix: light emerging from a dark cave, persistent
bright seams, a glow-lichen-like pattern projected onto other cave blocks, and
short 100-to-40 FPS dips. These are current reports; earlier probes did not accept
this exact cave scene. Continue without claiming every rendering issue is fixed.

## Investigation and implementation

1. Confirm actual Vulkan GI sampling: `linearBounceSurface` requests atlas LOD4,
   but `SurfaceState` creates its sampler with max_lod=0. Reproduce with distinct
   base/coarse mip colours before changing the sampler. Keep reflection/alpha LOD0.
2. Exercise an enclosed, emissive/cutout cave fixture. Inspect full-resolution
   material capture at corners, GI on/off, shafts on/off and actual light leakage.
3. Measure the static-light rescan. Current render-thread collection rescans all
   nearby emitting sections in one call every 20 ticks, potentially hundreds of
   thousands of block states. If measured cost explains the stalls, bound the work
   and publish complete snapshots while retaining previous lights; preserve immediate
   dynamic sources and correct readiness. Do not restructure this path based on its
   worst-case loop count alone.
4. Run Java/Python/Rust checks and relevant explicit Vulkan tests. Keep compilation
   separate from visual evidence and frame-cost measurements. Preserve source/world
   ownership and the owner's settings. The earlier explicit Prism-update authorization
   persists, but only replace the installed JAR after the new build is verified.

## Current evidence

- Latest owner gameplay is in `run/logs/latest.log` (22:45–22:50); Prism log is older.
- Observed RT scene catch-up intervals 92 ms and 769 ms are not proof of corresponding
  frame stalls; publication is asynchronous and requires separate CPU/GPU measurements.
- `max_lod(0.)` is confirmed in the actual native surface sampler. The GI request for
  `textureLod(...,4.0)` therefore cannot select an averaged atlas mip.
- User clarification pending: exact glow-lichen interpretation and current run target.

## October 28 continuation

- GI mip regression confirmed red/green on the actual Vulkan GPU; surface sampler
  fixed without changing reflection/detail lookups or the native ABI.
- DH startup crash was separately confirmed, fixed with two lazy-initialization
  gates, reviewed, tested against actual DH LOD rendering, and delivered to Prism.
- Enclosed cave and close held-torch views completed. The enclosed sun-visibility
  image is black and disabling shafts does not reveal a major lighting difference.
  The reported bright block-edge lines have not reproduced in these views.
- Thirty full-scan samples in each emitting-section fixture: median 1.184/1.471 ms,
  maximum 1.840/1.940 ms. This does not account for the reported 25 ms frames, so the
  incremental scanner redesign is deferred pending evidence from the affected scene.
- The separate whole-frame route/profile completed: no frame exceeded 25 ms in
  this short standalone-mod run. Exact world/dimension/coordinates and current run
  target have been requested asynchronously; the remaining owner reports have not
  reproduced. Detailed measurements and acceptance limits are in
  `2026-10-28-dh-cave-evidence.md`.
