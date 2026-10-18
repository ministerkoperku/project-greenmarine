# Third-party components

GreenMarine bundles LuaJ JSE 3.0.1 in a nested jar and uses ash 0.38.0,
libloading 0.8.9 and cfg-if 1.0.5 in its Linux native library. Their MIT
notices are preserved under `META-INF/licenses/greenmarine/` in the mod jar,
including distinct notices from the published LuaJ source distribution.
These notices do not choose a license for GreenMarine's own source.

The optional Sodium 0.9.3 terrain adapter consumes Sodium's own shader includes
from the running host mod. Sodium and its packed vertex decoder are not bundled.
Its published terrain ABI is the integration reference; see
[the matching upstream source](https://github.com/CaffeineMC/sodium/tree/mc26.3-0.9.3-alpha.1).

Minecraft, Fabric Loader, LWJGL, Renderpearl and their dependencies are supplied
by the host environment. KoperLib integration dependencies are compile-only;
they are not embedded by the optional adapter. The Gradle wrapper retains its
upstream distribution terms. Radiance and Veil were architecture references;
their implementation was not copied or bundled.
