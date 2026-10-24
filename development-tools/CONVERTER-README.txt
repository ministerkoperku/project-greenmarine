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
Successful translation creates an editable GreenMarine fullscreen effect. It is
not full Iris compatibility. Complex/world/multiple-buffer packs are preserved
for manual conversion and are not falsely published as selectable packs.

See IRIS-IMPORT.md for the supported subset, limits and platform requirements.
