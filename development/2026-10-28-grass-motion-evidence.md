# Grass shadow stability and AI notice

## Owner report and reproduced mechanisms

The owner reports occasional square shadow fragments and apparent lateral movement
while walking on High; stationary rendering is acceptable. The current Prism log
confirms High at 2560x1370, with an 854x457 native surface target. Ultra was not
accepted by the owner. No owner game process or world was modified for diagnosis.

Two mechanisms were verified independently:

1. The native 5x5 GI/AO filter shifted its window to remain within each 8x8
   workgroup. An affine field therefore produced discontinuities at tile edges
   (about 24x24 viewport pixels on High). The actual Vulkan tilted-plane regression
   failed before the fix: AO at native pixels 7/8 jumped 0.378174 to 0.400879. After
   removing the group-local filter it progresses 0.387207 to 0.391846, matching the
   surrounding slope. GI/AO now retain each producer's position and use the existing
   geometry-aware whole-image reconstruction and validated temporal history.
2. Removing that filter alone barely changed the constructed grass-motion metric.
   Raw sun visibility still switched reduced pixels completely on/off on fine alpha
   cutouts. Filtering the cutout coverage over the receiver footprint reduces this
   independent source of crawling. It does not supersample geometric silhouettes.

## Primary sun cutout coverage

`surface_sun.glsl` projects a reduced receiver pixel onto a hit triangle along the
sun ray, converts world deltas into UV gradients (including external transforms),
and samples sprite-bounded bilinear/trilinear alpha mips. It retains one primary
shadow query per receiver. Opaque casters retain their hard visibility; secondary
GI/volumetric queries, reflections, and their detailed alpha tests are unchanged.

The scanner opts in only CUTOUT layers whose sprite has no translucent base texels.
The internal metadata value 1.25 remains within the existing alpha-tested range;
legacy readers interpret it as a normal cutout. Legacy value 1 retains its point
threshold, preserving optical texture alpha and material transmission for glass.
Existing public builder overloads still write value 1; only the new package-private
scanner overload opts in. Triangle size, C ABI and frame blocks are unchanged.
Gradle tracks the new shader include as an input.

The explicit GPU checker regression failed before filtering (visibility 0 instead
of the averaged 0.5). After filtering, all three camera offsets produce 0.5. It
also checks tinted transmission (0.75 for half coverage), legacy glass (texture
alpha 0.2 with material transmission 0.5 stays 0.5), and opaque blocking (0).
The Java material test separately verifies values 1, 1.25 and 0 with unchanged
96-byte layout. Independent review found no critical or important issue in either
fix. Adjacent packed sprites and transformed tagged geometry are not independently
accepted by dedicated runtime fixtures.

## Actual Vulkan motion captures and cost

`GrassMotionProbe` constructs static short-grass casters and a stone pillar on a
flat arena, then captures the same eight camera translations and host bob phases
in six modes: High, Ultra, High without GI/shafts, coverage, raw sun visibility,
and High without history. Each run completes 48 captures. The run folders under
`build/verification/grass-motion/` are `before`, `after` (group-filter removal only),
`filtered` and `final` (tagged cutouts). Each includes camera/projection metadata.

`analyze-motion.py` reprojects the floor into world coordinates and excludes view
rays intersecting conservative plant bounds. In the same 28,990-pixel floor mask,
the raw sun diagnostic's temporal RGB-mean standard deviation changes:

| Statistic, 8-bit RGB-mean units | Before | Final |
|---|---:|---:|
| Mean | 1.0397 | 0.4827 |
| p95 | 5.3848 | 2.0308 |

This measures temporal variation over eight deterministic settled poses, not a
universal image-quality metric or a continuous live-walking latency test. High and
Ultra normal captures retain visible grass shadows without the earlier coarse
alpha switching. Remaining geometric-edge aliasing is expected from point rays.
History-disabled and normal settled poses are nearly identical, so no bobbing or
history-matrix behavior was changed. Original matrices remain paired with capture.

RX 9070 XT/RADV, 1200x675 viewport, High, 400x225 native target. Mean of the eight
captured surface-stage sample windows:

| Cost | Before | Final |
|---|---:|---:|
| CPU submission | 0.05275 ms | 0.04976 ms |
| GPU surface pass | 0.26831 ms | 0.27390 ms |

The approximately 0.0056 ms GPU difference is specific to this small arena. It is
not a whole-frame FPS promise for the owner's resolution or modpack. Each probe
saved and exited normally. The later sprite-transparency guard excludes additional
nonbinary custom textures without changing the binary grass used by this fixture.

## Validation and requested documentation

- Java `test build`: 296 discovered, 283 passed, 13 opt-in skips, zero failures/errors.
- Ten relevant explicit Vulkan regressions passed, including the two new cases,
  coarse GI mip selection, cave/local-light occlusion, cutout layering, actor math,
  capture texel mapping and asynchronous lifetime checks.
- Rust: 17 portable tests passed, 12 opt-in GPU tests; release build successful.
- Python suite: 65 passed. Native surface SPIR-V validation and diff whitespace check passed.
- Two previously documented unrelated baseline GPU failures remain outside the
  accepted set; this evidence does not count them as passes.
- The root README now includes the owner-requested English AI notice.

The owner-authorized Prism replacement uses the final desktop build, with byte
identity/native-content verification and a backup receipt at
`build/verification/grass-motion/install.json`. This does not resolve every earlier
cave/FPS report or certify the entire owner's modpack. A running owner client keeps
its loaded version until restarted.
