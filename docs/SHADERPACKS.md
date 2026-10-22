# Shaderpacks and API v1

For editable directories/ZIPs loaded directly from the game directory, the raster
default and the authoring CLI, see [external packs](EXTERNAL-PACKS.md). Existing
resource-pack loading described below remains available.

A mounted Minecraft resource pack supplies a manifest at
`assets/<namespace>/greenmarine/packs/<name>.json`. GreenMarine discovers packs
on resource reload. Mod assets need a resource loader/mounting mechanism or
the direct Java registration below; Fabric Loader alone does not mount every
mod's assets. GreenMarine mounts its own built-in resources without Fabric API.
The shipped packs (Marine Raster, Seabreeze, Solstice RT) are under
`src/main/resources/assets/greenmarine/greenmarine/packs/`. Smaller fullscreen
examples (Portable lightmap/grade, Advanced bloom, Radiance voxel emitters, Aurora
world rays) live in `src/samples/resources` as API fixtures; they are not in the
release jar but remain complete starting points. Schema v1 supports a lightmap pass,
ordered scene passes and world programs (below).

## Manifest

```json
{
  "schema": 1,
  "id": "demo:daylight",
  "name": "Daylight",
  "script": "demo:greenmarine/scripts/daylight.lua",
  "parameters": {
    "exposure": {"default": 1.05, "min": 0.25, "max": 4.0}
  },
  "passes": [
    {"name":"lightmap", "stage":"lightmap", "fragment":"demo:core/lightmap"},
    {"name":"grade", "fragment":"demo:core/grade"}
  ]
}
```

Script resources use their exact namespaced paths. Fragment `demo:core/grade`
loads `assets/demo/shaders/core/grade.fsh`. All ids must be namespaced. Up to
32 parameters use declaration order as shader slots, and up to eight passes
are accepted. Each pass has a unique name; at most one is a lightmap stage.
Optional `scale` is 1, 0.75, 0.5 or 0.25 (lightmap always 1). Scene dimensions
round up, preserving at least one pixel. `minQuality` defaults to `low` and
can be `medium`, `high` or `ultra`. Optional `format` is `scene` (default)
or `rgba16f` for scene-stage intermediate lighting/HDR. Lightmaps retain their
fixed format. A final incompatible format is converted by a draw, never copied
as raw mismatched texels. Tonemapping remains the pack's responsibility.
Float target allocation failure falls back to scene format with a log.

Scene passes may opt into previous-frame output with `"history": true`; the
default is false and lightmap passes reject this option. Each opted-in pass
owns its own color ping-pong targets, matching its scale and actual allocated
format. Existing eight- and nine-argument Java `PackPass` constructors retain
history disabled. The additive ten-argument constructor accepts the flag.

`"lightingHistory": true` additionally allows that pass to keep history across
native lighting scene publications. It requires `"history": true` on a scene
pass. The default is false: existing packs and every previous `PackPass`
constructor keep their previous reset behavior. The additive twelve-argument
constructor accepts this boolean after the named-input map. This opt-in only
preserves the input; the shader must reject incompatible surfaces and bound its
transition. General history invalidation still applies to every pass.

Scene passes can also sample named outputs from earlier scene passes in the
same frame. For example:

```json
"passes": [
  {"name":"lighting", "fragment":"demo:core/lighting", "format":"rgba16f"},
  {"name":"blur", "fragment":"demo:core/blur", "scale":0.5},
  {"name":"compose", "fragment":"demo:core/compose",
   "inputs":{"CachedLighting":"lighting"}}
]
```

The composition shader declares `uniform sampler2D CachedLighting;`. Its
`ColorSampler` still receives `blur`, and `SceneSampler` still receives the
original world image. Named inputs use linear clamp-to-edge sampling. Referenced
outputs retain independent storage until their consumers finish, including
across intervening passes with matching dimensions and formats. They contain
the current frame's output, not previous-frame history.

`inputs` maps at most eight sampler names to producer pass names. Sampler names
match `[A-Za-z_][A-Za-z0-9_]{0,63}`; `gl_` prefixes, double underscores and builtin
binding names such as `ColorSampler`, `SceneSampler`, `MarineFrame`,
`MarineSurfaceAlbedo`, `RayLighting`, `VoxelMaterials` and history bindings are
reserved. Values must be strings naming an earlier scene pass in declaration
order. Unknown, self, forward and lightmap references are rejected; lightmaps
cannot declare inputs. If quality or capabilities skip a producer needed by a
selected consumer, rendering fails explicitly rather than sampling stale data.
A selected fallback has the same inputs as its primary fragment. Packs should
keep producer and consumer selection compatible across their quality profiles.

