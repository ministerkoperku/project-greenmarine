# Iris frame inputs

`IrisFrameInputs` is the client adapter for genuine Minecraft frame data used by
native converted packs. It does not change the 288-byte `MarineFrame` block.
Its presence does not establish that a converted pack can run: world attributes,
shadow rendering, resource bindings and pass execution require separate evidence.

Create one provider per active renderer/pack lifetime. Call
`capture(renderer, hostProjection, packIdentity, frameStartNanos, sunPathDegrees)`
once after Minecraft extracts its camera state, then reuse the returned snapshot
for all passes in that frame. `hostProjection` must be the actual matrix passed
to Minecraft's `ProjectionMatrixBuffer`; the camera's base projection omits
camera effects. `sunPathDegrees` comes from the pack's `sunPathRotation`
directive. Use a stable pack instance as `packIdentity`, not a freshly allocated
token on every call. A provider resets when world or pack identity changes;
call `reset()` explicitly during renderer teardown/reload.

`snapshot.resolve(references)` returns only the requested **root** values for
`UniformExpressions`. For example, requesting `eyeBrightness.y` returns the
`eyeBrightness` integer vector. `resolveValues(references)` instead retains
each exact requested source key and resolves dotted components, for uniform
block uploads. Matrix references use `column.row`; a matrix is a column-major
`float[16]`. Integer vector components remain `Integer`, floating components
remain `Float`, and booleans remain `Boolean`. Both methods reject every missing
reference before returning values. `unsupported(references)` reports that set
without throwing. Returned arrays are copies; changing them cannot change the
snapshot or another pass's data.

## Values and timing

The adapter supplies viewport dimensions/aspect ratio; current, inverse and
previous gbuffer matrices; camera position/history, integer/fractional position
and eye altitude; frame counter/timing; world time/day/moon phase; sun/moon/up
vectors; precipitation and thunder; player health/air; camera submersion and
light levels; player state, vehicle state and look/relative position vectors;
lightning position; biome, category, precipitation, temperature and expression
constants; render distance, gamma, HUD visibility and first-person state.

Important source semantics:

- `currentPlayerHealth` is health divided by maximum health;
  `currentPlayerAir` is air divided by maximum air. `maxPlayerHealth` is the raw
  maximum. These three values are `-1` outside survival mode, matching Iris.
- `eyeBrightness` reads block and sky light at the camera entity's eye block,
  multiplies both by 16, and returns an integer vector. Biome data instead uses
  the player's block position. `feetInWater` and `vehicleInWater` use Minecraft's
  shallow-water test. Camera submersion uses the camera fluid type, including
  Iris's spectator/lava distinction.
- `lightningBoltPosition` is relative to the unshifted camera. Its `w` is one
  when a lightning entity exists and zero otherwise. The absent-lightning zero
  vector is the actual Iris contract, not an integration fallback.
- Camera X/Z history shifts in multiples of 30000 to preserve float precision.
  The same shift applies to the previous camera, preserving frame motion.
  Integer/fractional camera coordinates use the original unshifted position,
  with floor semantics for negative coordinates.
- The first previous matrix is identity and the initial previous camera is
  zero, as in Iris. Each `sample` advances once; resolving a snapshot never
  advances history. Invalid matrices fail without advancing an existing lifetime.
- `frameCounter` increments at frame start and wraps at 720720. Wall-clock
  `frameTime` uses millisecond resolution and continues while paused;
  `frameTimeCounter` resets to zero at 3600 seconds. The separate
  `expressionDeltaSeconds()` is zero while paused so expression smoothing
  freezes; resuming uses only the elapsed current frame, with no paused catch-up.
- `worldTime` uses Java remainder and `worldDay` uses truncating division.
  Fixed-time dimensions return zero world time except the Nether and End,
  matching Iris's compatibility exception.

Capture reads the current extracted frame's partial tick. The adapter requires
a loaded world; player/camera-dependent roots with no applicable data are absent
unless Iris defines an explicit absence value (such as survival vitals or a
missing vehicle). A consumer must handle missing roots as unsupported inputs.

