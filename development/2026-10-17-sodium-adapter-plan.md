# Sodium terrain adapter design and implementation plan

Authorization: the owner requested the compatibility fix and has authorized
project-scoped work and export. Execute inline; preserve the existing working
vanilla/Android path and do not require Sodium in base entrypoints or public APIs.

## Design

Target the reported Sodium 0.9.3-alpha.1 Minecraft 26.3 terrain ABI and the
built-in Solstice/Seabreeze terrain programs. Observe immutable host pipeline
definitions at the existing Renderpearl compile boundary. Recognize Sodium's
known shader pair and preserve its packed vertex bindings, constants, uniform
slots, topology and push constants. Use a source bridge to decode vertices
through Sodium's own runtime include and expose the existing pack shader inputs.
Normal draws retain their original attachment states; material replay uses
the existing two independent capture outputs and private depth.

The bridge adds the Fog binding used by the existing pack fragments, maps
projection, atlas/lightmap, region translation, texture controls and fade inputs,
and wraps the original pack vertex entrypoint after Sodium vertex initialization.
No geometry is rebuilt or read back to the CPU. No Vulkan handles are owned
by this adapter. Existing pack scripts, parameter order and native ABI remain.
Unknown terrain ABIs are not treated as compatible. This is a targeted adapter,
not general compatibility with arbitrary replacement renderers or shaderpacks.

## Steps

- [x] Add failing pipeline recognition/state tests and shader compilation/link
  tests using the matching upstream Sodium includes and cached Minecraft assets.
- [x] Implement the optional pipeline registry and packed-source bridge; observe
  definitions using a Renderpearl mixin without importing Sodium Java classes.
- [x] Route known Sodium terrain through world and capture variants; keep
  vanilla selection unchanged and provide actionable errors for unsupported replay.
- [x] Compile raster, capture, alpha cutout and OIT variants for Vulkan/OpenGL;
  verify pipeline attachment formats, existing uniform slots and push constants.
- [x] Run Java/Rust checks and Android packaged FFM smoke. Review the whole diff.
- [x] Export a new Android JAR, source archive, compatibility notes and checksums
  to Documents/Koper/recents, preserving prior exports. Separate compile/native
  evidence from the owner's next actual Sodium gameplay acceptance.

## Performance acceptance

Compare identical worlds, camera, resolution, chunk distances, pack quality and
ray mode with/without Sodium after warmup. Report CPU/GPU timings and frame-time
distribution separately. Sodium can reduce chunk draw/submission overhead;
shader/RT GPU cost and material replay remain. No FPS increase is promised.
