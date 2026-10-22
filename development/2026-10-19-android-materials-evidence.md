# Android delivery and material fixes, 0.8.4

## Root causes and owner evidence

Owner log: https://mclo.gs/Pswt31v (raw log retained at
`/tmp/greenmarine-Pswt31v.log` during development). The two latest screenshots
at 08:56:38 and 08:58:41 show the native-library missing message. The log selects
Solstice RT, reports `native=false`, and explicitly identifies the absent
`native/android-aarch64/libgreenmarine_engine.so`. The exported 0.8.3 artifact
contained only its Linux host native library. This was a delivery error, not
evidence that the Adreno device lacks RT capabilities.

The 0.8.3 exporter regression is now pinned: the Android exporter rejects that
actual old JAR before creating a destination. New exports validate ELF64/AArch64,
Bionic `libc.so` dependencies and absence of embedded RPATH/RUNPATH.

The cave reflection cause is unconditional procedural-sky lookup in world water
and fullscreen reflection-miss fallback. World sky reflection now depends on local
skylight, and fullscreen misses preserve the existing raster result. The heuristic
does not constitute directional visibility at arbitrary cave openings.

Owner subsequently requested fallback shadows, less uniform reflectivity and glass
lighting. Ordinary terrain previously shared one roughness control. New bounded
profiles distinguish glass, stained/tinted glass, ice variants and selected metals;
unknown blocks remain rough/nonmetallic. Reflection defaults change to 0.55 without
editing saved owner overrides. Glass sunlight throughput uses the reserved last
triangle float; legacy builders emit zero. Primary/reflection intersection behavior
is preserved. Direct sunlight transmission is scalar, not colored caustics.

Solstice now opts into local voxel sunlight fallback. The existing incremental
32-cube volume supplies one occupancy-only sun trace, bounded to 24 blocks and
faded over two boundary cells. Full-block geometry is approximate; foliage/slabs/
entities/distant shadows are not newly supported. Emissive non-solid voxel cells
do not cast false full-cube shadows. Tinted glass is explicitly opaque to this path.

## Verification

Final dual-target build command:

```sh
CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath' \
./gradlew test build -PandroidNative=true \
  -PandroidLinker=/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang --console=plain
```

- Build succeeds: 186 Java tests, zero failures/errors, one skipped optional
  compiler test requiring external Sodium resources.
- Full Python tool suite passes 51 tests. After final shader edits, all nine
  Solstice/authoring/sky-visibility/Android-delivery tests pass again, including
  classic/multidraw/OIT GLSL/SPIR-V variants on Vulkan and OpenGL.
- The actual shared scalar GLSL sky-visibility function is also executed as C++:
  enclosed cave zero, exposed sky one, bounded monotonic intermediate values.
- Rust: 10 tests pass, four explicit desktop GPU tests remain ignored;
  `cargo build --release --locked` succeeds. Both native SPIR-V modules pass
  `spirv-val --target-env vulkan1.2`.
- Android/Bionic OpenJDK 25.0.4 loads the final JAR's native library and reports
  `Native ABI 1 ready [android-aarch64]`. Packaged FFM calls/close and 22 native
  ray contract checks plus worker teardown pass. Missing-host messages in these
  tests are intentional; the tests do not attach a Minecraft GPU device.
- JAR inspection confirms exact bytes of both selected Linux ARM64 and Android
  ARM64 release libraries, material API classes, voxel opt-in and reflection default.
- Independent read-only review finds no Critical/Important defect in packaging,
  material-frame initialization/ownership, native traversal or shadow policy.
- `git diff --check` passes.

Optimization scope: material atlas/profile upload happens once per renderer reset,
block classification is cached during native scene extraction, and surface RT
does not query reflection for dry ordinary opaque pixels whose outputs are unused.
No whole-frame speedup or phone FPS claim is made.

No rendered Android/Minecraft acceptance was run during this change. The log also
ends in a separate `VK_ERROR_SURFACE_LOST_KHR` during presentation; its cause is
not established or claimed fixed here. Owner settings, installed game jars and
sibling projects are unchanged. Delivery is through `tools/export-android-mod.py`
to Android shared Documents, with matching SHA256 hashes.