Existing eight-, nine- and ten-argument `PackPass` constructors default to empty
inputs. The additive eleven-argument constructor accepts the immutable
`Map<String,String>` snapshot after the history flag. Resource reload, output
resize, schedule changes and render failure release retained output storage.


Optional `requires` is `none`, `vulkan`, `voxel_rt` or `hardware_rt`. A missing
capability selects the pass's optional `fallback` fragment; without one the
pass is skipped. Quality below `minQuality` skips it entirely. Every selected
pack is independently responsible for a coherent fallback chain. A malformed
manifest, missing source, bad script initialization or duplicate pack id
preserves the last valid resource catalog. A GPU shader error disables the
active pack rendering until switch/reload while ordinary Minecraft continues.
It is not an automatic fallback to a previously compiled pack.

## Shader ABI

Fragment shaders receive a full-screen triangle with `texCoord` at location 0
and must write a vec4 at output location 0. GLSL 330 uses
`GL_ARB_separate_shader_objects`. Pipeline samplers are:

| Name | Scene stage | Lightmap stage |
| --- | --- | --- |
| ColorSampler | Previous scene pass or initial world image | Original vanilla lightmap |
| SceneSampler | Initial full-resolution world image after point lights | Original vanilla lightmap |
| DepthSampler | Original scene reverse depth | Lightmap color (depth has no meaning here) |
| LightmapSampler | Active world lightmap | Original vanilla lightmap |

Uniform blocks/samplers not used by a shader may be omitted. Point lighting
runs before pack scene passes; the hand and HUD are composed later. A reduced
resolution effect can sample SceneSampler during a final full-resolution
composite to preserve detail instead of blurring the entire world.

The std140 `MarineFrame` block is exactly 288 bytes:

```glsl
layout(std140) uniform MarineFrame {
    vec4 Frame;                 // width, height, time seconds, day fraction
    vec4 Context;               // depth 0..1 flag, quality enum, effective ray enum, reserved
    mat4 InverseProjection;     // offset 32: actual submitted world projection
    mat4 InverseViewRotation;   // offset 96
    vec4 Parameters[8];         // offset 160: 32 declaration-ordered float slots
};
```

Quality enum is Low=0, Medium=1, High=2, Ultra=3. Effective ray enum is
Off=0, Voxel=2, Hardware=3; Auto is resolved before upload. Time advances with
unpaused game ticks. Depth is reverse Z, with sky at zero. View reconstruction:

```glsl
float z = Context.x > 0.5 ? depth : depth * 2.0 - 1.0;
vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, z, 1.0);
vec3 relativeWorld = (InverseViewRotation * vec4(view.xyz / view.w, 0.0)).xyz;
```

Use standard `#include <demo:frame.glsl>` with
`GL_GOOGLE_include_directive`; the resource is
`assets/demo/shaders/include/frame.glsl`. Includes are captured with the pack,
with depth/source limits, so failed reloads retain their previous contents.
Cyclic includes are rejected. This prototype does not inject shader code into
vanilla programs; it owns the declared passes only.

## Lua and engine extensions

```lua
function update(frame)
    marine.uniform("exposure", 1.05 + math.sin(frame.time) * 0.05)
    marine.light(0, frame.camera.x + 2, frame.camera.y, frame.camera.z,
                 0.2, 1.0, 0.5, 4.0, 1.5)
end
```

`update(frame)` runs once per unpaused tick while enabled. A scripted light can
follow the camera as above, or keep coordinates in Lua state for a fixed lamp.
Frame fields: `time`, `day`, `camera.x/y/z`, lowercase `quality` and effective
`rays`, and capabilities `vulkan`, `hardware_rt`, `voxel_rt`, `spirv`.

