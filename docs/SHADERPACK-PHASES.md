# Shaderpack execution phases

Scene and compute manifest passes can select `phase`. The default is `composite`,
including all existing Java `PackPass` constructors. Lightmaps keep their separate
update path and cannot select another phase. The public `PackPass.Phase` enum uses
the corresponding uppercase names.

| Manifest phase | Host boundary | Frequency |
| --- | --- | --- |
| `setup` | After owned resource initialization, before `begin` | Once per pack/world/viewport lifetime |
| `begin` | Before the reserved shadow boundary | Each rendered world frame |
| `shadowcomp` | After the reserved shadow boundary | Each rendered world frame |
| `prepare` | Before host world programs and world drawing | Each rendered world frame |
| `deferred` | After opaque drawing, before classic or OIT transparency | Each rendered world frame |
| `composite` | After the world, before hand and HUD composition | Each rendered world frame |
| `final` | After composite, before presentation | Each rendered world frame |

The phase names map directly to Iris program families. GreenMarine currently has
no owned shadow renderer: `begin` and `shadowcomp` execute on opposite sides of an
explicit empty shadow boundary before `prepare`. Selecting these phases does not
supply shadow maps, shadow matrices, shadow geometry or hardware ray tracing.

```json
"passes": [
  {"name":"initialize", "stage":"compute", "compute":"demo:initialize",
   "groups":[1,1,1], "phase":"setup"},
  {"name":"prepare_lighting", "fragment":"demo:prepare", "phase":"prepare"},
  {"name":"deferred_lighting", "fragment":"demo:deferred", "phase":"deferred",
   "inputs":{"PreparedLighting":"prepare_lighting"}},
  {"name":"compose", "fragment":"demo:compose", "phase":"composite",
   "inputs":{"OpaqueLighting":"deferred_lighting"}},
  {"name":"present", "fragment":"demo:present", "phase":"final"}
]
```

Passes execute in the phase order above, preserving manifest declaration order
within each phase. Pass-name inputs must refer to an earlier scene result in that
execution order. A consumer cannot read a later phase, even if its producer appears
earlier in the manifest. Quality/capability selection still validates that every
required producer is selected. Named colour targets and storage images retain
their existing feedback and explicit resource semantics.

`ColorSampler` starts from the current host colour at each phase and chains scene
results within that phase. `final` starts with the composite result when one
exists. Use named `inputs` to carry other results across phases. `SceneSampler`
continues to reference the current host colour attachment. Early results are not
automatically copied over host world drawing: only the last scene result from
`composite` or `final` is presented. A pack with only earlier phases or compute
passes leaves the host presentation untouched.

The renderer creates owned resources before the first phase. Named targets are
initialized on allocation; later `clearEachFrame` clears run exactly once at frame
start, before `setup`/`begin`, and never repeat at the post-world callback. Storage
resources and target feedback persist until their pack/world/viewport lifetime
ends. Referenced pass-name outputs keep independent snapshots across phases;
referenced `setup` outputs also remain available on later frames of that lifetime.
World changes, viewport changes, pack switches/reloads and explicit renderer reset
reinitialize owned storage and rerun setup. Existing settings that rebuild the
renderer, such as quality/ray-mode changes, also start a new lifetime. Parameter
edits keep the current lifecycle. A capability change that changes the selected
pass graph also restarts owned resources and setup; an unchanged graph preserves
them.

All selected custom uniform blocks use one host snapshot and immutable uploaded
slices prepared before the first phase. Their frame index, matrices and expression
time step cannot advance between phases. `MarineFrame` keeps its 288-byte ABI;
lightmaps acquire no world bindings.

The opaque boundary uses Minecraft's actual render-thread command encoder. With
classic transparency, GreenMarine defers the classic draw until the solid render
pass closes, executes the pack phase, then opens a separate transparency pass with
Minecraft's default uniforms and fog. With OIT, the phase executes before
`executeOit`, after the solid pass closes. No nested Vulkan render pass is created.

## Sampled world depth

Scene, compute and ordinary world programs may bind renderer-owned forward-depth
snapshots through `inputs`. These producer names cannot be declared as pack
targets, resources or pass names:

| Input producer | Captures |
| --- | --- |
| `world_depth` | Opaque depth before `deferred`, refreshed after world transparency before `composite` |
| `opaque_depth` | Depth at the opaque boundary, retained through transparency |
| `opaque_no_hand_depth` | Opaque depth without hand geometry |

Each requested snapshot is an owned full-resolution `R32F` image. Values use
forward window depth, with clear/background at 1. Snapshots reset to 1 before
early phases each frame, rather than exposing previous-frame depth. All bindings
use nearest sampling. They are converted from actual borrowed host reversed
depth outside render passes, so world shaders never sample their attached depth.
World/viewport/pack changes and failures release the owned snapshots. Packs that
do not request these inputs allocate no depth snapshots.

The automatic converter maps `depthtex0`/`gdepthtex`, `depthtex1`, and `depthtex2`
to these producers. The current host draws the hand after world postprocessing;
`opaque_depth` and `opaque_no_hand_depth` therefore capture the same world
boundary. Original Iris hand execution and its ordering remain unsupported and
explicitly block conversion of packs requesting hand programs. These snapshots
do not establish complete Iris hand/depth parity or full Eclipse readiness.

Actual Vulkan readback covers initialized early depth, opaque retention through
water drawing, deferred/composite sampling, compute sampling and comparison with
the real host depth attachment. The controlled terrain fixture excludes other
entities/particles and hides the HUD/hand. Its independent shader reference is
terrain-only. CPU and GPU costs of this extension have not been benchmarked.

Vulkan acceptance on RX 9070 XT covers ordered execution and immutable host values
across all seven phases, one setup execution over four frames, mixed float/uint
MRT writes across prepare/deferred/composite, retained outputs, frame clears and
final presentation. It does not establish complete Eclipse world/shadow rendering
or another backend's runtime support. CPU submission timing includes pack phases;
the existing GPU profiler measures the post-world composite/final region only,
so those timings cover different regions for packs with early phases.
