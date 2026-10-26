# GreenMarine schema 1 authoring reference

This reference describes the current repository parser and runtime. The bundled
repository documents and GLSL includes provide full contracts and implementation
examples. The bundled api/**/*.java entries are **Java-only APIs** for trusted Java
mods; their methods are not callable from shaderpack Lua or GLSL. A manifest remains
editable JSON: templates are starting files, not a subset of the schema.

## Files and resource identifiers

A manifest lives at assets/<namespace>/greenmarine/packs/<path>.json. Its id is
independent of the filename. Fragment ID demo:core/grade resolves to
assets/demo/shaders/core/grade.fsh; vertex uses .vsh and compute .csh.
Script, materialAddons and spirv identifiers use their exact asset path and extension.
#include <demo:file.glsl> resolves to assets/demo/shaders/include/file.glsl.
Includes may depend on GreenMarine or Minecraft mounted resources. The starter
copies its GreenMarine include dependencies; Minecraft includes come from the game.
Use GL_GOOGLE_include_directive for standalone shaderc include compilation.

Resource IDs match [a-z0-9_.-]+:[a-z0-9_./-]+, at most 192 characters, with no '..',
':/' or trailing slash. External folders/ZIPs belong in shaderpacks/greenmarine/;
ZIP assets/ sits at its root. Symlinks, traversal and duplicate ZIP paths are rejected.
Runtime external limits: 64 containers/manifests, 2048 entries/container, 8 MiB assets
per container, 32 MiB per scan; text 256 KiB, manifest/Lua 64 KiB, SPIR-V 4 MiB.
Catalog include traversal rejects cycles and depth beyond 16; source map above 80
entries also fails. Validation does not replace GPU/backend acceptance.

## Manifest root (all current fields)

- schema: required numeric 1.
- id: required namespaced resource ID, unique among loaded packs.
- name: optional display name, defaults to id; nonblank, at most 80 characters.
- script: optional exact namespaced Lua resource ID.
- parameters: optional object, at most 32 declaration-ordered float parameters.
- passes: required array, possibly empty; at most 8 scene/lightmap plus 16 compute.
- programs: optional world program array; at most one program for each target.
- materialAddons: optional array, at most 16 unique exact JSON resource IDs ending .json.
- resources: optional object containing only images and buffers.

Each parameters.<name> has required default, min and max finite floats;
min <= default <= max. Names match [a-zA-Z_][a-zA-Z0-9_]{0,47}. Declaration order
selects uniform slots, not alphabetical order. Keep existing slot order when editing.

## Pass fields (all current fields)

- name: required unique [a-z0-9_/-]{1,48}, distinct from image/buffer names.
- stage: scene (default), lightmap or compute.
- fragment: required namespaced shader ID for scene/lightmap; forbidden on compute.
- compute: required namespaced .csh shader ID for compute; forbidden on raster stages.
- fallback: optional fragment ID selected if requires is unavailable. It shares inputs.
- requires: none (default), vulkan, hardware_rt, voxel_rt, compute.
- minQuality: low (default), medium, high, ultra; lower quality skips the pass.
- scale: 1 (default), 0.75, 0.5, 0.25. Lightmap/compute require 1.
- spirv: optional exact fragment binary resource ID; GLSL fragment remains required.
- format: scene (default), rgba16f. Only scene can use rgba16f; tone mapping is authored.
- history: optional boolean false; only scene may opt into previous-frame output.
- inputs: optional object mapping up to 8 sampler identifiers to an earlier scene pass
  name or declared pack image. Unknown, self, forward and lightmap producers fail.
  Sampler identifiers match [A-Za-z_][A-Za-z0-9_]{0,63}; gl_ prefixes, '__' and
  builtin binding names are reserved. Sampler keys cannot shadow pack resource names.
- lightingHistory: optional false; requires scene stage and history true.
- groups: compute-only required 'screen', 'image:<declared image name>', [x,y,z]
  or 'x,y,z'. Fixed counts are each 1..99999 in the parser and must fit actual device
  dispatch limits. Screen/image counts ceil-divide dimensions by reflected local size.