`marine.uniform(name, value)` validates the declared range and stages an
update. A failed tick preserves the last valid parameters. `marine.light(slot,
x,y,z,r,g,b,radius,intensity)` creates/updates owned world lights in slots
0..31. `marine.remove_light(slot)` removes one. RGB is 0..1; radius is positive;
positions/intensity are finite. `marine.mix(a,b,t)` and `marine.clamp(v,min,max)`
are engine helpers. Standard math functions and ordinary Lua control flow and
tables are available. Heavy lighting/ray algorithms belong in shaders/native
extensions, not per-pixel Lua.

F6 parameter overrides are stored separately per pack, clamp to current
ranges after reload and ignore retired names. Explicit overrides win after
Lua runs; removing them restores the most recent authored value. GUI edits
preview live without reinitializing scripts/pipelines and save on release or
close. The UI handles the 64-pack/32-parameter storage limits without crashes.

Lua is bundled; it needs no separate installation. Sources are limited to
64 KiB, updates to 20,000 instructions and 64 nested calls. String growth is
limited to 64 KiB; retained table entries are limited to 2048 and the inspected
Lua object graph to approximately 1 MiB/16,384 values. These are execution
budgets, not a claim to account for every JVM/native allocation. IO, OS, Java
reflection, dynamic code loading, coroutine and public debug/package libraries
are absent. The script error is logged once and its owned lights are removed;
pack rendering returns to ordinary Minecraft if that script fails.

Installed trusted Java mods can extend `marine` with per-pack Lua function
factories, without filesystem/Java reflection being exposed to pack scripts:

Extension authors use `compileOnly 'org.luaj:luaj-jse:3.0.1'` for their Java
compile classpath. GreenMarine already bundles LuaJ at runtime.

```java
AutoCloseable registration = PackScript.registerExtension("my_wave", () ->
    new org.luaj.vm2.lib.OneArgFunction() {
        @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue input) {
            return org.luaj.vm2.LuaValue.valueOf(Math.sin(input.checkdouble()));
        }
    });
```

Extensions are installed into newly created scripts and must be bounded engine
operations. Trusted Java callbacks can allocate or block outside Lua's budget;
the mod implementing them owns that responsibility. Close the registration to
remove it from future scripts. Core function names cannot be replaced.

## Java pack registration

Mods whose assets are mounted into Minecraft's resource manager (for example
by Fabric API's resource loader) can contribute manifests as above. Standalone
mods can instead read their own bundled sources and register an immutable
loaded pack through `GreenMarine.PACKS.register(loadedPack)`, requiring no
Fabric API. Registration is explicit; GreenMarine does not discover arbitrary
unmounted mod assets. `PackCatalog.LoadedPack`
contains a `ShaderPack` definition, Lua source and a map keyed by fragment ids;
include sources use `include|<namespaced id>` keys. Optional binaries are
immutable `ShaderBinary` instances mapped by fragment id. Keep and close the
returned lease to remove the registration. Registered packs override resource
packs of the same id and survive resource reload; duplicate Java registrations
are rejected. Pass definitions enforce the same schema limits. Mods own
shader correctness and supplied include resources. Lights are independently
available through `GreenMarine.LIGHTS` and immutable `Light` values.

For a minimal pack, parse a manifest and supply its Lua/GLSL directly:

```java
ShaderPack definition = ShaderPack.parse(
    JsonParser.parseString(manifestJson).getAsJsonObject());
PackCatalog.LoadedPack loaded = new PackCatalog.LoadedPack(definition,
    "function update(frame) end",
    Map.of("demo:core/grade", fragmentSource));
AutoCloseable registration = GreenMarine.PACKS.register(loaded);
```

Read `manifestJson` and `fragmentSource` using your mod's own resource/classloader
access. The source map must contain every declared fragment and fallback.
Register before selection and keep the lease for the mod's lifecycle.

## Optional SPIR-V

Add a `spirv` resource id to a pass while retaining its `fragment` GLSL:

```json
{"name":"grade", "fragment":"demo:core/grade", "spirv":"demo:greenmarine/spirv/grade.spv"}
```

On Vulkan, compatible modules are reflected and fed directly to Renderpearl;
its pipeline builder still handles resource bindings and ownership. OpenGL
uses GLSL. Binary reflection failure or capabilities outside the v1 baseline
profile use GLSL too. V1 permits Shader, Sampled1D, ImageQuery and
DerivativeControl capabilities. This does not enable hardware RT or add device
features. Every binary must expose a fragment `main` using the same input/output
locations and uniform/sampler names as its GLSL fallback.

