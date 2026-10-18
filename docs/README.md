# GreenMarine 0.8 prototype

GreenMarine is a standalone shaderpack and mod-effects platform for Minecraft
26.3. Vulkan is the primary backend; OpenGL uses the same Renderpearl passes.
Packs combine JSON declarations, an extended Lua API and GLSL, with optional
precompiled fragment SPIR-V on Vulkan. The built-in Portable and Advanced packs
include a real lightmap stage. Point lights use world positions and radius;
optional voxel rays give opaque full-block shadows without hardware RT.
The new Radiance pack traces world voxel materials to receive diffuse light
from emissive blocks, including sources outside the visible image.

Solstice RT adds actual terrain/water shaders, independent material buffers,
hardware offscreen reflections and diffuse rays, atmosphere/weather, wet ground,
bloom and bounded temporal filtering. Select **Solstice RT** in F6, with Vulkan
and Hardware/Auto for native rays. OpenGL keeps the raster water/material path
and reports Fallback. Read [Solstice RT](SOLSTICE-RT.md) and the additive
[surface rendering API](SURFACE-RENDERING.md) for controls and current limits.
Geometry updates and measured shadow freshness are covered in
[dynamic ray scenes](DYNAMIC-RAY-SCENES.md).

## Installation

Sodium 0.9.3-alpha.1 terrain adaptation for built-in Solstice/Seabreeze is
available in 0.8.2; see [Sodium compatibility and FPS expectations](SODIUM.md)
for scope and the phone acceptance procedure.

Use Java **25**, Fabric Loader **0.19.5+** and Minecraft **26.3**. Install
`greenmarine-0.8.2-prototype.jar`. Fabric API, KoperLib and Sodium are optional.
The Fullpack variant replaces the base jar; install one GreenMarine jar at a
time. Fullpack's own dependencies apply if you install Fullpack.

Add `--graphicsBackend vulkan` to **game** arguments to select Vulkan.
Development `./gradlew runClient` selects Vulkan by default; use
`-PgraphicsBackend=opengl` for the secondary backend. Minecraft selects the
host graphics device before a mod can use it; GreenMarine does not switch an
already created device.

Native builds select Linux x86_64, Linux ARM64 or Android ARM64 explicitly;
the Android export includes separate Bionic and Linux ARM64 libraries.
See [Android / Zalith installation](ANDROID-ZALITH.md) for build options,
touch controls and the remaining device acceptance checks. Add
`--enable-native-access=ALL-UNNAMED` to JVM arguments to use it. If it is absent,
unsupported or disabled, animation falls back to Java and portable rendering
continues. This removes a native-library requirement, but Windows/macOS and
other GPUs still need runtime validation. Java uses Renderpearl for drawing;
Rust evaluates animation and records native BLAS/TLAS builds and ray queries
through ash into the live host Vulkan command encoder. Minecraft retains device,
queue, atlas and submission ownership. See [hardware RT](HARDWARE-RT.md).

## Controls and settings

- **F6:** select pack, quality, ray-lighting mode and profiling; **Pack parameters**
  opens paged sliders with live preview and per-pack persistence.
- **Pause menu → GreenMarine:** open the same settings with a clickable/touch entry.
- **F7:** place/reposition the demonstration point light at a targeted surface
  within six blocks, or three blocks ahead. It stays fixed when you move.
- **F8:** toggle GreenMarine world effects; hand/HUD keep ordinary rendering.
- **F3+T:** reload resources/configuration. Reload restores configured enabled state.

`config/greenmarine-shaders.json` selects the pack and render policy:

```json
{
  "pack": "greenmarine:portable",
  "quality": "medium",
  "rays": "auto",
  "profiling": false
}
```

Use `greenmarine:none` to run only point lighting. Portable changes lightmap
color/ambient light and exposure/saturation. Advanced adds half-resolution
bloom and full-resolution depth occlusion/compositing at High/Ultra quality;
its final color keeps full-resolution scene detail. Low/Medium skip those
expensive stages. Radiance uses High/Ultra + Voxel for world emitter lighting
(4/8 hemisphere rays, 12-block maximum), half-resolution RGBA16F and a depth-
guided full-resolution composite. Off or Low/Medium returns the ordinary scene.
Its gain slider controls emission strength; explicit overrides win over Lua.
Reset pack restores authored parameters. Parameters remain editable with world
effects disabled; storage-limit errors are shown without evicting other packs.
Profiling adds CPU work and is off by default.

