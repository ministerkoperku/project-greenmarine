# Android ARM64 / Zalith installation and verification

GreenMarine 0.8.1 adds Android ARM64 native library selection and packaging.
The requested device is a Poco F8 Pro running Zalith. The owner now reports
working Solstice RT shadows at approximately 60 FPS with low chunk-generation
settings and coexistence with Fabric API/Koper mods. A separate Sodium session
showed a render-attachment mismatch. Version 0.8.2 adds a targeted adapter;
see [Sodium compatibility](SODIUM.md) for the remaining phone acceptance.
See the [owner field report](../development/2026-10-17-poco-field-report.md).

## Install

1. In Zalith, select a 64-bit Java **25** runtime, Minecraft **26.3** and
   Fabric Loader **0.19.5 or newer**. First verify that this instance can
   reach a world without GreenMarine. Launcher/runtime/SDL/graphics startup
   failures before mod initialization need to be resolved in the launcher.
2. Put `greenmarine-0.8.2-prototype.jar` in that instance's `mods` directory.
   Install only one GreenMarine version. Fabric API, KoperLib and Fullpack
   are not required by this base artifact. No installed game JAR needs replacing.
3. Add `--enable-native-access=ALL-UNNAMED` to the instance's **JVM** arguments.
   Add `--graphicsBackend vulkan` to **game** arguments if the launcher exposes
   them and its Minecraft 26.3 native Vulkan path works. These are different
   argument lists. A launcher renderer that internally uses Vulkan does not
   necessarily give Minecraft a native Vulkan Renderpearl device.
4. Open the pause menu and tap **GreenMarine** at the top left. The settings
   screen returns to the pause menu when closed. Desktop F6 remains available.
5. Start with **Portable**, **Low** quality, rays **Off**, profiling **Off**.
   Gradually enable features after verifying ordinary rendering and reloads.
   Existing configurations are preserved; installing does not force a preset.

The export directory is `/storage/emulated/0/Documents/Koper/recents`.
This is a Fabric Java Edition mod JAR, not an APK or Bedrock add-on.

## Native loading and fallback

The Android resource is `native/android-aarch64/libgreenmarine_engine.so`.
The accompanying Linux ARM64 resource is separate at
`native/linux-aarch64/libgreenmarine_engine.so` and uses glibc. The current
dual ARM64 export does not contain a desktop x86_64 library. Building on a
supported x86_64 Linux host selects the x86_64 resource automatically.

Selection inspects the **running Java process**. Android OpenJDK can report
`Linux/aarch64`; `/proc/self/maps` identifies Bionic. A glibc process inside
proot remains Linux ARM64 even when Android directories and hints are visible.
When procfs is restricted, JVM OS/runtime hints are used.

Android keeps Rust library code mappings alive until process exit. Testing
found that unloading the Rust library through FFM could leave Bionic
thread-exit callbacks pointing at unloaded code. Closing GreenMarine still
detaches native GPU state, closes the engine's logical lifetime and removes
extracted temporary files; it does not unload Android code mappings. Linux
keeps scoped library unloading. No native ABI or shaderpack ABI changes.

The startup log reports `Native ABI 1 ready [android-aarch64]` after successful
Android loading. A missing or unloadable native library leaves portable
rendering and Java animation available. Native availability alone does not
enable hardware ray tracing: the real host device, enabled features, native
pipeline and current scene readiness must all pass their existing checks.

Extraction uses Java's `java.io.tmpdir`. This directory must allow native
library mapping. If the log reports an extraction or mapping failure, check
that the launcher's Java temporary directory is writable **app-private cache**,
rather than `/sdcard`/Documents. Do not put executable native libraries in the
public export directory and expect Android to load them there. The JAR can
be copied through Documents; the loader extracts its own temporary copy.

`-Dgreenmarine.native.disable=true` is an optional JVM diagnostic switch for
testing Java fallback. It is not needed for normal installation.

## Build from source

Required: Java 25, Cargo/Rust, `glslangValidator`, and the Android ARM64 Rust
target plus a suitable Android linker for the Android bundle.

```sh
rustup target add aarch64-linux-android
./gradlew test build
```

The default build packages the native target reported by `rustc -vV`.
To additionally include Android ARM64 from a Linux host:

```sh
./gradlew test build -PandroidNative=true \
  -PandroidLinker=/absolute/path/to/aarch64-linux-android-clang
```

An Android NDK linker such as `aarch64-linux-android26-clang` can be used as
the property value. Cargo's `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` also
works when the property is omitted. On the development phone, the available
Termux linker is `/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang`.
When using Termux clang, set
`CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath'`
for that Gradle invocation to omit Termux-private search paths from the library.
Do not pass this Termux-specific compiler flag to a standard NDK clang.
Gradle tracks both global and target-specific Cargo rustflags for rebuilds.
`-PnativeTarget=aarch64-linux-android` selects an Android-only cross-build;
host-native integration tests require a matching host library, so use the
dual build above for local verification on Linux glibc.

Only `aarch64-linux-android`, `aarch64-unknown-linux-gnu` and
`x86_64-unknown-linux-gnu` are currently accepted native targets. Unknown
targets fail explicitly. Resource processing removes stale native directories
when switching targets so a previous host library cannot leak into a new JAR.

## Runtime acceptance still required

On the selected Zalith version, record the Minecraft/Java/launcher versions,
GPU driver and actual GreenMarine backend. Exercise:

- Open a world, enter settings by touch, switch packs and return to the game.
- Rotate the phone, resize/change resolution and reload resources.
- Leave/re-enter a world, pause/resume and background/foreground the launcher.
- Verify Java fallback with native disabled, then verify native loading.
- Enable voxel rays separately; enable hardware rays only if the runtime
  reports an initialized supported path. Record fallback reasons otherwise.
- Measure sustained CPU/GPU time and memory at a fixed resolution and quality.

Passing compilation or ABI checks does not substitute for these gameplay tests.
The API backlog is in [API-ROADMAP.md](API-ROADMAP.md).
