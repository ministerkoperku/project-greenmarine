# Native pack phase scheduling evidence

The native manifest renderer now schedules scene/compute passes at host boundaries
using `PackPass.Phase`: SETUP, BEGIN, SHADOWCOMP, PREPARE, DEFERRED, COMPOSITE, FINAL.
Existing constructors and omitted manifest fields retain COMPOSITE. Declaration
order is preserved within each phase, with producer validation against actual
execution order. Public semantics and current limits are documented in
`docs/SHADERPACK-PHASES.md`.

The existing Eclipse conversion remains incomplete. These results establish native
phase execution, resource lifetime and immutable uniform sharing, not Eclipse
world-family MRT, geometry/tessellation, shadow rendering, DH projections/depth,
source flip/mipmap semantics or complete effect preservation.

## Host boundary inspection

Inspected the actual deobfuscated Minecraft 26.3 LevelRenderer bytecode before
changing the mixins. In `lambda$addMainPass$0`, executeSolid and classic transparency
share a RenderPass. Its normal close occurs at bytecode offset 195; executeOit is
at 242 and executeOutline at 248. Therefore classic transparency is deferred using
the existing redirect, DEFERRED runs at the later executeOutline boundary after
the host closes the solid pass, and classic drawing resumes in a fresh pass with
Minecraft default uniforms and fog. OIT runs DEFERRED at the separate executeOit
boundary. There are no nested Vulkan render passes.

GameRenderer.renderLevel has one invocation of
ProjectionMatrixBuffer.getBuffer(Matrix4f). Its existing projection hook prepares
pack resources and phases after RayWorld.renderFrame and before world-program
preparation and host world drawing. COMPOSITE and FINAL execute in the existing
post-world rendering callback. BEGIN and SHADOWCOMP bracket an explicitly empty
reserved shadow boundary: no owned GreenMarine shadow renderer is implemented.

SceneTargets allocation and beginFrame are separate. Allocation initializes both
versions; subsequent frame clears run once before the first phase. Pass-name
snapshots survive phase boundaries, with setup snapshots surviving the whole
owned resource lifetime. Only a COMPOSITE/FINAL scene result is presented.

## Portable verification

`PackPhaseTest` initially observed three expected behavior failures: phase fields
were ignored, host phase ordering was absent, and a consumer could reference a
later phase. The focused phase/lifecycle suite passed after implementation.
Additional tests cover legacy constructor defaults, capability-dependent graph
changes, phase idempotence, late-boundary rejection and pack/world/viewport/reset
setup lifetime.

An intermediate global run (`/tmp/greenmarine-phases-full.log`) compiled probe
sources and ran 416 tests, with 13 skips and nine expected failures from the other
in-progress PackInputs construction stubs. Phase/lifecycle/RenderSchedule tests
passed. The root task performs the final integrated suite after those stubs and
custom-expression binding are complete; this intermediate run is not a full green
suite claim.

## Actual Vulkan readback

Both runs used the copied development world under
`build/verification/eclipse-conversion-2026-10-29/volume-game`; no installed game
JAR was replaced. GPU was the actual RX 9070 XT host device, using Minecraft's
render-thread encoder and submission lifecycle.

Commands, with the output directory replaced for the second run:

```sh
./gradlew -PruntimeProbe -PphaseProbe \
  -PprobeRunDir=build/verification/eclipse-conversion-2026-10-29/volume-game \
  -PprobeOutput=/home/koper/Projekty/greenmarine-dev/build/verification/eclipse-conversion-2026-10-29/phase-runtime \
  runClient -x downloadAssets --console=plain
# Repeat with -PoitProbe and output phase-oit-runtime.
```

The phase probe extends the actual registered MRT manifest: first raster write in
PREPARE, second in DEFERRED, third in COMPOSITE, presentation in FINAL. A small
native compute trace runs in all seven phases and checks order and the exact
custom-UBO frame index shared across phases. SETUP increments an owned storage
counter once. Float/uint targets accumulate across frames; a clearEachFrame target
must accumulate all three phase writes only within its current frame. The FINAL
shader checks the retained first-pass snapshot despite intervening target flips.

| Run | Frames | Setup executions | Trace errors | MRT/presentation mismatches |
| --- | --- | --- | --- | --- |
| Classic transparency, 960 x 540 | 4 | 1 | 0 | 0 |
| Improved/OIT transparency, 1200 x 675 | 4 | 1 | 0 | 0 |

Each run checks 64 texels of each of three attachments, four presentation samples,
actual dimensions, frame index, matrix/inverse, vec3/scalar and integer-array UBO
layout. Both report `sharedUniformSnapshot=true`. The OIT run explicitly enables
improved transparency and restores the prior setting on completion.

Evidence files are ignored local output:

- `build/verification/eclipse-conversion-2026-10-29/phase-runtime/phase-result.json`
- `build/verification/eclipse-conversion-2026-10-29/phase-runtime.log`
- `build/verification/eclipse-conversion-2026-10-29/phase-oit-runtime/phase-result.json`
- `build/verification/eclipse-conversion-2026-10-29/phase-oit-runtime.log`

Viewport/world/setup restart is covered by portable lifecycle tests in these
initial runs; these runs do not claim native resize or world-switch acceptance.
The root task will separately rerun legacy MRT and the integrated phase fixture
after custom expression source binding changes.


## Early world input initialization follow-up

A subsequent review found that early scene phases could bind the previous frame's
WorldPrograms environment/surface metadata: the old `prepare` prefix refreshed it
only after the new early pack phases. The prefix is now `beginFrameInputs`, called
before RayWorld.renderFrame and PackRenderer.beginWorldFrame. It refreshes current
camera, environment, sun and surface inputs and resets surface/material readiness
before any early GPU pass. Later world preparation consumes that initialized state
without resetting it a second time. Direct `prepare` callers still initialize when
no begin hook ran; world finish, reset and failed initialization invalidate pending
markers. Late material/opaque captures retain their existing readiness updates.

`WorldFrameInputOrderTest` inspected the actual compiled projection hook and
observed an expected failing assertion before this fix
(`/tmp/greenmarine-world-inputs-red.log`). The focused green command is:

```sh
./gradlew test \
  --tests com.koper.greenmarine.mixin.WorldFrameInputOrderTest \
  --tests com.koper.greenmarine.client.world.WorldFrameInputsTest \
  --tests com.koper.greenmarine.client.WorldUniformStartupTest --console=plain
```

The six tests cover actual compiled hook ordering, initialization before early
work, no repeated initialization during later preparation, direct-caller fallback,
actual WorldPrograms finish/reset marker invalidation, failed refresh cleanup and
startup before a pack is active. These are portable integration/lifecycle checks;
the root task separately reruns the integrated GPU phase fixture and an existing
late-world RT capture probe after the change.
