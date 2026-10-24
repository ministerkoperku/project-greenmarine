# Iris → GreenMarine conversion (target: Eclipse, Bliss fork)

Goal from the owner: the converter takes an Iris pack and produces a GreenMarine-format
pack that can be edited further in dev tools. Packs built on the same well-known
techniques (sun shadow maps, SSR, screen-space GI) may get GreenMarine RT applied
automatically. Eclipse (github.com/Merlin1809/Eclipse-Shader, "© Chocapic13, all
rights reserved") is the reference pack. It is a local test fixture only: never commit
it or a converted copy, never publish converted output. The converter is ours.

## What Eclipse needs (inventory 2026-10-24)

- 751 files, 49 MB (moon.png alone 20 MB, block.properties 1.3 MB), ~36k GLSL lines.
- 285 vsh/fsh pairs, 44 compute (`.csh`), 12 geometry, 6 tessellation pairs.
- `#version 430 compatibility`, SSBOs, 3D image load/store (LPV floodfill, voxel mask),
  wave simulation images up to 8192², ~25 custom textures, custom uniforms written in
  Iris' expression language, option-dependent `shaders.properties`, DH and Voxy programs,
  colorwheel/photonics optional programs.
- 13 composites + deferred1-2 + prepare + begin/setup compute, gbuffers MRT through
  `RENDERTARGETS` comments into colortex0-15, shadow pass with shadowtex0/1/shadowcolor.

## What the host has

- Renderpearl (MC 26.3) has **no compute**. GreenMarine's native engine already records
  Vulkan compute into Minecraft's own command stream on Minecraft's device (hardware RT).
  That path is how Iris compute/image programs can run.
- Java `RenderGraph` already does multi-attachment raster passes with owned textures.
- World programs swap host shaders but keep host attachments (single color target).
- No shadow pass. RT sun visibility exists (surface tracer) and can stand in.

## Milestones and how coverage is measured

Coverage is reported per milestone as programs passing / programs enabled by default
in Eclipse's world0 with default options, so the number stays honest.

1. **IR-1 translate+compile:** Iris preprocessing (absolute/relative includes, Iris
   predefined macros, option defaults, `shaders.properties` with conditionals), then
   compat GLSL → Vulkan GLSL (core profile, built-in matrices/attributes, `gl_FragData`
   and `RENDERTARGETS`, varyings with matched locations, loose uniforms into a block,
   texture2D & co). Metric: % of program stages that compile to valid SPIR-V.
2. **IR-2 pack format:** GreenMarine schema additions the converter emits: named
   render targets (colortex/shadowtex), MRT fullscreen passes, compute passes,
   image/SSBO resources, custom textures, an Iris uniform block filled by the host,
   larger asset limits. Converted output stays editable GLSL in the workspace.
3. **IR-3 post chain runs:** deferred/composite/final execute in game through the
   render graph. Until gbuffers run, colortex inputs come from GreenMarine's material
   capture (albedo/normal/depth) — the "auto apply" mapping.
4. **IR-4 compute:** begin/setup/prepare/deferred/composite compute via the native engine.
5. **IR-5 gbuffers:** world programs with MRT attachments for terrain/entities/water.
6. **IR-6 shadows:** shadow-map lookups either from a real shadow pass or mapped onto
   RT sun visibility (auto RT for packs using the standard shadow sampling path).
7. **IR-7 DH/Voxy/geometry/tessellation** last, only where the host can support them.

Every milestone ends with the number, a test and evidence; no parity claims beyond it.

## Progress

- **IR-1 done (2026-10-25): 250/250 default program stages of Eclipse world0/-1/1 = 100%**
  translate and compile to strict Vulkan SPIR-V (vertex 109, fragment 109, compute 20,
  geometry 6, tessellation 3+3). Mod-integration programs that Iris only loads beside
  another mod (Colorwheel `clrwl_*`, Distant Horizons `dh_*`, Voxy, Physics Mod,
  Photonics `*_voxels`) are reported separately: 28/50, not counted.
  Pieces: `iris/IrisSources` (dir/zip), `IrisPreprocess` (Iris include rules, comment
  stripping, `RENDERTARGETS`/`DRAWBUFFERS` kept as an identifier pragma),
  `MiniPreprocessor` (conditionals with float comparisons like OptiFine, redefinition
  allowed), `IrisTranslator` (compat built-ins → `iris_*` host inputs, loose uniforms →
  one std140 `IrisUniforms` block at binding 0, opaque bindings from 1, SSBO bindings
  +100, Vulkan-reserved names renamed), `IrisCompiler`, `IrisCoverage`
  (`./gradlew -p development-tools irisCoverage -PirisPack=<pack>`; the same property
  turns on the whole-pack test).
  Honest limits: varying locations still come from shaderc auto-mapping (must be matched
  explicitly before IR-3 links stages), and compiling says nothing about rendering.
