# The player in reflections and shadows

With hardware surface RT active, the local player's posed body is part of the
traced scene: it appears in reflections (water, metals, glass, puddles) and casts
a sun shadow onto terrain, in first and third person. The body is rebuilt every
frame from the real animation state, with the actual skin, slim or wide arms and
enabled outer layers; skin alpha is respected, so transparent texels neither
reflect nor shadow. Players do not take part in diffuse GI. Armor, capes, held
items, other players and mobs are not included.

`PlayerReflection.detail()` and the surface RT status line report what was
captured (box count, KAvatar parts, omissions and why).

## KAvatar bodies

With KAvatar 0.3.x installed, GreenMarine reads its public frame snapshot
(`AvatarFrames.evaluate`, shared frame serial, animation time, part transforms and
textures) through reflection; KAvatar is never a required dependency and GreenMarine
never calls its host-mutating paths. Cubes follow Kodel's posed bone matrices,
pivots, rotations, inflate and per-face rotated/mirrored UVs.

- Up to **64 boxes** per player with the extended native module (16 with older
  natives).
- Parts with **different textures** (accessories) are combined into an owned
  RGBA8 atlas, up to 2048x2048 with 2-pixel gutters; their UVs are remapped
  affinely. Older natives keep only parts sharing the body texture.
- Parts that do not fit, open cubes and triangle-mesh bodies are omitted as whole
  parts with a reason; an unsupported body publishes nothing rather than a
  partial or stale body.

## Native ABI

Two optional extensions, both version 1, sit beside the unchanged surface ABI:

| Symbol | Boxes | Payload bytes |
|---|---:|---:|
| `greenmarine_surface_rt_record_actors` | 16 | 4624 |
| `greenmarine_surface_rt_record_actors_extended` | 64 | 18448 |

Each takes the usual surface-record arguments plus a payload pointer and a skin
`VkImageView` (sampled, GENERAL layout, alive until the submission's fence
completes; zero allowed when the payload is empty). The payload is native-order
std430: a 16-byte header (count, then three zeros) followed by fixed-size boxes of
288 bytes each:

| Field | Bytes |
|---|---:|
| Column-major inverse model matrix (scene-relative position → cube units) | 64 |
| Lower and upper bounds (vec4 each) | 32 |
| Six affine U rows, one per face | 96 |
| Six affine V rows, one per face | 96 |

Face index is `axis * 2 + (positive ? 1 : 0)`; a face UV is
`dot(row, vec4(localHit, 1))`. Matrices must be finite, affine and invertible and
bounds must have positive extent; invalid payloads are rejected. Legacy payloads
are zero-padded to the extended size. `NativeRayTracer.actorsAvailable()` and
`extendedActorsAvailable()` detect the extensions.

Descriptor bindings 7 and 8 hold the actor buffer and skin sampler. Every
submission flight owns its own actor buffer, written with `vkCmdUpdateBuffer`, so
the caller may reuse its CPU payload immediately after recording. Rays test entry
and exit faces, keep world-unit distances under scaling, and accept the nearest
box strictly before the terrain hit.

## Verification

```sh
cd engine
cargo test
# Actor math on any Vulkan compute device (lavapipe works):
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json \
GREENMARINE_GPU_TEST_DEVICE=llvmpipe cargo test actor_compute -- --ignored --nocapture
# Ray-query acceptance on real hardware:
GREENMARINE_GPU_TEST_DEVICE="RX 9070 XT" cargo test surface_gpu_actor -- --ignored --nocapture
```

The compute tests run the production actor GLSL and check scaled distances,
misses, inside exits, alpha, UV colour, nearest-box selection, the terrain cutoff,
transformed normals and box index 63. Java tests cover KAvatar conversion with the
real Hyper Chibi and Baby Chibi models, atlas packing and UV remapping.
