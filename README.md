# GreenMarine 0.8

Standalone Minecraft 26.3/Fabric shaderpack and effects platform prototype:
public GPU resources, shader programs, render graphs and contributed mesh materials,
plus Lua shaderpacks, GLSL/optional SPIR-V, lightmaps, voxel rays and Vulkan hardware
world ray queries. Aurora RT and a resource-pack ZIP demonstrate textured BLAS/TLAS
shadows and diffuse rays, with explicit loading/fallback status. Java 25;
Rust native processing has a Java fallback. Installation, controls and authoring: [docs/README.md](docs/README.md).
[Rendering API guide](docs/RENDERING-API.md). Current evidence and remaining work: [development/PROGRESS.md](development/PROGRESS.md).
The original owner concept is preserved in `readme.txt`.

Seabreeze adds actual terrain/water/sky/cloud shaders; a separate corrected Prismarine
pack fixes additive-irradiance whiteness. [World-program API and examples](docs/WORLD-PROGRAMS.md).

Solstice RT adds raster material capture, actual offscreen Vulkan reflections,
shared-scene sun/GI, environment/weather inputs and opt-in temporal history.
[Solstice pack](docs/SOLSTICE-RT.md), [surface API](docs/SURFACE-RENDERING.md).

0.8 adds named retained scene-pass inputs, covered scene publication, exact-size
worker assembly and material-checked lighting transitions. Solstice caches its
linear scene once for bloom/composition. [Changes and measurements](development/evidence/performance-08/README.md).
