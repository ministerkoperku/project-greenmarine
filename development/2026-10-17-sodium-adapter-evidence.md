# Sodium adapter 0.8.2 verification

Target: Minecraft 26.3, Sodium `0.9.3-alpha.1+mc26.3`, built-in Solstice RT and
Seabreeze terrain programs. Upstream source used for the ABI and integration
test includes: CaffeineMC/sodium tag `mc26.3-0.9.3-alpha.1`, commit
`44b89f42dec873aa7c799224d7a10359b393a487`.

## Failure addressed

The owner's 19:28 session shows a Sodium chunk draw entering GreenMarine's
two-attachment material replay with Sodium's unmodified pipeline. The
attachment-count validation exception disabled Solstice. A matching owner
A/B test works without Sodium. See `2026-10-17-sodium-log-analysis.md`.

The adapter now recognizes the host's exact shader pair/packed ABI, translates
terrain source inputs through the runtime Sodium decoder, preserves old
uniform indices and push constants, and compiles separate raster/capture
variants. Unknown ABIs are not classified by mod namespace alone.

## Checks run

```sh
GREENMARINE_TEST_SODIUM_RESOURCES=/tmp/greenmarine-sodium-src/common/src/main/resources \
CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath' \
./gradlew test build -PandroidNative=true \
  -PandroidLinker=/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang
```

- Build successful: **171 Java tests, zero failures/errors/skips**.
- New pipeline tests check classic and actual `OitPipelineSet`-built stages,
  attachment preservation, uniform-prefix preservation, push constants,
  packed layout preservation, unsupported packs and stale ABI rejection.
- Review found that Minecraft gives Sodium OIT pipelines `minecraft:` names.
  A new actual-builder regression test failed before the namespace fix and
  passed after recognition was changed to use shader/ABI identity.
- The offline integration test links **30 vertex/fragment pairs** for Vulkan
  and OpenGL: Solstice raster/capture and Seabreeze raster, each with classic,
  cutout, depth-bounds, transmittance and accumulation variants. It uses actual
  host OIT defines, actual upstream Sodium includes and cached Minecraft assets.
- All compiled modules pass `spirv-val`; disassembly verifies the four packed
  Sodium vertex inputs and exact fragment output attachment slots, including
  OIT output arrays. Thus a merely compiling vanilla-input shader does not pass.
- `cargo test --locked`: **10 passed**, four explicit RX 9070 XT GPU tests
  ignored; Rust source and native ABI are unchanged by this adapter.
- Android/Bionic Java 25 loads the actual 0.8.2 distribution JAR, selects
  `android-aarch64`, exercises FFM, closes the engine and rejects retained
  tracer downcalls: **exit 0**.
- Same Android JVM with native disabled: Java pulse fallback passes, **exit 0**.
- Android library depends only on `libdl.so`, `libm.so`, `libc.so`, with no
  Termux RUNPATH/RPATH. `git diff --check` passes.
- Independent code review found no remaining blocker after the OIT correction.

## Remaining rendered acceptance

No new Minecraft/Sodium render session was started on the phone during this
implementation. The launcher-private instance remains inaccessible from this
workspace. Shader linking and native ABI checks are not a gameplay capture,
GPU timing, frame-rate measurement or blanket Sodium compatibility claim.

The owner's next test should repeat the former failing Medium/Rays Off case,
then the working no-Sodium RT settings, with matching resolution/chunk distances
and scene. Exercise water/OIT and reloads. Record the first error if any; only
compare FPS after chunk/shader warmup at similar device temperature.

Install/export details and scope: `docs/SODIUM.md`.
