# Surface-local reflection and animated local player design

The owner says 0.9.1 still looks like an overlay near a block and wants player
reflections. They prefer the current non-RT look to RT. The exact occluding view
is awaiting clarification; do not label the original visual report reproduced.
Two verified architectural gaps can be addressed independently: opaque reflection
composition happens after entity drawing, and native ray targets omit the player.

## Surface-local composition

Replay captured terrain before the main opaque pass, after the chunk sampler and
default world state are initialized. Use a cleared private depth attachment so
capture depends on terrain visibility rather than entities already drawn into
main depth. Trace that capture before drawing opaque terrain. Compose native hit
radiance once in the terrain material shader, before entity rendering, fog and
tone conversion. Keep post-scene native irradiance correction but remove its
second reflection overlay. Water keeps its existing surface-local path. Other
packs retain their capture timing; select it through optional
`surfaceBeforeTerrain`, defaulting to true for the shipped Solstice program pair
even when reused by a renamed external pack.

Capture water with the existing water-ready surface frame without claiming the
opaque image is ready for normal world rendering. Restore that flag after replay.
Do not sample or write the active color attachment. Preserve all frame layouts.

## Local player native targets

Capture the local player's actual animated vanilla model, including skin overlay
parts, independently of whether their body appears in the primary first-person
view. Own dedicated model instances; never mutate the host's rendering model.
Extract world-to-local transforms, actual cube bounds and affine skin UV mappings.
Skip invisible/spectator players and reset on missing data. Respect slim arms,
outer layer visibility and alpha; armor, capes, held items and arbitrary entities
are explicit limits of this first extension.

Use at most 16 posed boxes and the borrowed skin texture. A fixed std430 payload
contains a vec4 header then boxes: inverse matrix (64 bytes), min/max bounds
(32), six affine U vectors (96), six affine V vectors (96): 288 bytes per box.
All coordinates are native scene relative. Ray/box parameter distances remain
world distances by not normalizing the transformed direction. Reject boxes past
the first native block hit, giving real block occlusion; alpha-test the skin.

Append an optional native entrypoint taking this payload and skin view; keep ABI1
and the old recording function intact. Legacy calls bind initialized empty actor
data and a valid fallback sampler. Snapshot actor buffers per in-flight native
submission; never mutate descriptor resources still referenced by the GPU.
Borrowed skin views remain referenced through submission completion.

## Verification

Regression tests must exercise early capture ordering, single reflection
composition, model transforms/UVs, bounded/finite payload, ray-box parallel and
inside cases, alpha/nearest hit and block occlusion. Compile both shader backends,
run Java/Python/Rust checks, rebuild dual ARM64 native libraries, execute actual
Bionic smoke and guarded export. Controlled renderer tests do not establish
Android gameplay appearance or device frame rates.