For a shader without includes, generate a baseline Vulkan 1.1 module with:

```sh
glslangValidator -V --target-env vulkan1.1 --auto-map-bindings --auto-map-locations \
  -S frag -o grade.spv grade.fsh
spirv-val --target-env vulkan1.1 grade.spv
spirv-opt -O grade.spv -o grade-optimized.spv
spirv-val --target-env vulkan1.1 grade-optimized.spv
```

For namespaced includes, resolve/flatten them before invoking offline tools.
Keep uniform/sampler reflection names; do not use debug-name stripping.
Other languages or hand-authored SPIR-V assembly can produce the same module
via their tooling; GreenMarine consumes `.spv`, not a Slang/HLSL compiler.
Runtime performs size/header/instruction/stage checks (4 MiB maximum); those
are structural checks and do not replace offline semantic validation.
Precompilation avoids the GLSL-to-SPIR-V step at load, but does not promise
higher FPS. Optimize the algorithm, sampling, resolution and work budgets;
measure GPU time on the target hardware.

## World voxel shader API

Scene shaders may include `greenmarine:voxel.glsl` using the same snapshot
include mechanism. All scene stages receive initialized VoxelFrame and
VoxelMaterials bindings, including `requires: none` and fallback fragments.
`VoxelTracing.x` is 1 only when effective Voxel mode has a complete snapshot;
it is 0 while unavailable/Off. Lightmap stages do not receive world bindings.
Use `requires: voxel_rt` and an explicit fallback to skip expensive tracing.

```glsl
#include <greenmarine:voxel.glsl>
MarineVoxelHit hit = marineVoxelTrace(relativeWorldOrigin, worldDirection, 12.0);
if (hit.hit) {
    vec3 albedo = hit.material.rgb; // map-color, encoded sRGB
    float emission = hit.material.a; // vanilla 0..15 light emission / 15
}
```

The 4128-byte std140 block contains `vec4 VoxelOriginSize` (camera-relative grid
origin and size=32), `vec4 VoxelTracing` (ready, point-shadow limit=4, steps=96,
bias=0.06), and `uvec4 VoxelOccupancy[256]`. `VoxelMaterials` is a 256x128 RGBA8
atlas with index `(z*32+y)*32+x`, texel `(index%256,index/256)`. RGB is block map
color; alpha stores emission in increments of 17. Geometry/materials publish
together after a completed scan; only changed completed data uploads.

`marineVoxelMaterial(ivec3 cell)`, `marineVoxelOccupied(ivec3 cell)` and
`marineVoxelTrace(origin,direction,maxDistance)` use local cell coordinates and
camera-relative world origins/directions. Trace returns `hit`, `material`,
`position` (camera-relative cell center), and `distance`. It clips to the volume,
returns first occupied or emitting cell, and stops after 96 crossings or the
requested distance. Missing chunks are empty; full opaque blocks occlude and
partial/non-solid emitter geometry is approximated by cells. Check readiness
before custom direct atlas reads; helper material lookups return zero when
unavailable. Rays are ordinary shader DDA, with no enabled hardware RT claim.

The Radiance sample (`src/samples`) estimates diffuse emitter lighting using 4 High/8 Ultra
cosine hemisphere rays per shaded surface at half resolution. RGB radiance and
view distance use RGBA16F; the final pass rejects depth mismatches when gathering
and retains original full-resolution scene detail. It uses world emitter data,
not neighboring screen color. It adds to vanilla illumination without separating
vanilla block-light energy, so energy can be counted twice. No multi-bounce
transport, ray feature outside
this local volume, temporal filter or complete material renderer is implied.

## Java renderer contributions

The additive [public rendering API](RENDERING-API.md) provides owned GPU
resources, immutable GLSL/SPIR-V programs, dependency graphs and mesh/material
passes. `ShaderProgram.fromPack` reuses catalog source/include/binary snapshots.
Schema-v1 manifests and the 288-byte MarineFrame ABI remain unchanged; Java
contributions initialize their declared bindings explicitly.

## Optional scene history

