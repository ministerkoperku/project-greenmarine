GreenMarine Iris source converter

Requires Java25+ and the matching native platform libraries supplied in lib/.
This distribution is built with the current build host's LWJGL dependencies;
Linux/glibc ARM64 is not Android/Bionic. The in-game addon instead uses the
Minecraft launcher's own Shaderc libraries.

No Minecraft process or installed mod is required for this tool. Run:

  sh iris-import.sh /path/to/IrisPack.zip /path/to/new/editable-project

Or invoke the Java main class using lib/* as the classpath.
Source archives/directories remain unchanged. Original files and license notices
are preserved under iris-source/. Review IMPORT-REPORT.md before using the result.
Whole-program conversion emits linked editable GLSL, successful SPIR-V stages,
and conversion/pipeline.json with typed resources, bindings, dimensions, options
and separate preprocessing/link/compile coverage. Disabled and failed programs
remain explicit, and original source and licenses stay preserved.

A supported single fullscreen effect can also receive a selectable GreenMarine
manifest. Whole Iris scene/shadow pipeline execution, host uniforms, materials,
resource transitions and option/profile behavior still require runtime integration.
Complex packs have runtimeReady=false and no selectable whole-pack manifest.
The bundled Gson JAR supports pipeline metadata; no Minecraft runtime is required.

See IRIS-IMPORT.md for the supported subset, limits and platform requirements.
