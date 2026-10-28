# Fullpack Spark profile, projected GI textures and CPU work

## Supplied profiles and their limits

The owner provided [bTQOWFiNNH](https://spark.lucko.me/bTQOWFiNNH), followed by
[aGqLHVI5GQ](https://spark.lucko.me/aGqLHVI5GQ). Raw sampler data was downloaded
and decoded using Spark's published protobuf schema. Raw bytes, schema and decoded
JSON are retained under `build/verification/spark-{id}/`.

The first profile is SERVER, covering only Server thread for 224.946 seconds. It
contains no GreenMarine renderer stacks and cannot diagnose its frame cost. The
second is CLIENT, Render thread only, spanning 60.387 seconds with 59,064 ms of
sampled time. Relevant inclusive nodes in that client profile are:

| Node | Share of sampled render-thread time |
|---|---:|
| WorldSceneScanner.step | 6.007% |
| PlayerReflection.snapshot | 3.732% |
| WorldPrograms.captureMaterials | 6.826% |
| RayWorld.renderSurface | 4.984% |
| VulkanCommandEncoder.awaitSubmitCompletion | 12.949% |

These nodes nest: renderSurface and actor capture are within captureMaterials.
They must not be summed as independent total renderer overhead. GPU completion
waiting is not a per-shader GPU duration. This profile also exposes global GC and
allocation metrics, but does not assign whole-process allocations to individual
render methods or directly measure frame-time 1% lows.

Scanner descendants spend repeated time in grass/foliage biome tint resolution;
nearby actor append validates and serializes the same cubes twice through a trial
builder. These justify the two bounded CPU changes below. No claim that all FPS
loss or pauses are fixed follows from this profile.

## Confirmed zero-mip root cause

The owner's fullpack log records:

`Texture koper_mod_fabric:block/water_flow with size 1179x1179 limits mip level from 4 to 0`

The following atlas creation is `4096x4096x0`. The owner's options request mip4;
the asset constraint removes mipmaps for the entire atlas. This is distinct from
the earlier sampler max-LOD bug. Even after that sampler fix, LOD4 clamps to the
available base level and samples detailed texture patterns into diffuse GI.

When mip4 exists, the existing native bounce lookup remains unchanged. With fewer
mips, diffuse bounces now use four fixed UV samples over the triangle's sprite
bounds at the coarsest available mip, independent of the ray-hit UV. This is a
bounded colour estimate, not an exact average of every texel. It preserves tint,
emission and non-atlas external colours without changing ray counts, reflections,
alpha tests, native layouts or owner settings.

The actual Vulkan regression uses a zero-mip red/green source and camera offsets
on its two colour regions. Before the fix, GI switched to source detail (first
phase `[0.649902,0.199951,0.199951,0]`). Afterwards both phases produce
`[0.297852,0.297852,0.199951,0]`, while the reflection correctly switches between
detailed red and green. The test requires retained bounce energy, not disabled GI.

## Separate registered-emitter silhouette defect

Texture averaging alone does not prevent the four fixed GI directions from
projecting an emitter's geometric silhouette. Registered emitting blocks already
supply direct light through SurfaceLights, so their emissive term was counted a
second time by GI. The earlier 2-to-6-block fade did not suppress distant emitters.

The bounce now identifies an emitting world hit's block centre using a small
incoming-direction bias. If a positive-level source at that centre exists in the
32-entry local-light table, it omits only that duplicate emission term. Direct
lighting, sun bounce, AO and reflections stay active. Unknown or external emitters
keep the previous fallback; geometry extending outside its owning block can evade
matching. Zero-radiance bounces also skip unnecessary albedo texture samples.

A new actual GPU regression fails with direct RGB `[0.301514,0.301514,0.301514]`
but GI RGB `[0.644043,1.875977,0.376221]` on a six-block emitter silhouette. After
the fix, registered-emitter GI exactly matches the direct-light result. The test
also retains energy without a matching table entry and with an unrelated light;
it therefore rejects globally disabling emissive GI. Independent review found no
important issue in centre matching or the bounded lookup.

Separate High, zero-mip TorchShadowProbe runs cover placed, held and dropped torches,
empty hand and occlusion. The completed run reports sceneOnlyVerified,
droppedLightVerified and lightsActive, with normal exit. Captures and per-state
CPU/GPU timings are under `build/verification/emitter-gi/runtime{,-final}/`.
These isolate the owner's follow-up observation from the glowstone texture case.
The final-bytecode run used High, 960x540 capture, 320x180 native output and an
actual zero-mip atlas. Held-torch surface-stage mean was 0.06703 ms CPU submission
and 0.13857 ms GPU (last 256 samples). Placed/held/dropped captures retain light
and occlusion without the duplicate torch image on the stone wall. These are stage
measurements at the fixture resolution, not fullpack frame-rate claims.

## Runtime visual verification and GPU cost

`CaveProbe` now offers a wood fixture: an enclosed oak pillar lit by a textured
glowstone panel behind the camera. At actual mip0 the old bounce shader projects
glowstone's pattern onto both the oak and the stone wall. The corrected shader
retains indirect light without that pattern. Before/after captures use the same
camera/arena, High, Vulkan and 1200x675 viewport with a 400x225 native target. Both
logs confirm a `1024x512x0` atlas. Evidence:
`build/verification/zero-mip-gi/{before,after}/` and matching log files.

Initial attempts in `wrong-mip-{before,after}` were rejected: the host Fancy preset
restored mip4 despite the edited options. The scratch preset was then set to Custom
with mip0; the probe now refuses a wood fixture unless the actual atlas has one
level. An `after-large-viewport` run is also retained but excluded from matching
viewport comparisons. Only scratch options/worlds were modified.

A final matched-resolution `timed-after` run records CPU/GPU samples per captured
variant before changing parameters. Its full-GI surface-stage window (256 samples):

- CPU submission mean 0.07408 ms, p95 0.07391 ms.
- GPU surface pass mean 0.34313 ms, p95 0.35576 ms.

Those are absolute stage costs in this constructed emissive arena, not whole-frame
FPS or a GPU speedup claim. Earlier end-of-probe snapshots represent the final
GI-disabled variant and are not used to benchmark the new fallback. Other owner
processes were left untouched. The exact owner's forest scene, full modpack load
and 1% lows still need owner runtime acceptance after restart.

## CPU changes and atomicity

`BlockTintMemo` resolves each tint index once for a scanned block. It resets before
every MODEL block and uses that block's current state and position; it does not
cache biome colours across locations or bypass host world queries. The callback
is retained once, avoiding per-quad closures. Tests cover multiple tint indices,
reset to a new position, zero colours, growth and failed lookups.

`appendWhole` now serializes actor cubes once. A `finally` rollback restores the
count/header and clears the unused tail on rejection or any exception. This
retains complete-model publication and prevents orphan boxes without texture spans.
Tests cover capacity rejection, an invalid later polygon, an exception after a
valid cube, and successful append preserving prior model bytes.

The same manual six-model/six-cube CPU fixture measured:

| Per synthetic frame | Before | After |
|---|---:|---:|
| CPU time | 135,418 ns | 94,883 ns |
| Java heap allocation | 69,885 bytes | 53,857 bytes |

The trial builder's repeated direct-buffer allocation is also removed. This small
benchmark is separate from GPU cost and is not an owner FPS/1%-low measurement.
Fixture, init script and logs remain in `build/verification/profile-cpu/`.
Independent review found no critical or important issue in either CPU change or
the zero-mip fallback.

## Validation and delivery

- Java: 301 discovered, 288 passed, 13 opt-in skips, zero failures/errors.
- Twelve relevant explicit Vulkan regressions passed, including registered-emitter
  duplication, zero-mip GI,
  coarse-mip GI, grass cutout coverage, workgroup AO continuity and lifetime cases.
- Rust: 17 portable tests passed, 14 GPU tests opt-in; release build successful.
- Python: 65 tests passed; native surface SPIR-V and diff whitespace checks passed.
- The two previously documented unrelated baseline GPU failures remain outside
  this accepted set and are not counted as passing here.

The desktop Prism JAR is replaced under the owner's prior explicit authorization,
with byte/native verification and backup. Receipt:
`build/verification/zero-mip-gi/install.json`. No owner process, asset, setting or
world is changed. A running game retains its loaded version until restarted.
