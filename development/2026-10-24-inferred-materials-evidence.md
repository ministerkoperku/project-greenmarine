# Solstice 0.9.9 inferred materials evidence

The implementation adds conservative texture-family defaults when `_s` is absent.
Explicit specular/normal maps remain independent, builtin profiles remain exact,
and water keeps its current path. Ores/raw metal blocks are rough dielectrics;
unknown names retain the original fallback. Matte roughness varies by at most
0.04 from albedo, bounded to 0.75–0.98. Existing procedural relief supplies detail.

The existing specular atlas carries inferred raw roughness/reflectance/transmission
and integer surface class under presence bit 2. Authored LabPBR remains bits 0/1.
This avoids new GPU bindings, frame/native ABI changes and uniform profile limits.
The shared terrain shader applies identical properties in capture and shading.
Known builtin textures do not consume inferred entries. Full material textures
now cost about 36 MiB at 2048² even for an albedo-only pack.

## Verification

- RED: classifier behavior failed for missing metal/material defaults and builtin preservation; actual Vulkan fixture rejected the missing shader fallback function.
- GREEN: 3 classifier tests and all 5 Vulkan upload/material tests passed. Shader tests cover inferred metal properties, explicit-map and builtin-glass precedence, bounded matte variation and authored-map presence independence.
- Actual Minecraft 26.3 texture names exposed additional iron_chain/copper_chain/copper_bars gaps; added behavioral assertions, observed RED, added those anchored metal shapes.
- Fresh `./gradlew test build -PandroidNative=true -PandroidLinker=/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang` with `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath'`: passed. 257 ordinary Java tests passed; 6 opt-in tests skipped. Both Android/Bionic and Linux ARM64 release natives included.
- `cargo test`: 17 passed, 8 hardware/opt-in tests ignored. Rust source unchanged.
- Independent combined review reported no Critical/Important findings; confirmed explicit-mask/builtin precedence and capture/shading consistency.

The first Python/runtime attempts were interrupted by a Termux restart: Python
reported cascading OS Errno 38 (8 failures, 31 errors), and Minecraft aborted with
a futex error before producing probe stages. Fresh reruns are recorded below.

## Limits

This is name-based inference, not authored-map recovery. Unusual mod texture names
can require explicit maps. No generated POM or true height maps, animated material
maps, Android gameplay screenshots or hardware-RT performance claim is made.
Existing LabPBR limitations remain documented in `docs/LABPBR.md`.

## Fresh reruns and delivery

- Fresh Python suite: all 59 tests passed, including actual Vulkan/OpenGL shader compilation and SPIR-V validation.
- Fresh actual Minecraft Vulkan/lavapipe probe: `status:passed`, mapped → removed → restored through real WorldPrograms owner/reset hooks. 305 inferred sprites with mapped stone; 306 with its maps removed. Removed stone sampled raw inferred `(232,10,0,1)` and shader output roughness 232/255, reflectance 10/255, dielectric class 1. Authored-map presence stayed false; dirt control stayed unmapped. Restoring maps recovered authored metal and roughness 16/255 at centre and corners.
- `git diff --check`: clean.
- Guarded Android exporter passed Bionic ARM64 ELF/dependency/no-RPATH checks. Both packaged native libraries match their respective release outputs byte for byte. Probe/test classes are excluded; the new classifier is packaged.
- Delivered JAR and ZIP byte-match build outputs. Existing 0.9.8 deliveries were preserved.

Artifacts in `/storage/emulated/0/Documents/koper/recents/`:

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| `greenmarine-0.9.9-prototype.jar` | 1496554 | `905a50ffdf9edaec69d88ccd33a9e21cb2ff27c48b622215ed81634d1f49ca26` |
| `solstice-rt-0.9.9-prototype.zip` | 35267 | `19310b75392e0a0ecf0bfb06e9e9d9d74ea9322795b9701ca73131c7977e66aa` |

Android native SHA256: `4fb00f3906a8833b0f70440b96237f00b3ff33219514a28f1d367f5a1150e441`.
Logs: `/tmp/gm-inferred-{android-final,python-final,runtime-final,rust,gpu-green}.log`.
Runtime report: `/tmp/gm-inferred-runtime-run/probe-output/pbr-result.json`.
No installed game JARs, commits, branches or sibling repositories were changed.
