# LabPBR 0.9.8 verification, 2026-10-24

## Reproduction and repair

The actual Minecraft 26.3 Vulkan probe loaded a generated 16x16 stone specular
map `(192,231,0,255)` plus a flat normal map. Before repair it found 1/1 maps,
but the production `labpbr.glsl` returned absent and sampled zero bytes at stone's
visible UV. The atlas was 2048x2048. CPU lookup at that UV was zero too.

Minecraft's `TextureAtlasSprite` constructor derives U0/V0 from `(x+padding)` and
`(y+padding)`. Its getX/getY return the gutter origin. Stone's gutter origin was
(1680,1312), while its visible centre was UV (0.83203125,0.65234375). PbrAtlas had
copied maps at the gutter origin. Copying at rounded U0/V0 times atlas dimensions
changed the same fixture to raw `(192,231,0,255)`, present=255, metal=255,
roughness=16/255 (within output quantization). This was not an upload failure.

## Additional material fixes

An R8 atlas carries specular/normal presence independently of material values.
The all-black specular-map regression failed on the old zero-texel sentinel and
passes with explicit presence. Unmapped normals retain procedural relief and full
ambient light. GPU fixtures verify dielectric F0=229/255, zero F0, metal albedo F0,
and unit grazing Fresnel. Solstice uses those values for reflections and sun
highlights. CPU NativeImages close after immediate Renderpearl staging; a GPU
readback fixture explicitly closes its CPU image before submitting.

## Verification

- `./gradlew test build -PandroidNative=true -PandroidLinker=/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang`, with target rustflags `-C link-arg=-fno-termux-rpath`: passed. 254 ordinary Java tests passed, 5 skipped (4 opt-in GPU tests and the Sodium-resource fixture).
- `xvfb-run -a ./gradlew -PpbrGpuTest test --tests '*PbrUploadGpuTest'` on lavapipe: all 4 opt-in GPU tests passed separately.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: 59 passed, including actual Vulkan/OpenGL shader compilation and SPIR-V validation.
- `cargo test`: 17 portable tests passed, 8 hardware/opt-in GPU tests ignored; no Rust source changes.
- Actual Minecraft Vulkan probe retained the real static `WorldPrograms.PBR` instance. Mapped, removed, restored phases all passed through actual resource reload/reset hooks. Centre/corner material samples, unmapped dirt control, map counts, normal AO, R8 presence and initialized 1x1 fallbacks passed.
- A deliberately wrong black/nonmetal fixture, with an old passed JSON preseeded, was rejected and replaced the result with `status:failed`. This negative run is expected; it verifies that stale success cannot survive a failed rerun.
- Independent review found no Critical/Important production defects. Its reload-ownership coverage concern and stale-success reporting concern were addressed and exercised.
- `git diff --check`: clean.

Probe commands are documented in `docs/DEVELOPMENT.md`. Local complete logs are
`/tmp/gm-pbr-{gpu-final,python-final,rust-final,android-build,runtime-owner,invalid}.log`.
The successful runtime report is `run-pbr/probe-output/pbr-result.json`.
The intentional negative report is `/tmp/gm-pbr-invalid-run/probe-output/pbr-result.json`.

## Delivery

Guarded Android exporter verified Bionic ARM64 dependencies/no RPATH and exported
`/storage/emulated/0/Documents/koper/recents/greenmarine-0.9.8-prototype.jar`.
Both packaged Android and Linux ARM64 natives match the respective release build
outputs byte for byte; the delivered JAR matches its build output. Probe/test
classes are excluded from the distribution.

JAR SHA256: `9f0bee87241eedcb87e4cb8a43b7da56be7414e68d864a9e620f8da637eab1b9`.
Android native SHA256: `4fb00f3906a8833b0f70440b96237f00b3ff33219514a28f1d367f5a1150e441`.
Solstice pack also exported to the same directory, byte verified, SHA256
`8c5693b7e7dc45d9556549d73bd853394926c7888a222022e9c2dfeb886f2c61`.

These checks verify material upload/stitching/decoding and resource lifecycle on
host Vulkan/lavapipe. Android gameplay appearance, hardware RT performance,
Kender per-texture PBR, POM and animated material maps remain unverified or outside
this change; see `docs/LABPBR.md` for precise support limits.
