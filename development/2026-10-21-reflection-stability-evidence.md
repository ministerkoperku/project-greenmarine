# GreenMarine 0.9.1 reflection verification

## Changes and controlled reproduction

Owner reports near-block reflection holes/flicker, especially metals, diamond
and netherite. Water is satisfactory; glass has not been tested. No new Android
gameplay recording was available during this work.

`tools/render-reflection-fixture.py` runs the actual Solstice reconstruction GLSL
in surfaceless desktop OpenGL using llvmpipe. It supplies a known oblique plane,
full-resolution captured metadata and reduced native-style reflection outputs.
It tests reconstruction, not native tracing or Minecraft presentation.

At distance 0.15, normal Z component 0.08 and output divisor 4, the 0.9.0 shader
from the previously built JAR rejected 1458 of 2340 reflective pixels. The new
shader rejected zero. Actual producer coordinates replace ideal reduced texture
centres; grazing-incidence footprint scaling preserves coplanar neighbours.

All 18 combinations of distances 0.05/0.15/0.5, normal Z components
0.03/0.08/0.2 and output divisors 2/4 had zero missing reflective pixels. A second
case uses a closer parallel same-class/same-normal surface with a distinct red
reflection: 1866 mirror pixels and 474 foreground pixels, zero missing mirror
pixels, zero mirror/foreground color leaks. Plane rejection remains active.
Images and JSON outputs are in `build/quality/reflection-*` (local intermediates).

Scalar tests executed the actual GLSL helper bodies via C++, including grazing
acceptance, different-plane rejection, foreground reversed-depth rejection,
producer interpolation at dimensions 2 through 129 and both reduced resolutions,
metal/gem energy and unchanged glass/ice attenuation. Red failures were observed
before adding the new helpers. Material tests classify UV rectangles for every
extension profile and check initialization, missing/invalid sprites, immutable
legacy profiles, serialization offsets and binding conflicts.

Diamond uses solid dielectric class 8, netherite metal class 2. Their separate
272-byte UBO preserves the original 32-slot/1040-byte block and prior bindings.
No native ABI or existing public frame size changed. Existing quality ray/tap
budgets are unchanged; newly reflective diamond/netherite pixels can now issue
reflection rays. No new device FPS claim follows from these checks.

## Build and verification

- Android dual-native `./gradlew test build -PandroidNative=true` with the
  documented Android clang linker: successful.
- Java: 222 tests, zero failures/errors, one optional Sodium fixture skipped.
- Python: 56 tests passed, including actual Vulkan/OpenGL shader compilation and
  SPIR-V validation across classic/multidraw/OIT variants.
- Rust: 10 tests passed, four explicit GPU tests ignored. Both ARM64 release
  libraries were rebuilt after the native class-8 shader change and their exact
  bytes compared with the packaged entries.
- Actual Bionic Java 25 packaged-native smoke: Android ABI 1 ready; FFM calls and
  resource close passed. No attached host was present, as expected for this smoke.
- Independent code review found no Important defect; gameplay remains unverified.

## Delivery

Guarded exporter verified the Android AArch64 ELF, Bionic dependencies, absence
of RPATH/RUNPATH, mod metadata and exported byte checksum.

`/storage/emulated/0/Documents/koper/recents/greenmarine-0.9.1-prototype.jar`

JAR SHA256:
`d1580ac74540d67ec6e9cd2f5a797be1f9884a4e5b560871607a3c40326b3604`

Android native SHA256:
`1e2e2a788d8aa6dcc323e558bdcad35110be551f2eeb509494315ada60836adf`

Linux ARM64 native SHA256:
`586f9009e29ed2298d9fde2dd5145c576d6fd2a22461cdc60ed084a3d8f61de2`

The controlled defect is corrected. Remaining reflection flicker, final visual
quality and performance on the owner's running Android Vulkan client require
gameplay acceptance. Native targets remain loaded block-model geometry; this
patch does not add player reflections, caustics or microfacet path tracing.
