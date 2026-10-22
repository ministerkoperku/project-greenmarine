# Optional native actor reflections

The existing core, native ray and surface APIs retain version 1 and their old
entrypoints. New libraries optionally export
`greenmarine_surface_rt_actors_api_version()` (version 1) and
`greenmarine_surface_rt_record_actors()`. The recording function takes all the
existing surface-record arguments, then a pointer to the actor payload and a
borrowed skin image view. `NativeRayTracer.actorsAvailable()` detects this extension;
`recordSurfaceActors(..., ByteBuffer actors, long skin)` falls back to the old
surface recording function when the extension is absent.

The payload is exactly 4624 bytes in native float order, with std430 layout:

| Field | Offset | Size |
| --- | ---: | ---: |
| Header: count, zero, zero, zero | 0 | 16 bytes |
| Up to 16 posed boxes | 16 | 16 × 288 bytes |

Each box contains a column-major inverse model matrix (64 bytes), lower and
upper bound vec4s (32 bytes), six affine U rows (96 bytes), then six affine V rows
(96 bytes). Matrices transform native scene-relative positions into raw cube
units. The face index is `axis * 2 + (positive ? 1 : 0)`; UV coordinates are
`dot(row, vec4(localHit, 1))`. The matrix must be finite, invertible and affine;
active box bounds must have positive extent. All payload floats must be finite,
count must be an integer from zero through sixteen, and header padding is zero.
An empty payload permits skin handle zero. Nonempty actors require a valid skin.

Native surface descriptor bindings 7 and 8 contain the actor storage buffer and
skin sampler. Each native submission flight owns its own device-local actor
buffer and descriptor set. `vkCmdUpdateBuffer` captures the CPU payload into the
recorded command; transfer writes become visible before compute reads. The caller
can reuse CPU payload storage after recording. The borrowed skin image view must
be sampled in GENERAL layout and remain live until the host submission fence
completes. Do not change skins between dispatches within one recording flight.
Old surface calls upload an initialized empty payload and bind the block atlas as
a valid fallback sampler, preventing stale actors after a new-style call.

The reflection ray tests both entry and exit faces, respects skin alpha, and
selects the nearest accepted box strictly before the native terrain hit. Local
ray directions remain unnormalized so distances retain world units under model
scaling. Normals use the inverse transpose, and sampled skin RGB is converted to
linear light before applying existing ambient and terrain-shadowed sunlight.
Player boxes affect reflection visibility and, since 0.9.5, cast native sunlight
shadows onto captured terrain (alpha-tested against the skin, so transparent
outer-layer texels stay open). First-person captures use the same boxes, so the
body shadow is present in every camera mode. Players do not participate in GI. Armor, capes, held items and other entities
remain outside the actor payload supplied by the local player integration.

## Optional KAvatar bodies (0.9.3)

With KAvatar 0.3.0 present, GreenMarine optionally reads its actual public
`AvatarFrames.evaluate` snapshot, shared frame serial, animation time, current
part transforms and chosen texture. It supplies its own posed vanilla model to
the evaluation; it never calls the host-mutating `forState` path. KAvatar/Kodel
classes are resolved reflectively and are not mandatory base dependencies.
Missing optional classes retain vanilla capture; an incompatible active custom
body publishes an empty snapshot with its reason instead of a stale/partial body.

The cube conversion follows Kodel's actual posed bone matrices, cube pivot and
rotation, inflate/thickness and per-face rotated/mirrored UVs. Reflection metadata
and immutable cube templates are cached; frame transforms remain current.
Real Hyper Chibi (14 cubes) and Baby Chibi (12), wide/slim, walking animations,
per-vertex renderer equivalence and placement branches are covered by fixture
tests. This verifies conversion and API resolution, not in-game appearance.

The actor ABI remains limited to 16 closed boxes and one texture. Same-texture
accessories are included only as complete parts within that budget. Unsupported
or differently textured accessories are omitted with a diagnostic reason;
oversized, open-cube or triangle-mesh custom bodies are rejected atomically.
`PlayerReflection.detail()` and surface RT status identify the actual result.
This does not add mobs, multiplayer entities, armor or general mesh tracing.

Reproduce native checks from the project root:

```sh
glslangValidator -V --target-env vulkan1.2 engine/shaders/surface_rt.comp -o engine/shaders/surface_rt.spv
spirv-val --target-env vulkan1.2 engine/shaders/surface_rt.spv
glslangValidator -V --target-env vulkan1.2 engine/shaders/surface_actors_test.comp -o engine/shaders/surface_actors_test.spv
cargo test --manifest-path engine/Cargo.toml
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json cargo test --manifest-path engine/Cargo.toml actor_compute_world -- --ignored --nocapture
cargo clippy --manifest-path engine/Cargo.toml --all-targets -- -D warnings
cargo build --release --manifest-path engine/Cargo.toml
```

The explicit compute test executes the production actor GLSL on Vulkan without
ray-query hardware. On local llvmpipe it verifies scaled world distances, parallel
misses, inside exits, alpha entry/exit, UV color, nearest actors, terrain-distance
cutoff and transformed normals. A normalization mutation failed the world-distance
assertion and the restored shader passed. The separate
`surface_gpu_actor_skin_alpha_and_nearer_block_occlusion` acceptance test requires
an actual RX 9070 XT ray-query device and remains ignored in the portable suite.
These tests do not establish Android gameplay appearance or frame rate.

`engine/tests/NativeRayTracerSmoke.java` can be compiled alongside the new bridge
against `build/classes/java/main`. Run its main class with Java 25,
`--enable-native-access=ALL-UNNAMED` and the fresh native library path. It checks
extension detection, legacy symbol fallback, invalid payload rejection and real
FFM missing-host handling without attaching to a game device.
