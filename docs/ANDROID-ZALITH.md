# Android (Zalith launcher)

GreenMarine runs on Android through Java Edition launchers such as Zalith. The jar
is the same Fabric mod as on desktop; Android builds additionally bundle an
Android/Bionic ARM64 native library. It is not an APK or a Bedrock add-on.

## Install

1. In the launcher, choose a 64-bit Java **25** runtime, Minecraft **26.3** and
   Fabric Loader **0.19.5+**. Make sure the instance reaches a world without
   GreenMarine first; launcher, SDL or graphics failures before mods load are
   launcher problems.
2. Put the GreenMarine jar in the instance's `mods` folder. Keep a single
   GreenMarine version installed. Fabric API, KoperLib and Sodium are optional.
3. Add `--enable-native-access=ALL-UNNAMED` to the **JVM** arguments and
   `--graphicsBackend vulkan` to the **game** arguments if the launcher's native
   Vulkan path works for Minecraft 26.3. A launcher renderer that uses Vulkan
   internally does not necessarily give Minecraft a native Vulkan device.
4. Open the pause menu and tap **GreenMarine** (top left) for settings; desktop F6
   opens the same screen.
5. New installs start with **Marine Raster**, Medium quality and rays Off. Try
   Solstice RT after confirming normal rendering; hardware RT also needs Vulkan
   and the Hardware ray mode.

## Native library loading

The jar contains `native/android-aarch64/libgreenmarine_engine.so` (Bionic) and
`native/linux-aarch64/libgreenmarine_engine.so` (glibc, for proot/Linux). The loader
inspects the running process: Android OpenJDK may report `Linux/aarch64`, so
`/proc/self/maps` identifies Bionic; a glibc process inside proot stays Linux.
When procfs is restricted, JVM runtime hints decide.

The library is extracted to `java.io.tmpdir`, which must allow mapping executable
code (the launcher's app-private cache does; `/sdcard` and Documents do not). An
extraction failure is logged; check the launcher's temporary directory then.
On Android the Rust code stays mapped until the process exits, because unloading
it could leave Bionic thread-exit callbacks pointing at unmapped code; GPU state
is still detached and temporary files removed on shutdown.

The log line `Native ABI 1 ready [android-aarch64]` confirms loading. Without the
native library, rendering and Java animation still work; only native features
(hardware RT, native animation) are unavailable. `-Dgreenmarine.native.disable=true`
forces the Java path for diagnosis.

## Sodium

The built-in packs' terrain programs work with Sodium 0.9.3-alpha.1, including
hardware surface RT; see [Sodium compatibility](SODIUM.md).

## Reporting problems

Include the launcher, Minecraft, Java and GreenMarine versions, the GPU, the
selected pack/quality/ray mode and the full `latest.log` (for example through
mclo.gs). The F6 screen and chat show the current status reason, which usually
names what is missing. Building the Android jar from source is covered in
[development](DEVELOPMENT.md).