An opted-in pass receives `HistorySampler`, its own previous submitted color
output, and `HistoryNormalDepth`, the previous full-resolution terrain surface
normal/device-depth capture. Color uses linear sampling and normal/depth uses
nearest sampling. History inputs are separate from the active color output.
All owned targets are initialized before use; first frames and discontinuities
advertise readiness false. Missing terrain capture leaves normal history false
even when the previous color is available.

The separate 160-byte std140 block is additive; MarineFrame remains 288 bytes:

```glsl
layout(std140) uniform MarineHistoryFrame {
    mat4 HistoryProjection;
    mat4 HistoryView;
    vec4 HistoryCameraDelta;
    vec4 HistoryInfo;
};
uniform sampler2D HistorySampler;
uniform sampler2D HistoryNormalDepth;
```

`HistoryInfo` contains previous-color readiness, previous-normal/depth
readiness, consecutive submitted history age, and elapsed seconds since the
last successful submission (clamped to 0..0.25, zero when unavailable).
`HistoryCameraDelta.xyz` is current camera position minus previous camera
position; `.w` is zero. Reproject a current camera-relative world position with:

```glsl
vec4 previousClip = HistoryProjection * HistoryView
    * vec4(relativeWorldPosition + HistoryCameraDelta.xyz, 1.0);
vec2 previousUv = previousClip.xy / previousClip.w * 0.5 + 0.5;
```

Check readiness, positive clip W and UV bounds before sampling. Reject
disocclusion and incompatible previous normal/depth in the shader. Captured
terrain depth is device depth, so expected previous clip Z must use the
backend's zero-to-one convention from MarineFrame. Color readiness alone does
not establish surface correspondence. Entities and unsupported terrain are
outside this terrain history's normal/depth coverage.

A `lightingHistory` pass additionally receives `HistoryAlbedoMaterial` (the
previous RGBA16F linear albedo and encoded material class/roughness) and
`HistorySceneDepth` (the previous final scene device depth). Both are
full-resolution, nearest-sampled and independent from current inputs. They are
copied after the pass; normal readiness is false if any required capture cannot
be copied. Ordinary `history` passes retain their existing bindings and cost.
Compare current and previous scene depth against the corresponding captured
surface depth to reject opaque entities in either frame. Compare albedo and
encoded material before preserving lighting across geometry publications.
At boundaries, color interpolation must not include rejected neighboring
surfaces: validate every contributing texel or fetch the validated texel.

Native lighting scene publication discards history unless the pass opts into
`lightingHistory`. `PackRenderer.invalidateLightingHistory()` preserves opted-in
histories; `invalidateHistory()` invalidates every pass. Resize, pack/resource
reload, world change, selected path/quality change and parameter override edits
discard history for all passes. Camera movement over eight blocks,
rotation over 45 degrees, and perspective FOV/aspect changes also reject it.
Rigid bob/hurt projection rotation and translation retain history; reprojection
uses the actual previous projection, including those transforms. Failed
submissions do not publish new history. Disabling scene rendering invalidates
it, and lifecycle reset closes only GreenMarine-owned targets.

This provides temporal accumulation inputs, without projection jitter or a
jittered TAA claim. A pack chooses its rejection tests and blend weights;
runtime backend rendering acceptance requires separate Vulkan/OpenGL evidence.

## Hardware model rays and readiness

Packs declaring `hardware_rt` passes use native Vulkan model ray queries
(Solstice RT; `examples/aurora-rt` is a small standalone example). See
[HARDWARE-RT.md](HARDWARE-RT.md) for
RayFrame80, initialized scene-only samplers, finite coverage, status states and
platform limits. MarineFrame288 and declaration-ordered parameter slots are
unchanged. Public graph imports carry the same ready data. No Iris format
compatibility follows from these additions.

Runtime ray selection considers the selected pack's declared requirements.
Hardware/Auto chooses hardware for a pack declaring hardware_rt; existing packs
without hardware passes retain portable voxel selection. This prevents enabling
the native feature from silently replacing older voxel passes with fallbacks.
The original public RenderPolicy.rays overload retains its general selection;
the additive pack-aware overload supplies runtime behavior. F6 reports the reason.

## World programs

Schema 1 additionally accepts optional `programs` with `target`, optional `vertex`,
`fragment`, and `worldRays`. Existing passes and parameters keep their layout.
See [world shader programs](WORLD-PROGRAMS.md) for the host interfaces, automatic
bindings, OIT requirements and the Seabreeze example.
