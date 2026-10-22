# Development

## Layout

| Path | Contents |
|---|---|
| `src/main/java` | Fabric mod: host integration, pack runtime, public APIs (`api/`) |
| `src/main/resources` | Built-in packs, shaders and includes |
| `src/samples/resources` | Demo packs used by tests and probes (not shipped) |
| `src/probe/java` | Runtime probes, only with `-PruntimeProbe=true` |
| `engine/` | Rust native engine (ash/Vulkan), loaded through Panama FFM |
| `examples/` | Editable resource-pack examples packaged by Gradle tasks |
| `tools/` | Pack CLI, Android exporter, shader/fixture tests |
| `development/` | Plans, evidence and session notes |

Java owns Minecraft lifecycle and host objects; Rust owns native processing behind
a versioned C ABI (no JNI). Minecraft owns every borrowed Vulkan handle.

## Build and test

Requirements: Java 25, Rust/Cargo, `glslangValidator` (and `spirv-val` for the
shader tests).

```sh
./gradlew test build          # mod jar, shader packs, host native library
cd engine
cargo test
cargo clippy --all-targets
cargo build --release
cd ..
python3 -m unittest discover -s tools -p 'test_*.py'
```

`./gradlew build` also writes editable pack ZIPs to `build/shaderpacks/`
(`solsticeShaderpack`, `seabreezeShaderpack`, `rasterShaderpack`,
`exampleShaderpack`, `prismarineCorrectedShaderpack`).

### GPU tests

Native GPU tests are `#[ignore]`d and pick their device by name substring through
`GREENMARINE_GPU_TEST_DEVICE` (default `RX 9070 XT`):

```sh
cd engine
GREENMARINE_GPU_TEST_DEVICE="RX 9070 XT" cargo test -- --ignored --nocapture
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json \
GREENMARINE_GPU_TEST_DEVICE=llvmpipe cargo test actor_compute -- --ignored
```

Compute-only tests run on lavapipe; ray-query tests need hardware (lavapipe on
aarch64 under proot crashes while building acceleration structures).

## Native targets and Android

The default build packages the target reported by `rustc -vV`
(`x86_64-unknown-linux-gnu` or `aarch64-unknown-linux-gnu`). An Android jar adds the
Bionic library:

```sh
rustup target add aarch64-linux-android
./gradlew test build -PandroidNative=true \
  -PandroidLinker=/absolute/path/to/aarch64-linux-android26-clang
python3 tools/export-android-mod.py build/libs/greenmarine-<version>.jar
```

With Termux clang (`/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang`)
also set `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath'`,
otherwise the library embeds Termux search paths and the exporter rejects it. The
exporter checks the packaged ELF (AArch64, Bionic dependencies only, no
RPATH/RUNPATH) before copying. `-PnativeTarget=aarch64-linux-android` builds an
Android-only jar; host integration tests then have no matching library. Unknown
targets fail the build.

## Optional KoperLib Fullpack variant

```sh
./gradlew -PfullpackIntegration=true test build
```

Uses compile-only KoperLib Core/Fullpack jars from `../koperlib/build/libs/`
(`-PkoperlibVersion=...` to choose). It registers the `greenmarine_effect` content
type; `examples/fullpack/greenmarine_demo/` is a sample. The variant replaces the
base jar; install only one.

## Runtime probes

Probes drive a development client and capture images and timings; they are never
part of a release jar and do not touch installed instances.

```sh
./gradlew -PruntimeProbe=true -PprobeOutput=probe-output/portable runClient
python3 tools/verify-probe.py run/probe-output/portable
```

Further probes: `-PvoxelProbe`, `-PworldLightingProbe`, `-PhardwareRtProbe`,
`-PsolsticeProbe`, `-PworldProgramsProbe`, `-PrenderApiProbe`,
`-PdynamicRayProbe`, `-PperformanceProbe` (see `build.gradle`). Use
`-PgraphicsBackend=opengl` for the secondary backend; on Wayland desktops set
`SDL_VIDEODRIVER=wayland`.

## Conventions

- Keep shaderpack schema 1, the 288-byte `MarineFrame` and native ABI version 1
  compatible; add optional symbols instead of changing existing ones.
- Never advertise hardware RT because a GPU supports it; report the initialized path.
- Public docs live in `docs/`, plans and evidence in `development/`.
