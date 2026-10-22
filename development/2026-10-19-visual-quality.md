# 0.8.7 visual quality investigation and plan

Owner reports strange sand, cut sky and odd reflections. No new gameplay images
were available; do not mark that exact appearance reproduced or accepted.

Confirmed source causes: procedural puddles ignored material porosity; cloud
noise did not repeat at the floating-origin wrap; sky crossed an 8-bit boundary
before tone mapping; reflection reconstruction ignored roughness; a green demo
light spawned automatically after 20 ticks in every world.

Implementation scope: class-7 porous materials within the existing 32-profile
frame, periodic filtered cloud noise, bounded zero-mean output dither, bounded
rough-reflection tent reconstruction retaining producing-capture validation,
opt-in automatic demo light with source-compatible constructors. Preserve native
ABI, frame schemas, owned resources, pack identifiers and fallback paths.

Regression tests were observed failing before implementation: missing periodic
noise/output helpers, absent roughness filter policy, sand class 1 instead of 7,
and missing automaticDemoLight option. The latter failed Java compilation on the
missing accessor. These are controlled source-policy tests, not game acceptance.

Sky fixture uses actual Solstice GLSL through surfaceless Mesa llvmpipe, with both
8-bit output boundaries modeled. At identical camera/time settings, origin X=0
versus X=4096 had mean RGB absolute pixel differences of 38.6195/19.1670/7.2915
before, and 0.0000309/0.0000077/0.0000077 after (0..255 units). Images are in
build/quality/sky-{before,after}[-wrap].png. Residual differences are float and
quantization noise. This is not Android gameplay or whole-frame performance.

Validation and delivery results will be appended after the final build.

## Final validation

- Android dual-native `./gradlew test build -PandroidNative=true` succeeded.
- Java: 190 tests, 0 failures/errors, 1 skipped optional Sodium resource fixture.
- Python: all 54 tests passed, including actual Vulkan/OpenGL shader compilation,
  SPIR-V validation and scalar GLSL policies executed as C++.
- Rust: 10 passed, 4 explicitly GPU-only tests ignored. Rust/native code unchanged.
- Actual Android/Bionic Java 25 loaded the packaged Android library, exercised FFM
  calls and closed it successfully (`BundledNativeSmoke`). No attached GPU host.
- Packaged Android and Linux native bytes match their explicit-target release
  build artifacts. All GreenMarine shader imports are present in the Solstice ZIP.
- Independent read-only review found no Important correctness/API/performance
  issue. Its missing ZIP-helper observation was corrected and archive-checked.
- `git diff --check` passed.

JAR SHA256: `4a383cd5b6b3585053b58e9b4b1c7d46f600781675136d423056961369daa90b`.
Android native SHA256: `498919581aad70796961b929ffe19be4f3df0bdc6b18e20ac405aae084b2d238`.

Exact Android gameplay appearance, the first-person near-block artifact, and FPS
are not verified. Player/entity native meshes and volumetric clouds are not added.
The broader rough-reflection footprint adds texture reads only on eligible rough
reflective pixels; no additional native rays are issued. No FPS improvement claim.
