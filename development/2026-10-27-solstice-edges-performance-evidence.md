# Solstice corners, torch cost, water and stars — evidence

## Owner report and final behavior

The owner supplied a crop of a bright line on an otherwise shadowed block corner.
They also reported approximately 80 FPS with a held torch versus 133–160 without,
overly transparent water, droplet-like stars, and little visible High/Ultra difference.

- Lighting taps on one plane are renormalized instead of fading to an unshadowed
  lightmap when taps on the other face are rejected. Reflection confidence remains
  separate. The actual GLSL corner fixture initially produced the bright sequence
  `[0,0,0,0,13,26,51,26,13,0,0,0]`; it now stays black.
- Local-light queries stop at the first accepted block occluder. Actor shadow
  queries stop at the first alpha-accepted face and do not calculate reflected
  normals/albedo. Candidate bounds skip lights that cannot beat the current visible
  result. Two area-light taps retain finite penumbrae and actor occlusion.
- The native shadow light becomes the moon at night, but the scene shader only
  consumes sun shafts. Java now schedules zero unused volumetric steps at night,
  while retaining moon surface shadows. Daytime budgets remain 8/12/16/24.
- High uses ceil(viewport/3), Ultra ceil(viewport/2). Ultra thus traces about 2.25
  times the native pixel count. External packs keep their existing half resolution.
- Water extinction now includes stronger wavelength-dependent absorption/haze.
  Host optical alpha is preserved for classic and OIT transparency, including
  translucent objects absent from the opaque refraction copy.
- Stars are angular point kernels with pixel-footprint filtering. Adjacent cells
  and cube faces contribute across their boundaries. A single-cell/face mutation
  fails the directional sweep with a 98/255 discontinuity; the final shader passes.

## Verification

- `./gradlew test build compileActorTestShader`: passed; 293 JUnit tests discovered,
  280 passed, 13 opt-in tests skipped, zero errors/failures.
- Python suite: 64 passed, including Vulkan/OpenGL GLSL variants, reflection/lighting
  reconstruction, shadowed corners and directional star sweeps.
- `cargo test`: 17 passed, 9 explicit GPU tests skipped by default.
- `cargo build --release`: passed.
- Seven explicit Vulkan checks passed again: cave GI/local lights/actors, sloped
  capture reconstruction, cutout sun blockers, actor/terrain closest hit, both actor
  math tests, and asynchronous generation retention. Actor math checks now also
  execute the production shadow-only path against the same alpha/distance fixtures.
- `git diff --check`: passed.

The two previously established baseline GPU failures remain as documented in
`2026-10-27-solstice-followup-evidence.md`; they are not counted as passing here.

## Runtime and costs

Logs, measurements and captures are in `build/verification/solstice-edges/`.
The final Minecraft Vulkan run (`final-ultra.log`) completed with
`sceneOnlyVerified=true`, `droppedLightVerified=true`, `lightsActive=true`. It captured
placed, held, empty-hand and dropped-torch states, then a noon pool with shallow
and deep halves (`final-ultra/water-optics.png`). The final probe client exited.
Original worlds/configuration and installed jars were not replaced.

RX 9070 XT/RADV, 1200x675 viewport, Ultra, 600x338 native target; last 256 samples:

| State | Before CPU mean | Final CPU mean | Before GPU mean | Final GPU mean |
|---|---:|---:|---:|---:|
| Held torch | 0.088843 ms | 0.064751 ms | 1.006675 ms | 0.362898 ms |
| Empty hand | 0.080681 ms | 0.064683 ms | 0.716834 ms | 0.315939 ms |
| Dropped torch | 0.084409 ms | 0.063536 ms | 0.905976 ms | 0.352882 ms |

These are CPU submission and GPU surface-stage costs, not whole-frame FPS. Runs
use the same constructed arena layout and quality/resolution, but copied-world
camera anchors advance between runs. Final scratch options disable VSync, unlike
the initial run. An independent owner game process was observed during testing;
it was left untouched. Do not infer a universal speedup percentage or guaranteed
FPS recovery in the owner's modpack from these stage samples.

One intermediate attempt failed to acquire a present image (GPU timeout in
`VulkanGpuSurface.acquireNextTexture`) and is retained as rejected in `after.log`.
The accepted reruns disabled VSync only in the scratch options and completed.
This observation does not establish the cause of that timeout.

Controlled night-sky GL fixture: 960x540, 20 timed draws, final GPU 0.1258 ms/draw,
calling-thread CPU 0.0007 ms/draw. This is shader math/appearance evidence, separate
from the successful actual Minecraft Vulkan probe. Night before/after captures
are `stars-night-before.png` and `stars-night-after.png`.

## Artifact

`build/libs/greenmarine-0.9.11-prototype.jar` (desktop Linux x86_64):

- SHA-256: `b884aeefa041ee6ad665a9465248d3900dfad3b478a896f41bac9d3d8d859d3e`
- Packaged native SHA-256: `8eba240931b242a6a20da443e34a5268480c14292b7de09b8da6a4b84d54dd6a`

Verified native bytes against the Gradle target output, checked all changed
Solstice includes against source and confirmed probe classes are excluded. The
editable Solstice shaderpack ZIP was rebuilt. This supersedes the earlier JAR hash.
Android was not built/exported; the Android linker/shared-storage destination are
still unavailable on this PC. Do not install this desktop-only native artifact as
an Android release.

The corner regression and isolated pool/night captures cover the identified
mechanisms. Final visual acceptance and whole-game FPS still require the owner's
exact scene, resolution and modpack, especially Distant Horizons and other renderers.