## Projection boundary

Iris shaders expect conventional OpenGL clip depth: near is -1 and far is +1.
Minecraft 26.3 builds its actual host projection using swapped near/far arguments
to JOML, producing reversed depth on both OpenGL and Vulkan. The pure `Input`
record explicitly carries both depth-range and reversal flags. Capture reads
the device's zero-to-one flag and uses Minecraft 26.3's reversed direction.

The adapter converts clip row Z as follows, preserving every other matrix row:

| Host projection | Iris row Z |
| --- | --- |
| Conventional [-1,1] | Z |
| Reversed [-1,1] | -Z |
| Conventional [0,1] | 2Z-W |
| Reversed [0,1] | W-2Z |

The inverse and previous projection use the same Iris convention.
`rawHostProjection()` retains the original host projection, independently of
these uniform values. A translated vertex pipeline must restore host clip depth
when writing positions; depth textures and reconstruction must use a consistent
conversion too. Supplying these uniforms alone does not implement that boundary.
`MarineFrame` keeps its existing host-matrix convention.

## Biome identifiers

Iris assigns `BIOME_*` numbers from `Biomes.register` declaration order, not
the level registry's raw IDs. The adapter explicitly follows the **67-entry
Minecraft 26.3 order**, verified against the local Minecraft class bytecode.
This includes `dappled_forest` before `dark_forest` and `sulfur_caves` before
the Nether biomes; `BIOME_DARK_FOREST` is 12 and `BIOME_PALE_GARDEN` is 13.
Constants and current-biome lookup use one table. Unknown mod biome keys yield
zero, matching Iris's unpopulated integer-map entry. A Minecraft version change
must revalidate this table.

Categories follow Iris's ordered tag tests and category enum, rather than a
registry ID or a biome-name guess. The first matching category wins:

| Tag | Category ID |
| --- | --- |
| WITHOUT_WANDERING_TRADER_SPAWNS | NONE = 0 |
| HAS_VILLAGE_SNOWY | ICY = 7 |
| IS_HILL | EXTREME_HILLS = 2 |
| IS_TAIGA | TAIGA = 1 |
| IS_OCEAN | OCEAN = 11 |
| IS_JUNGLE | JUNGLE = 3 |
| IS_FOREST | FOREST = 10 |
| IS_BADLANDS | MESA = 4 |
| IS_NETHER | NETHER = 16 |
| IS_END | THE_END = 8 |
| IS_BEACH | BEACH = 9 |
| HAS_DESERT_PYRAMID | DESERT = 12 |
| IS_RIVER | RIVER = 13 |
| ALLOWS_SURFACE_SLIME_SPAWNS | SWAMP = 14 |
| WITHOUT_ZOMBIE_SIEGES | MUSHROOM = 15 |
| IS_MOUNTAIN | MOUNTAIN = 17 |
| No matching tag | PLAINS = 5 |

Precipitation values are NONE=0, RAIN=1 and SNOW=2, sampled from the biome at
the player's height and the level's sea level. Temperature is the biome's base
temperature. These definitions also supply `CAT_*` and `PPT_*` constants.

## Unsupported inputs and Eclipse coverage

The 2026-10-29 Eclipse expression inventory has 51 remaining host references.
With a loaded world/player/camera, capture covers 48; `dhNearPlane`,
`dhFarPlane` and `dhRenderDistance` remain unsupported. The adapter does not
invent values for absent Distant Horizons, Voxy or Physics Mod integrations.

The complete converted stage-uniform union also contains custom expression
outputs, shadow matrices, per-draw matrices/IDs, item mappings, depth readback,
smoothing and other integration values. This frame adapter does not replace
those owners. In particular, it supplies no placeholder shadow matrices,
`centerDepthSmooth`, `eyeBrightnessSmooth`, `wetness`, block/entity/item IDs,
`renderStage`, `chunkOffset`, Physics Mod uniforms or distant-renderer matrices.
Custom expression outputs must be evaluated separately with the pack's options
and this snapshot as inputs. Every requested unresolved root remains an error.

