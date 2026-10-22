# GreenMarine

A shader and rendering platform mod for Minecraft **26.3** (Fabric), built on
Minecraft's own Vulkan renderer. It ships its own shaderpacks, including
**Solstice RT** with real hardware ray-traced shadows, reflections and global
illumination, and gives mod developers a public API for GPU resources, render
graphs, world shaders and ray-traced geometry.

> Status: prototype (0.9.x). Tested on desktop Vulkan (AMD RX 9070 XT) and on
> Android through the Zalith launcher. Expect rough edges.

## Features

- **Three built-in packs**: Marine Raster (lightweight default), Seabreeze, and
  Solstice RT (materials, refractive water, weather, procedural sky, bloom,
  temporal filtering).
- **Hardware ray tracing** on Vulkan ray queries: sun shadows (including the
  player's, in first person too), diffuse GI and reflections that see off-screen
  geometry, recorded into Minecraft's own command stream.
- **Voxel rays** on any backend as a fallback.
- **Sodium** compatible, including RT. **KAvatar** avatars and accessories show up
  in reflections and shadows.
- **Pack authoring**: JSON + Lua + GLSL (optional SPIR-V), world shader programs,
  external folder/ZIP packs and a pack CLI.
- **Mod API**: owned GPU buffers/textures/programs, validated render graphs,
  indexed/instanced meshes, point lights, and an
  [external renderer API](docs/EXTERNAL-RENDERERS.md) for mods that draw their own
  geometry (moving block grids, instanced models) to cast shadows and be
  ray traced.
- Android ARM64 support with touch settings.

## Install

Minecraft 26.3, Fabric Loader 0.19.5+, Java 25. Drop the jar into `mods/`; Fabric
API is not required. For Vulkan and the native engine add:

- game argument `--graphicsBackend vulkan`
- JVM argument `--enable-native-access=ALL-UNNAMED`

Press **F6** (or Pause → GreenMarine) and pick a pack. Full guide:
[docs/README.md](docs/README.md).

## Documentation

| Topic | |
|---|---|
| Using the mod | [User guide](docs/README.md), [Solstice RT](docs/SOLSTICE-RT.md), [Android](docs/ANDROID-ZALITH.md), [Sodium](docs/SODIUM.md) |
| Ray tracing | [Hardware RT](docs/HARDWARE-RT.md), [Player in reflections](docs/ACTOR-REFLECTIONS.md), [Dynamic scenes](docs/DYNAMIC-RAY-SCENES.md) |
| Writing packs | [Shaderpacks](docs/SHADERPACKS.md), [External packs](docs/EXTERNAL-PACKS.md), [World programs](docs/WORLD-PROGRAMS.md), [Surface rendering](docs/SURFACE-RENDERING.md), [Materials](docs/MATERIAL-EXTENSION.md) |
| Mod API | [Rendering API](docs/RENDERING-API.md), [Indexed meshes](docs/INDEXED-MESHES.md), [Graph allocation](docs/GRAPH-ALLOCATION.md), [External renderers](docs/EXTERNAL-RENDERERS.md), [Settings UI](docs/SETTINGS-UI.md) |
| Project | [Development](docs/DEVELOPMENT.md), [Roadmap](docs/API-ROADMAP.md), [Changelog](CHANGELOG.md), [Third-party notices](docs/THIRD-PARTY.md) |

## Building

```sh
./gradlew test build
cd engine && cargo test && cargo build --release
```

Needs Java 25, Rust and `glslangValidator`. Android builds and GPU tests:
[development](docs/DEVELOPMENT.md).

## License

No license has been chosen yet; until one is, all rights are reserved by the author.
Bundled third-party components keep their own licenses
([third-party notices](docs/THIRD-PARTY.md)). `readme.txt` is the original project
concept, kept as written.