At most one lightmap, with no inputs/history/HDR. Compute rejects fallback, spirv,
nondefault scale/format and history; lightingHistory is also invalid on compute.
Compute accepts name, stage, compute, groups, inputs, requires and minQuality.
Pass execution order matters. Capability/quality-skipped producers must not leave a
selected consumer needing missing outputs. Keep a coherent compatible raster chain.
The compute starter skips compute and its consumer together, then grades the scene.

## Persistent resources and compute

resources.images.<Name>: format defaults rgba16f; size [width,height], each 1..8192,
or scale 1, 0.75, 0.5, 0.25 (default 1), never both. Formats: rgba8, rgba16f,
rgba32f, r32f, rg32f, r16f, rg16f, r32ui, rg32ui, rgba32ui, r11g11b10f.
The last format's GLSL qualifier is r11f_g11f_b10f.
resources.buffers.<Name>: required size in bytes, 4..64 MiB, multiple of 4;
at most 128 MiB buffers total. At most 16 images and 16 buffers. Names follow the
binding identifier rules above and are unique across images/buffers.

Compute is Vulkan-only on Minecraft's actual host device. Availability is true only
after a real pipeline initializes. Bind image2D and buffer blocks by declared name;
MarineFrame, ColorSampler, SceneSampler, DepthSampler, LightmapSampler are builtins.
inputs sampler aliases must differ from the image resource name. Images/buffers
persist, clear on pack/reload/recovery; scaled images clear on resize. Do not sample
and write the same image in a dispatch. Storage format support and workgroup limits
are device-specific. Scene storage writes require enabled fragmentStorageWrites;
Java-only computeCapabilities() reports actual initialized device capabilities.
No 3D/image arrays/mip chains, push constants, separate samplers, indirect dispatch
or compute in world/lightmap programs. See docs/COMPUTE.md for binding and Java graphs.

## MarineFrame and builtins

MarineFrame is exactly 288 bytes, std140: vec4 Frame at 0 (width,height,time,day),
vec4 Context at 16 (zero-to-one depth, quality, effective rays,reserved),
mat4 InverseProjection at 32, mat4 InverseViewRotation at 96,
vec4 Parameters[8] at 160 (32 floats in declaration order).
Quality: low 0, medium 1, high 2, ultra 3; effective rays off 0, voxel 2, hardware 3.
Auto is resolved before upload. Time follows unpaused ticks. Reverse Z has sky at 0.
Frame includes supply marineParameter, marineViewPosition, marineRelativeWorldPosition.

Scene fullscreen input texCoord vec2 location 0; output vec4 location 0.
ColorSampler is previous scene result; SceneSampler is original full-resolution
world after point lights; DepthSampler is original reverse depth; LightmapSampler
is active world lightmap. In lightmap passes all these are vanilla lightmap color;
there are no world/voxel/ray bindings. Scene voxel/ray bindings are initialized when
unavailable: check VoxelTracing.x or marineHardwareRayReady(), never infer readiness
from GPU support alone. See frame.glsl, voxel.glsl, rays.glsl and surface.glsl.

## World programs (all current fields)

- target: terrain (default), block, entity, sky, clouds, weather, particles, background.
- vertex: optional shader ID; omission retains the host vertex shader.
- fragment: required shader ID matching the host varyings/attachments/defines.
- worldRays: optional false; requests the world ray path, not a hardware support promise.
- surface: optional terrain-only capture fragment ID.
- surfaceOnly: optional false; requires surface and worldRays true.
- voxelShadows: optional false; valid only for terrain.
- surfaceBeforeTerrain: optional boolean; true requires terrain and surface.
  Default false except the shipped greenmarine:core/solstice_terrain plus
  greenmarine:core/solstice_surface pair, which defaults to true.

