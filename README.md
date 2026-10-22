# GreenMarine 0.9

0.9.0 adds indexed/instanced mesh APIs, opt-in transient graph memory reuse,
cached world bindings and a touch-friendly paginated settings/performance UI.
Profiling toggles preserve the renderer's live resources. [Rendering API](docs/RENDERING-API.md).


0.8.9 gives Solstice Medium a smaller ray/filter budget and removes unused
reflection and noise work. [Quality budgets and limits](docs/SOLSTICE-RT.md).

0.8.8 requests consistent world depth for post effects, preventing always-on-top
gizmo overlays from clearing their depth input. [Depth contract](docs/WORLD-PROGRAMS.md).

0.8.7 adds porous sand/gravel materials, continuous filtered clouds, output dither,
rough-reflection reconstruction and opt-in automatic diagnostic lights.
[Visual correction scope and evidence](docs/SOLSTICE-RT.md).

0.8.6 draws Solstice's procedural sky/clouds once, before transparency, avoiding
overlap with host cloud geometry and brightness-dependent sky refill.

0.8.5 preserves metal reflection color and validates reduced ray samples against
their producing material surfaces. [Reflection scope](docs/SOLSTICE-RT.md).

0.8.4 repairs Android native delivery, limits cave sky reflections, differentiates
glass/ice/metal materials and adds opt-in local voxel sun shadows to Solstice's
fallback. [Scope and controls](docs/SOLSTICE-RT.md).

Marine Raster adds a ray-free default for new settings, external folder/ZIP loading
and a pack authoring CLI. [External packs and SDK](docs/EXTERNAL-PACKS.md).

Standalone Minecraft 26.3/Fabric shaderpack and effects platform prototype:
public GPU resources, shader programs, render graphs and contributed mesh materials,
plus Lua shaderpacks, GLSL/optional SPIR-V, lightmaps, voxel rays and Vulkan hardware
world ray queries. Aurora RT and a resource-pack ZIP demonstrate textured BLAS/TLAS
shadows and diffuse rays, with explicit loading/fallback status. Java 25;
Rust native processing has a Java fallback. Installation, controls and authoring: [docs/README.md](docs/README.md).
[Rendering API guide](docs/RENDERING-API.md). Current evidence and remaining work: [development/PROGRESS.md](development/PROGRESS.md).
The original owner concept is preserved in `readme.txt`.

0.8.1 adds distinct Android/Bionic and Linux ARM64 native bundles plus a
touch-accessible pause-menu settings entry. [Android / Zalith guide](docs/ANDROID-ZALITH.md),
[API roadmap and comparison](docs/API-ROADMAP.md). Android gameplay acceptance
remains separate from native/build verification.

0.8.2 adds a targeted Sodium terrain adapter for built-in Solstice/Seabreeze,
preserving packed geometry and OIT while fixing the material replay pipeline
mismatch. [Compatibility scope, testing and FPS expectations](docs/SODIUM.md).

Seabreeze adds actual terrain/water/sky/cloud shaders; a separate corrected Prismarine
pack fixes additive-irradiance whiteness. [World-program API and examples](docs/WORLD-PROGRAMS.md).

Solstice RT adds raster material capture, actual offscreen Vulkan reflections,
shared-scene sun/GI, environment/weather inputs and opt-in temporal history.
[Solstice pack](docs/SOLSTICE-RT.md), [surface API](docs/SURFACE-RENDERING.md).

0.8 adds named retained scene-pass inputs, covered scene publication, exact-size
worker assembly and material-checked lighting transitions. Solstice caches its
linear scene once for bloom/composition. [Changes and measurements](development/evidence/performance-08/README.md).
