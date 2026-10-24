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
paths, ZIP64, encrypted/multipart ZIPs, more than 2048 files, and more than 8 MiB
uncompressed are rejected. ZIP input also has a 32 MiB compressed limit.

A runnable candidate means one supported fullscreen effect passed Shaderc Vulkan
compilation. It does not establish full Iris runtime compatibility or verified
in-game rendering. The generated fragment is
`assets/iris_imported/shaders/core/converted.fsh`; the selectable manifest exists
only when automatic translation succeeds without reported unsupported features.

Automatic translation supports one root `final.fsh` or `composite.fsh`, ordinary
`texcoord`/`texCoord` fullscreen coordinates, `colortex0`/`depthtex0` sampler names,
GLSL 120/330 headers, `texture2D`, and `gl_FragColor` or one explicit color output.
Bounded relative/root-relative includes are expanded from the snapshot. Known
simple fullscreen vertex templates can be replaced by the host fullscreen pass.
The names become `ColorSampler`, `DepthSampler`, `texCoord`, and a location-zero
fragment output. Scene/depth formats, depth direction and color conventions can
differ from Iris; review the effect in game.

World/gbuffer programs, extra passes, dimension overrides, custom vertex logic,
SSBOs/images/atomics, additional color/depth buffers, custom render targets,
shader settings and unsupported uniforms require manual conversion. Such packs
still receive their preserved sources and report, with `runnable=false` and no
selectable manifest. A translated candidate, when available, is placed under
`conversion-candidate/` for review. No fallback pass is represented as a successful
conversion of the original effect.

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
Minecraft. Gson, Fabric Loader and Minecraft classes are not used by this CLI.
Do not mix LWJGL versions or use Linux/glibc natives as Android/Bionic natives.

For a Unix shell, set `IRIS_IMPORT_CP` to the actual JAR paths, separated by `:`:

```sh
IRIS_IMPORT_CP='/path/greenmarine-development-tools.jar:/path/greenmarine.jar:/path/lwjgl.jar:/path/lwjgl-shaderc.jar:/path/lwjgl-natives-platform.jar:/path/lwjgl-shaderc-natives-platform.jar'
java --enable-native-access=ALL-UNNAMED -cp "$IRIS_IMPORT_CP" \
  com.koper.greenmarine.development.IrisImporter \
  '/path/to/IrisPack.zip' \
  '/path/to/game/shaderpacks/greenmarine/my-import'
```

The CLI prints the destination, runnable status, preserved file count and full
conversion diagnostics. On Windows use `;` as the classpath separator.
