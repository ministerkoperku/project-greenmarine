# Material addons (API 1)

Mods and resource packs can provide editable PNG masks and JSON material definitions
without depending on Fabric API. GreenMarine discovers
`assets/<namespace>/greenmarine/materials/**/*.json` in mod roots and mounted
resources. Mounted resource-pack JSON at the same resource ID replaces mod JSON.
Shaderpacks can additionally declare `materialAddons` JSON IDs in their manifest;
those definitions and PNGs use that pack's private folder/ZIP assets and only
apply while that shaderpack is selected.

Example `assets/example/greenmarine/materials/robot.json`:

```json
{
  "schema": 1,
  "id": "example:robot",
  "shaderpacks": ["greenmarine:solstice_rt"],
  "textures": {
    "example:textures/robot.png": {
      "default": {"surface":"metal","roughness":0.23,"reflection":0.6,"emission":0},
      "mask": "example:greenmarine/masks/robot.png",
      "palette": {
        "123456": {"surface":"coated","roughness":0.8,"reflection":0.05,"emission":0.12},
        "abcdef": {"surface":"glass","roughness":0.06,"reflection":0.25,"emission":0}
      }
    }
  }
}
```

Omit `shaderpacks` for a generic addon. `default` and `mask` are optional, but at
least one is required. An opaque PNG pixel selects a matching RGB palette color;
transparent, partly transparent and unmatched pixels use that layer's default.
Without a default they retain earlier material layers or the renderer fallback.
Masks nearest-scale to the requested texture size, independent of albedo colors.
This lets a blue screen with a white centre stay matte/emissive while other blue
panels remain glass. Edit the PNG palette regions and JSON numeric properties,
then reload resources. A game editor is not required by this format.

Each material requires `surface`, `roughness`, `reflection` and `emission`.
The numeric properties are finite 0–1. Surface names/classes are:

| Class | Names |
| ---: | --- |
| 1 | opaque, matte, stone, wood |
| 2 | metal |
| 3 | puddle |
| 4 | water |
| 5 | glass |
| 6 | ice |
| 7 | porous |
| 8 | gem, diamond |
| 9 | coated, screen, polished |

An integer class 1–9 is also accepted. Screen emission is a raster appearance
term; it does not create a new native GI light. Reflection scales addon response,
including metal response, independently of class selection.

Generic layers apply first, then pack-specific layers. Within each tier source
order is deterministic: mod roots by mod ID/path, mounted JSON by resource ID,
then private shaderpack declarations in manifest order. Resource replacement at
the same JSON ID happens before layering. Invalid addons are reported and omitted
individually; valid addons publish together with a new generation. Removed sources
are removed on reload.

## Consumer API

`com.koper.greenmarine.api.material.MaterialAddons` is CPU-only:

```java
public static final int API_VERSION = 1;
public static Optional<Compiled> resolve(String textureId, String shaderPackId, int width, int height);
public static String activePackId();
public static long generation();
public record Compiled(int width, int height, ByteBuffer pixels, long revision) {}
```

Texture aliases `example:robot`, `example:robot.png` and
`example:textures/robot.png` resolve identically. Block atlas sprites use
`example:block/robot` → `example:textures/block/robot.png`.
Pixels are a direct, read-only RGBA8 buffer: roughness, reflection, emission,
surface class. Class byte zero means keep the renderer's existing fallback for
that pixel. Each accessor returns an independent buffer position. `revision` is
the catalog generation at compilation. Cache GPU uploads by texture/size,
`activePackId()` and `generation()`; retire GPU resources using the host renderer's
normal fences. This API neither owns nor destroys GPU resources.

`Source`, `snapshot(Source)`, `reload(List<Source>)`, and `setActivePackId` support
catalog/lifecycle owners; integrations normally only query the four members above.
`reload` replaces the complete catalog and returns isolated source diagnostics.
Private-pack snapshots copy PNG bytes before publishing.

Bounds: 128 sources, 512 textures/source, 256 palette colors/texture; masks and
resolved textures 1–2048 pixels per dimension; mask PNG at most 4 MiB, JSON at most
256 KiB, decoded catalog masks at most 64 MiB. Private shaderpack sources retain
the existing stricter 64 KiB JSON / 256 KiB individual binary bounds. Compiled CPU
maps use an LRU capped at 32 MiB. Unsupported resolution throws
`IllegalArgumentException`; optional consumers should retain their fallback.

## Solstice terrain

Readable explicit LabPBR `_s` maps win. Otherwise nonzero addon pixels override
builtin profiles and inferred materials; holes retain those fallbacks. `_n` maps
remain independent. The existing atlas presence mask uses bit 3 (value 8) for addon
pixels, keeping authored LabPBR bits 0/1 and inference bit 2 distinct. Capture and
shading share the same decode, so class/roughness agree. Actual water retains its
existing path. No frame/native ABI changed.
