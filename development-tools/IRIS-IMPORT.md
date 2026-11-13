# Iris source import

`IrisImporter.convert(Path source, Path destination)` accepts an Iris shaderpack
ZIP or a directory containing `shaders/`. The destination must be a new project
directory chosen inside the editable GreenMarine workspace. It returns
`Report(boolean runnable, int sourceFiles, List<String> diagnostics, Path destination)`
and throws `IOException` for invalid input, exceeded limits, or failed I/O.

The importer snapshots the entire source pack, including license files, under
`iris-source/`. It never edits the original. A report is written to
`IMPORT-REPORT.md`. Existing destinations are rejected. Files are staged beside
the destination and moved into place atomically; rejected input leaves no partial
project. Source and destination ancestor symlinks, symlink entries, unsafe ZIP
paths, ZIP64, encrypted/multipart ZIPs, more than 8192 files, and more than 256 MiB
uncompressed are rejected. ZIP input also has a 256 MiB compressed limit.
Generated conversion assets have a separate 512 MiB limit. Exceeding that limit
aborts atomic publication. The original conservative fullscreen include expansion
keeps its 256 KiB editable limit.

Every import also produces a whole-program editable conversion under
`assets/iris_imported/shaders/iris/` and a typed `conversion/pipeline.json` document.
All mapped dimension variants are inventoried, including custom dimension folders.
Enabled vertex/fragment/geometry/tessellation programs are linked as one ABI before
strict Vulkan compilation. Compute siblings are separate linked groups, ordered
before the associated graphics group. Successful stages include both editable GLSL
and `.spv`; failed compilation preserves GLSL and diagnostics. A program which
cannot link may retain an explicitly unlinked GLSL candidate without SPIR-V.
Disabled programs remain in metadata and their original source remains editable.

The document includes program enable expressions, whole-program geometry fallbacks,
uniform/opaque/resource-block bindings, varying locations, attachment mappings,
stage constants (including Iris block-comment format directives), workgroup settings,
1D/2D/3D image descriptors, SSBOs, texture mappings, options and raw/unhandled settings.
An absent render-target directive is distinguished from an explicit mapping; regular
fragment programs record Iris's default attachments 0–7. Geometry draw order remains
owned by the host. Dimension overrides replace complete program sets and do not
borrow individual stages from the root folder.

Preprocessing, program ABI linking and stage compilation have separate counters.
Optional integration failures remain visible. `coverage.runtimeReady` remains false;
compilation is separate from in-game acceptance. Every world receives a native
candidate under `conversion/native/`, with explicit unmet execution requirements
and `runtimeVerified=false`. A supported root graph also receives the selectable
manifest `assets/iris_imported/greenmarine/packs/native.json`. `Report.runnable`
indicates supported executable conversion, not a verified visual match.

Native conversion preserves original fullscreen vertex calculations and emits
ordered scene/compute graphs, per-program uniforms and named targets. Supported
vanilla terrain requests are `gbuffers_terrain_solid`, `gbuffers_terrain_cutout`
and `gbuffers_water`, resolved through the original whole-program fallback graph.
Each actual host pipeline gets a separate variant; cutout variants implement the
Iris default alpha threshold of 0.1. Every other resolved geometry request also
requires its own host adapter. A shared `gbuffers_terrain` or `gbuffers_basic`
cannot qualify the entire pack merely by supplying these three terrain draws.
Original intermediate terrain stages are emitted into the same native world
programs. Geometry input must match the triangular host stream; tessellation
uses three input vertices and requires triangular evaluation. Stage-only
uniforms and captured includes follow the same ABI validation as the endpoints.
The development editor opens `.gsh`, `.tcs` and `.tes` files with their GLSL
compile stage. Project checks and automatic reload validation include every
declared intermediate source and reject missing or invalid edits before reload.

Terrain adapters use actual host meshes, matrices, atlas/lightmap samplers and
per-draw sidecars. Option-selected `block.properties` rules retain exact state
filters and first-match precedence. Unsupported tags, legacy default material
maps for absent files and layer overrides remain explicit blockers. An empty
file intentionally leaves materials unmapped. Default AO is multiplied into RGB;
`separateAo=true` supplies raw tint and AO in alpha. `oldLighting=true` remains
unsupported. Native capture currently requires Vulkan, the vanilla mesher,
regular transparency and filled terrain. Switching packs retires owned capture.

Terrain and fullscreen fragments receive forward `gl_FragCoord.z` (including
vector/indexed reads). Source `gl_FragDepth` values remain forward throughout
the original program, then convert once to host reversed depth after return.
Conservative depth inequalities reverse too. Coordinate-only shaders and depth
declarations without writes retain fixed-function depth. Explicit fragment
coordinate layout redeclarations remain unsupported. Sampled `depthtex0/1/2`
and legacy `gdepthtex` bind owned forward-depth snapshots, initialized each frame
and refreshed at the opaque/world boundaries. Raster, world and compute inputs
share these actual full-resolution images. See
[execution phases](../docs/SHADERPACK-PHASES.md#sampled-world-depth) for ordering
and the remaining original hand-depth requirements. Hand/shadow/entity programs
still need their own host adapters; sampled-depth evidence does not qualify the
entire Eclipse pack as runnable.

Full entity/item/hand/shadow dispatch, dimension and
option/profile switching and the remaining
resource/integration semantics are unfinished. Unsupported requirements prevent
selectable publication; original shaders, licenses and candidates remain editable.
The older single-pass candidate, when available, stays under
`conversion-candidate/`. No replacement lighting effect counts as conversion.

The supported subset follows the primary Iris references for
[program stages](https://shaders.properties/current/reference/programs/overview/),
[final fullscreen passes](https://shaders.properties/current/reference/programs/final/),
[color buffers](https://shaders.properties/current/reference/buffers/colortex/),
[depth buffers](https://shaders.properties/current/reference/buffers/depthtex/), and
[shader settings](https://shaders.properties/current/reference/shadersproperties/overview/).

## Standalone command line

No game process or installed addon is needed. Use Java 25+ and the built addon
JAR on the classpath, together with the ordinary GreenMarine JAR (for its pure
Java `ShaderBinary` checks), LWJGL core, LWJGL Shaderc, and both matching native
JARs for the host platform. These are the same LWJGL dependencies used by
Minecraft. Gson is also required for the typed pipeline document and is included
in the converter distribution. Fabric Loader and Minecraft classes are not used
by this CLI.
Do not mix LWJGL versions or use Linux/glibc natives as Android/Bionic natives.

For a Unix shell, set `IRIS_IMPORT_CP` to the actual JAR paths, separated by `:`:

```sh
IRIS_IMPORT_CP='/path/greenmarine-development-tools.jar:/path/greenmarine.jar:/path/gson.jar:/path/lwjgl.jar:/path/lwjgl-shaderc.jar:/path/lwjgl-natives-platform.jar:/path/lwjgl-shaderc-natives-platform.jar'
java --enable-native-access=ALL-UNNAMED -cp "$IRIS_IMPORT_CP" \
  com.koper.greenmarine.development.IrisImporter \
  '/path/to/IrisPack.zip' \
  '/path/to/game/shaderpacks/greenmarine/my-import'
```

The CLI prints the destination, runnable status, preserved file count and full
conversion diagnostics. On Windows use `;` as the classpath separator.

From this repository, run the same conversion with:

```sh
./gradlew -p development-tools irisConvert \
  -PirisPack=/path/to/IrisPack.zip \
  -PirisDestination=/path/to/new/editable-project
```

Build the standalone ZIP with `./gradlew -p development-tools irisConverterDistribution`.