Ray modes are **off**, **auto**, **voxel**, **hardware**. Auto stays off below
High, then chooses an available path. Voxel uses shader DDA through a local
32³ occupancy grid on both backends. Hardware uses native Vulkan Ray Query
with real loaded block-model BLAS/TLAS when the host enables every required
feature and the pipeline initializes. Unsupported systems select the explicit
fallback. Aurora RT provides sun shadows/diffuse rays and an installable example
ZIP. F6 and chat report Loading/Loaded/Fallback/Failed; Loaded means the selected
programs submitted with ready world data, not GPU support alone.

Point lighting and independent optional bloom use `config/greenmarine.json`:

```json
{
  "enabled": true,
  "color": "#33ff99",
  "intensity": 1.5,
  "radius": 4.0,
  "bloomThreshold": 0.65,
  "bloomStrength": 0.0,
  "bloomRadius": 2.0,
  "pulseSpeed": 1.0
}
```

Intensity is 0..16, radius 0.1..64 blocks, bloom strength 0..4, bloom radius
0.1..16 pixels and pulse speed 0..16. Existing configurations are preserved;
invalid files retain the last valid settings. Intensity zero removes the
built-in lamp's illumination; mod/script lights remain independent.

## Authoring and mod integration

See [Shaderpacks and API](SHADERPACKS.md) for the manifest, shader ABI, Lua
functions, SPIR-V workflow and Java registration. The built-in
packs are under `src/main/resources/assets/greenmarine/greenmarine/packs/`.
A mounted resource pack or explicit Java registration can contribute a pack
without depending on Fullpack. Asset-only mods need a resource mounting
mechanism; Loader alone does not mount other mods' assets.

`GreenMarine.LIGHTS` creates/updates/removes immutable `Light` values with world
positions. Each frame conservatively rejects light spheres outside the side
planes of the camera frustum, then selects up to 16 by distance, radius and
intensity. Zero/black/unrepresentable sources are skipped. Camera-outside-radius
sources can still illuminate visible surfaces. Only the first four selected
sources receive voxel shadows; the remainder use unshadowed lighting. Changing
worlds clears handles. Shader scripts own and clean up their own lights.

Resource packs can also provide `assets/<namespace>/greenmarine/effects/*.json`:

```json
{"id":"demo:aurora","color":"#33ff99","radius":4.0,"intensity":1.5}
```

Add `"effect":"demo:aurora"` to `greenmarine.json` to select its color, radius
and intensity. Missing ids fall back to configuration; `greenmarine:aurora`
is built in. Effect declarations validate intensity 0..16. Light rendering
clamps the broader Java light contract to that range.

## Optional Fullpack declarations

```sh
./gradlew -PfullpackIntegration=true test build
```

The variant uses compile-only sibling Core/Fullpack **0.1.1-alpha** jars under
`../koperlib/build/libs/`; override `-PkoperlibVersion=...` when needed. It
registers the dedicated `greenmarine_effect` content type and keeps the last
valid declarations after failed scans. Matching Fullpack definitions take
precedence over resource definitions. It preserves KFX's existing provider.

Copy `examples/fullpack/greenmarine_demo/` into `koperlib/fullpacks/`, select
`demo:aurora` and restart with Fullpack and the integration jar. Fullpack's own
reload refreshes those declarations. Fullpack scripting and KFX forwarding
are not wired to the new pack Lua runtime yet; packs use GreenMarine's API.

## Optimization and current limits

Light snapshots, pipelines, schedules and render targets are cached. Small
passes use reusable targets; scene stages ping-pong and copy/composite at the
end. Disabled bloom skips its nine reads, out-of-radius pixels skip light
work, and empty point-light frames allocate/submit no pass. Voxel acquisition
reads loaded client chunks only, at most 2048 blocks with a 750 µs scan target
per tick (checked every 64 reads). Only complete snapshots are published and
unchanged occupancy is not uploaded again. Animation scripts run once per
unpaused game tick, with instruction/memory budgets; shader arithmetic stays
on the GPU. Owned GLSL programs request Shaderc's performance optimization;
vanilla programs keep Minecraft's options. The supplied SPIR-V is optimized
offline without stripping binding names. Profiling uses nonblocking timestamp
queries and excludes warmup. Pipelines compile synchronously on first use;
pack changes/reloads can briefly hitch, while later frames reuse the result.

