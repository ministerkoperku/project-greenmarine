# GreenMarine user guide

## Install

- Minecraft **26.3**, Fabric Loader **0.19.5+**, Java **25**.
- Put one GreenMarine jar in `mods/`. Fabric API, KoperLib, Sodium and KAvatar are
  optional; GreenMarine works with and without them.
- JVM argument `--enable-native-access=ALL-UNNAMED` enables the bundled native
  engine (hardware RT, native animation). Without it everything else still works.
- Game argument `--graphicsBackend vulkan` selects Vulkan, the primary backend and
  the only one with hardware ray tracing. OpenGL runs the same raster passes.
  Minecraft chooses its device before mods load; GreenMarine never switches it.

The bundled natives cover Linux x86_64, Linux ARM64 and Android ARM64. Android
specifics: [Android / Zalith](ANDROID-ZALITH.md).

## Controls

| Key | Action |
|---|---|
| F6 | Settings: pack, quality, ray mode, profiling, pack parameters |
| Pause menu → GreenMarine | Same settings, touch friendly |
| F7 | Place the demonstration point light on the targeted surface |
| F8 | Toggle GreenMarine world effects (HUD and hand stay vanilla) |
| F3+T | Reload resources, packs and configuration |

The settings screen is paged: pack list, quality and ray mode, then parameter
sliders with live preview, saved per pack, and a Reset button. With profiling on it
also shows CPU and GPU timings per stage. See [settings UI](SETTINGS-UI.md).

## Packs

| Pack | What it does | Needs |
|---|---|---|
| **Marine Raster** (default) | Terrain lighting, animated refractive water, sky and clouds; no ray work | any backend |
| **Seabreeze** | Stylised terrain/water shading, waves, sky and clouds | any backend |
| **Solstice RT** | Full pipeline: materials, water, weather, procedural sky, bloom, temporal filtering; RT shadows, GI and reflections on Vulkan | Vulkan + hardware ray queries for RT |

Details: [Solstice RT](SOLSTICE-RT.md). More packs can be dropped into
`shaderpacks/greenmarine/` as folders or ZIPs ([external packs](EXTERNAL-PACKS.md)).
These are GreenMarine packs; Iris and OptiFine packs do not load.

Status appears in F6 and chat as Loading, Loaded, Fallback or Failed with a reason.
*Loaded* means the pack's programs actually submitted with ready data this frame;
*Fallback* means a declared fallback is in use (for example rays unavailable).

### Quality and ray modes

Quality (Low, Medium, High, Ultra) scales pass resolution, filter taps, bloom and
cloud detail per pack.

| Ray mode | Behaviour |
|---|---|
| Off | No ray work |
| Auto | Off below High; at High/Ultra hardware when ready, otherwise voxel |
| Voxel | Shader rays through a local 32³ block grid; any backend |
| Hardware | Native Vulkan ray queries ([hardware RT](HARDWARE-RT.md)) |

## Configuration files

`config/greenmarine-shaders.json` holds the selected pack and render policy:

```json
{
  "pack": "greenmarine:raster",
  "quality": "medium",
  "rays": "off",
  "profiling": false
}
```

`greenmarine:none` disables packs and keeps only point lighting. Parameter overrides
are stored per pack in the same file.

`config/greenmarine.json` controls the point-light demo and optional bloom:

```json
{
  "enabled": true,
  "color": "#33ff99",
  "intensity": 1.5,
  "radius": 4.0,
  "bloomThreshold": 0.65,
  "bloomStrength": 0.0,
  "bloomRadius": 2.0,
  "pulseSpeed": 1.0,
  "automaticDemoLight": false
}
```

Ranges: intensity 0..16, radius 0.1..64 blocks, bloom strength 0..4, bloom radius
0.1..16 px, pulse speed 0..16. `effect` may name a resource-pack effect (below).
Invalid files keep the last valid settings.

## Lights and effects

Mods create lights through `GreenMarine.LIGHTS` (world position, colour, radius,
intensity). Each frame up to 16 visible lights are selected by distance, radius and
intensity; the first four get voxel shadows. Changing worlds clears them.

Resource packs can add `assets/<namespace>/greenmarine/effects/*.json`:

```json
{"id": "demo:aurora", "color": "#33ff99", "radius": 4.0, "intensity": 1.5}
```

Select it with `"effect": "demo:aurora"` in `greenmarine.json`.

## Compatibility

- **Sodium 0.9.3**: built-in terrain programs and hardware RT work with it
  ([Sodium](SODIUM.md)).
- **KAvatar**: custom avatars and accessories appear in reflections and cast
  shadows ([player in reflections](ACTOR-REFLECTIONS.md)).
- **KoperLib Fullpack**: an optional build variant registers a
  `greenmarine_effect` content type ([development](DEVELOPMENT.md)).
- **Renderers with their own geometry** (Kender, instancing mods) can join RT
  through [external renderers](EXTERNAL-RENDERERS.md).
- Other shader mods (Iris, OptiFine) are not compatible; use one at a time.

## Limits

- RT covers a finite region of loaded blocks around the player plus the player
  body and registered external meshes; entities and fluids are not ray targets.
- Entities, block entities, particles and rain keep Minecraft's shaders.
- No upscaling (FSR), path tracing or caustics. [LabPBR block maps](LABPBR.md) are supported.
- First use of a pack compiles pipelines and can hitch briefly.

Mod developers: [rendering API](RENDERING-API.md), [shaderpacks](SHADERPACKS.md),
[world programs](WORLD-PROGRAMS.md), [surface rendering](SURFACE-RENDERING.md),
[external renderers](EXTERNAL-RENDERERS.md), [roadmap](API-ROADMAP.md).

Resource-pack material support: [LabPBR](LABPBR.md).