### Distant Horizons follow-up

The installed DH 3.3.4 API was inspected together with Iris's
[DHCompatInternal](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/compat/dh/DHCompatInternal.java).
In Iris's enabled DH path, `dhRenderDistance` is the configured chunk radius
multiplied by **16**, in blocks. `dhFarPlane` is
`(blockRadius + 512) * sqrt(2)`. `dhNearPlane` invokes
`IDhApiRenderProxy.getNearClipPlaneDistanceInBlocks(realPartialTick)`.
The pre-initialization/fallback values in Iris are compatibility behavior;
GreenMarine deliberately leaves these inputs unsupported instead of inventing
a distant renderer that is not ready.

Two details prevent treating this as a direct scalar-only integration:

1. DH 3.3.4's public near-plane method immediately invokes its internal
   `RenderUtil`. This can initialize the utility too early and freeze a null
   renderer definition. Metadata discovery must use initialization disabled;
   near-plane invocation must wait for actual DH rendering readiness. The
   existing `DistantTerrain` bridge waits for a non-null DH depth texture of the
   correct viewport size before reading clip utilities. Its startup regressions
   and actual DH/Vulkan evidence are recorded in
   `development/2026-10-28-dh-cave-evidence.md`.
2. Actual DH 3.3.4 without Iris uses a different far plane:
   `(blockRadius + 512) * 2`. Its projection also caps the near plane at 7.5
   when there is no height-based override; the raw public near-plane method
   does not perform that cap. Feeding Iris's scalar far/near values into
   reconstruction of native DH depth would therefore be inconsistent. A native
   converted DH pass must bind the actual DH projection/depth and handle this
   convention boundary explicitly.

The public API exposes delayed `configs.graphics().chunkRenderDistance()` and
`renderProxy.getDhDepthTextureBlazeWrapper()` without calling `RenderUtil`.
They are the safe discovery/readiness path for a later optional adapter.
`DhApiRenderParam` also carries actual near/far and DH projection matrices in
render events. Neither discovery nor a non-null configuration proves that a
current usable depth image or DH rendering pass exists.

## Sources and verification

Semantics were checked against Iris revision
`bff1e69cb6c5519d8745784aa9c8b92984de67e7` on branch 26.1:
[common/player and light uniforms](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/CommonUniforms.java),
[exclusive/player vitals](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/IrisExclusiveUniforms.java),
[camera history](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/CameraUniforms.java),
[matrices](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/MatrixUniforms.java),
[celestial vectors](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/CelestialUniforms.java),
[frame timing](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/SystemTimeUniforms.java),
[world time](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/WorldTimeUniforms.java),
[biome category/precipitation](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/uniforms/BiomeUniforms.java),
[category numbering](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/parsing/BiomeCategories.java)
and [biome ID assignment](https://github.com/IrisShaders/Iris/blob/bff1e69cb6c5519d8745784aa9c8b92984de67e7/common/src/main/java/net/irisshaders/iris/mixin/MixinBiomes.java).

`IrisFrameInputsTest` exercises deterministic CPU inputs and actual JOML math,
including both reversed host depth ranges, inverse/previous matrices, exact
expression component access, pause/resume timing, camera shifts, immutable
snapshots, vital ratios and category precedence. These tests establish CPU
behavior and adapter compilation. They do not establish runtime rendering or
complete Eclipse feature preservation.

The 2026-10-30 Vulkan manifest-uniform probe also exercised actual capture and
upload through a three-attachment raster/compute chain. Its four rendered frames
reported viewport values 1200×675, frame counter 4, valid host uniforms, and zero
mismatches across 64 pixels per attachment. Evidence:
`build/verification/eclipse-conversion-2026-10-29/uniform-mrt-runtime-stage-fixed/mrt-result.json`.
This establishes that the tested host values reached the GPU. It does not verify
every supported root or render the complete converted Eclipse pack.