Portable point lighting uses final scene color and depth-derived normals. It has
no material normal maps/G-buffer or entity/partial-block voxel occlusion. Aurora
RT separately exports textured model albedo, normals/depth and irradiance,
including partial block geometry, sun visibility and four diffuse secondary rays.
See [hardware RT limits and verification](HARDWARE-RT.md). Voxel shadows cover the local
32³ grid and update incrementally; rapid movement or block changes can show
stale/unshadowed lighting until a scan completes. Beyond the grid, lighting is
unshadowed. Transparency, particles and fog have approximate depth behavior.
The Advanced depth effect is a small screen-space approximation. Radiance
uses block map-color albedo/emission, not texture-derived materials or measured
spectra. Its first-hit hemisphere lighting has fixed low sample counts; no
multi-bounce GI or temporal denoising is implemented. Computed lighting adds
to vanilla illumination; vanilla block-light energy is not separated, so this
is not an energy-conserving replacement for vanilla lighting. Non-solid emitters are
full-cell approximations. The underlying scene remains vanilla rasterization.
RGBA16F intermediates preserve new lighting above 1; they do not recover HDR
already clamped by vanilla. Unsupported float target allocation falls back to
the scene format and can lose HDR range. This is a
platform prototype, not a full path-traced/native renderer or an FPS guarantee.

FSR 3/4, OptiFine-format packs, networking, native renderer
replacement and full Sodium/KFX coexistence remain separate work. FSR4 is
explicitly deferred by the owner. Compatibility with other shader mods is
not claimed. Only one selected GreenMarine pack composes scene passes.

## Development and verification

```sh
./gradlew test build
cd engine
cargo test
cargo build --release
```

Test-only probes are excluded from distributable jars. On the checked desktop,
use `SDL_VIDEODRIVER=wayland` before Gradle commands:

```sh
./gradlew -PruntimeProbe=true -PprobeOutput=probe-output/portable runClient
python3 tools/verify-probe.py run/probe-output/portable
./gradlew -PruntimeProbe=true -PvoxelProbe=true -PprobeOutput=probe-output/wall runClient
python3 tools/verify-voxel-probe.py run/probe-output/wall
```

The wall fixture needs the demonstration intensity set to zero in this
project's development config. It changes client-only test blocks, teleports
through its integrated server, temporarily uses spectator mode, then restores
that mode and shader selection. It does not edit installed instances. The
ordinary probe blanks movement input and disables focus-pausing only in its
own development client. Screenshot checks require ImageMagick; it is not a
mod dependency. Results, images and measured scope are documented in
[development/PROGRESS.md](../development/PROGRESS.md).

World-material/HDR/control probe (set this project's demo intensity/bloom to
zero for the fixture):

```sh
./gradlew -PruntimeProbe=true -PworldLightingProbe=true -PprobeOutput=probe-output/world runClient
python3 tools/verify-world-probe.py run/probe-output/world
```

The controlled probe uses gain 4 (normal default 2), client-only emitter/ceiling
blocks, a fixed camera without client-side mobs, resize/resource reload, an integrated-
server spectator teleport, independent On/Off pairs for both geometries, an
HDR arithmetic fixture and real parameter controls. It restores its original
shader settings and game mode; original settings are also saved beside the
captures. `-PworldRestoreMode=survival` is an explicit dev-world repair override.
If asset download stalls on optional translation files while all rendering
assets are cached, the local test may use `-x downloadAssets`; this is not a
change to the shipped mod or Minecraft dependencies.

Mod developers can use the [public rendering API](RENDERING-API.md) for owned
GPU resources, shader programs, render graphs and contributed mesh materials.
It is client-only, works independently of pack selection, and keeps the base
standalone. See the guide for lifecycle, backend support and current limits.

## World materials (0.6)

Select **Seabreeze World Shaders** in F6 for actual terrain and water programs,
including vertex waves, opaque-depth refraction/absorption, sky and clouds.
Vulkan adds native world rays; OpenGL/Rays Off retains raster material shading.
A separate Prismarine Showcase Corrected ZIP addresses the original white local
RT patch without altering its owner archive. [World program authoring and limits](WORLD-PROGRAMS.md).
