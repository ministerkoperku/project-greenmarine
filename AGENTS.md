# GreenMarine contributor instructions

Owner delivery preference: export finished mod artifacts to Android shared storage
at `/storage/emulated/0/Documents/koper/recents/`, never `/root/Documents` or another
Linux-home delivery directory. Verify exported files against the build outputs.
Android delivery must contain the actual Android/Bionic ARM64 native library.
Build with `-PandroidNative=true` and the Android linker documented in
`docs/ANDROID-ZALITH.md`; use `tools/export-android-mod.py` to verify and export.
Never export a host-only Linux JAR as an Android release.

Read `../AGENTS.md` in the workspace and this file before editing.

GreenMarine is a standalone Fabric mod targeting Minecraft 26.3 and Java 25.
Fabric Loader is required; Fabric API and all KoperLib modules are optional.
Do not introduce dependencies on them into base entrypoints or base public APIs.
Fullpack adapters belong to the optional integration source set and use
compile-only dependencies. Preserve existing KFX providers and registry ids.

Java owns Minecraft lifecycle and host integration. Rust owns native processing,
using Panama FFM with a versioned C ABI, never JNI. Minecraft owns borrowed
Vulkan handles; destroy only GreenMarine-owned resources. Use the actual host
physical device and render-thread command encoder.

Vulkan is the primary backend and runtime acceptance target. Use Minecraft's
Renderpearl API for passes shared with OpenGL; do not introduce an OpenGL-only
rendering path. Distinguish portable code from tested backend support.

Shaderpack API v1 uses declaration-ordered parameters and the 288-byte
MarineFrame std140 block. Keep fragment GLSL fallbacks for optional SPIR-V.
All scene shaders receive initialized VoxelFrame/material bindings; unavailable
voxel data must report readiness false. Lightmaps have no world bindings.
Parameter-only edits preserve scripts/pipelines and retired names cannot exhaust
their storage budget. HDR copies require matching dimensions and actual formats.
Lua runs once per unpaused tick and owns its light handles; preserve execution
budgets and cleanup on failure/world/pack changes. Never advertise hardware
RT merely because a GPU supports it; the host device and implemented pipeline
must both support the path. Benchmark CPU and GPU costs separately.

Use `./gradlew test build` and `cargo test` in `engine/`; finish Rust changes
with `cargo build --release`. Runtime rendering evidence is separate from
compilation. Public documentation lives in `docs/`, plans and evidence in
`development/`. Keep new text English and preserve `readme.txt` as original
owner material. Never replace installed game jars during development.
