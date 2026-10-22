# Raster shaders and external authoring

Marine Raster changes terrain lighting, animated/refractive water, sky and clouds
using raster programs. It requests no ray scene and its terrain fragment shader
has no ray-output reads. Sun lighting is directional and unoccluded: this pack does
not add raster shadow maps, PBR maps or screen-space reflections. New installations
select this pack with Rays Off; existing saved pack and ray choices are preserved.
The builtin terrain adapter also recognizes this program on its supported Sodium
ABI, but rendered device acceptance is separate from compilation and routing tests.

## Load an external pack

Place an editable directory or ZIP in `<game directory>/shaderpacks/greenmarine/`:

```text
shaderpacks/greenmarine/ocean/
  assets/demo/greenmarine/packs/ocean.json
  assets/demo/shaders/core/terrain.vsh
  assets/demo/shaders/core/terrain.fsh
```

Reload resources with F3+T, then select the pack in GreenMarine settings. Each
directory/ZIP has private assets; shared includes fall back to mounted Minecraft
and mod resources. Different packs may reuse resource names. Pack IDs must be
unique, including builtins; do not install both the directory and ZIP of one pack.
Only immediate child folders and ZIPs are scanned. ZIPs have `assets/` at the root,
without an extra wrapper folder. No `pack.mcmeta` is required by this loader.
Existing resource-pack discovery and Java registrations remain supported.

Catalog publication is atomic: malformed manifests, missing includes, duplicate
IDs or invalid scripts retain the last valid catalog. Errors identify the pack
origin in the log. A GPU compiler error still disables the selected rendering
until switch/reload; filesystem validation is not shader compilation.

Snapshots are read only on resource reload, with 64 external containers/manifests,
2048 entries per container, 8 MiB assets per container and 32 MiB assets per scan.
Individual text assets are at most 256 KiB, manifests/Lua 64 KiB, SPIR-V 4 MiB.
Unsafe ZIP paths, duplicate entries and filesystem symlinks are rejected.

## Create and export

From this repository, using Python 3.9+ and its standard library:

```bash
python3 tools/greenmarine-pack.py init /tmp/ocean --id demo:ocean
python3 tools/greenmarine-pack.py validate /tmp/ocean
python3 tools/greenmarine-pack.py package /tmp/ocean /tmp/ocean.zip
```

The starter has its own manifest, terrain vertex/fragment, sky and cloud shaders.
Edit these independently of the mod. Its common include ABI comes from GreenMarine;
game includes come from Minecraft. Parameter order defines shader slots: retain
the two fixed reserved slots when editing this starter. The CLI checks local
references and size limits; the runtime catalog validates schema, script and include
contracts. GPU compilation and visual acceptance still require the intended backend.
Custom shaders are not automatically recognized by the targeted Sodium adapter;
keep the builtin program IDs for that tested adapter contract, or validate a custom
renderer integration separately.

`package` creates a deterministic ZIP and refuses an existing output or an output
inside the source directory. `init` refuses an existing destination. The Gradle
`rasterShaderpack` task exports `build/shaderpacks/marine-raster-<version>.zip` with
the distinct ID `greenmarine:raster_example`, allowing it to coexist with the builtin.

## Java source API

```java
PackSource source = new PackSource("my-pack.zip", manifestJson,
    resourceId -> readPrivateText(resourceId), resourceId -> readPrivateBytes(resourceId));
boolean published = catalog.reload(java.util.List.of(source));
```

`reload(List<PackSource>)` atomically replaces the catalog's resource-loaded packs,
leaving Java registrations intact. Use a private `PackCatalog` for tooling; mods
contributing to the shared catalog should keep using owned `register(LoadedPack)`
leases. `ExternalPacks.scan(path, textFallback, binaryFallback)` supplies snapshot
sources for tools and hosts; it makes no GPU calls and never creates directories.
Source callbacks need no render-thread access. Repeated text resource references
are read once per source/reload, and errors are available from `catalog.error()`.

This is the native GreenMarine schema. Broader world-stage coverage, shadow maps,
material textures, compute/storage and other pack-format translation remain work
requiring host support and independent runtime tests.