Background requires explicit vertex/fragment and no ray/surface/voxel flags. It
writes a fullscreen sky triangle at far reverse depth (0 for zero-to-one, -1 otherwise)
with no sampled color/depth. Preserve the target's host vertex attributes, varyings,
OIT/ALPHA_CUTOUT/MULTIDRAW_TERRAIN defines, state and uniform interface. The world
starter uses current raster terrain/sky/clouds sources. Other targets have routing;
independent gameplay acceptance of every variant is not claimed. Custom IDs require
separate Sodium adapter acceptance. Check SurfaceInfo.x before opaque-copy reads.
RayLighting is linear irradiance to multiply by albedo, not scene RGB to add.

## Histories

history true adds per-pass previous color HistorySampler and previous terrain
HistoryNormalDepth, plus MarineHistoryFrame (160 bytes): HistoryProjection mat4,
HistoryView mat4, HistoryCameraDelta vec4, HistoryInfo vec4. HistoryInfo contains
color readiness, normal/depth readiness, age, elapsed seconds (0..0.25).
Check readiness, reprojection bounds and surface correspondence; shader owns rejection.
lightingHistory additionally preserves compatible history across native lighting
publications and supplies HistoryAlbedoMaterial and HistorySceneDepth. It does not
bypass resize/pack/world/path/quality/parameter reset, large camera moves or failures.
No automatic projection jitter/TAA is implied. See docs/SHADERPACKS.md and history.glsl.

## Lua pack API

function update(frame) runs once per unpaused tick. Frame fields: time, day,
camera.x/y/z, quality, rays and capabilities.vulkan/hardware_rt/voxel_rt/spirv/compute.
marine.uniform(name,value) validates declared parameter range; explicit user overrides
win after Lua. marine.light(slot,x,y,z,r,g,b,radius,intensity) owns slots 0..31:
RGB 0..1, positive radius, finite positions/intensity. marine.remove_light(slot),
marine.mix(a,b,t), marine.clamp(v,min,max) are available. math, control flow and
tables are available; filesystem/OS/Java/reflection/dynamic loading/coroutines are not.
Limits: source/string 64 KiB, 20000 instructions/update, call depth 64,
2048 retained table entries, inspected graph about 1 MiB/16384 values. Failure removes
script-owned lights and disables pack rendering; pack/world changes clean up lights.
Trusted Java-only PackScript.registerExtension is not callable from pack scripts.

## Material JSON (all current fields)

Root schema: numeric 1, id: namespaced ID, optional shaderpacks: up to 64 selector IDs,
textures: object at most 512 texture IDs. Each texture: optional default material,
optional mask exact PNG resource ID, optional palette object (up to 256 six-digit RGB
hex keys, optional leading '#'). At least default or mask is required.
Material: required surface, roughness, reflection, emission; three numbers finite 0..1.
Surface is integer 1..9 or opaque/matte/stone/wood (1), metal (2), puddle (3), water (4),
glass (5), ice (6), porous (7), gem/diamond (8), coated/screen/polished (9).
Only opaque mask pixels select palette entries; other pixels use default/earlier
layers/fallback. PNG masks and resolved texture dimensions 1..2048. Generic layers
apply before pack-specific layers. Private-pack JSON 64 KiB, PNG 256 KiB. Catalog
bounds 128 sources, decoded masks 64 MiB; CPU compiled maps LRU 32 MiB. Standalone
material discovery has larger JSON/PNG bounds described in docs/MATERIAL-ADDONS.md.
Screen emission changes raster appearance and does not create a native GI light.

## SPIR-V and Slang limitations

Runtime SPIR-V is optional fragment main only, maximum 4 MiB; preserve GLSL fragment,
locations and reflected uniform/sampler names. ShaderBinary structural checks accept
SPIR-V 1.0..1.6 headers, but the baseline allows only Shader, Sampled1D, ImageQuery,
DerivativeControl capabilities. Reflection failure/unsupported capability uses GLSL.
No RT/device feature is enabled by providing a binary. Validate offline with spirv-val.
Slang is an external executable authoring compiler, not a bundled runtime language.
The editor requires a configured platform-compatible slangc, bounded 15s execution,
and named main entry with the selected stage. Vertex/compute SPIR-V can be compiled
for inspection but schema spirv fields remain fragment-only; compute runtime loads
.csh and world programs load GLSL. Java-only rendering APIs have separate contracts.
