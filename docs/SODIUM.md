# Sodium compatibility

GreenMarine works alongside Sodium **0.9.3-alpha.1+mc26.3**. Sodium stays optional and
GreenMarine links none of its classes. Both rendering and hardware surface RT have
been confirmed in game on Android (Zalith) with Solstice RT.

## What is adapted

Sodium replaces Minecraft's terrain renderer with its own packed vertex format and
shaders. GreenMarine observes Sodium's terrain pipelines when they are compiled,
recognises the exact shader pair and ABI (20-byte region push constants,
`USE_VERTEX_COMPRESSION`, its globals/atlas/section-time bindings, including OIT
pipelines that use `minecraft:` locations), and substitutes the built-in pack
programs:

- Solstice RT terrain and its material capture,
- Seabreeze terrain,
- Marine Raster terrain.

Sodium's own runtime `chunk_vertex.glsl` decodes the geometry; a small source bridge
maps the decoded data, atlas coordinates, light, camera-relative region offsets and
visibility onto the pack terrain interface. Vertex bindings, uniforms, topology,
defines and push constants are preserved. There is no CPU vertex conversion, mesh
rebuild or readback. The material capture still redraws terrain once.

## Custom packs

Only the shipped terrain programs are adapted. A custom pack that replaces the
terrain shader renders terrain through Sodium's normal shaders while Sodium is
installed; its sky, clouds and scene passes still apply. Reuse the built-in
terrain/surface program identifiers to keep the adapted path. Unknown terrain
pipelines in material capture fail with their identifier instead of silently
reporting support.

## Performance

Sodium lowers CPU chunk submission cost, which helps when the frame is CPU-bound.
Ray tracing, reflections and post-processing remain GPU work, so the gain shrinks
when the GPU is the limit. Compare at the same world, pose, render distance,
resolution, pack, quality, ray mode and FPS cap, after chunks and shaders warm up.

## Developer test

The shader test links the adapted programs against Sodium's real include files
(classic, cutout and all OIT variants, Vulkan and OpenGL):

```sh
GREENMARINE_TEST_SODIUM_RESOURCES=/path/to/sodium/common/src/main/resources ./gradlew test
```

Without that directory only this optional test is skipped; Sodium is never
downloaded or required. Source:
[Sodium mc26.3-0.9.3-alpha.1](https://github.com/CaffeineMC/sodium/tree/mc26.3-0.9.3-alpha.1).
