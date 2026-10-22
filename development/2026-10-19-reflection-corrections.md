# Reflection corrections after Android owner feedback

Owner: copper appears white, reflections change in a block-shaped region near the
player, and the avatar has no reflected geometry or proper RT shadow. The owner
clarified the avatar appears to occlude the reflection, rather than block geometry
being clipped. The owner confirmed first-person view standing directly in front
of the block; the latest available Android
screenshots still belong to the earlier missing-native-library report.

Confirmed source defects: metal Fresnel was a neutral scalar, and half-resolution
surface outputs were bilinearly sampled without testing the capture texels that
actually produced them. Original opaque visibility allowed a 2% depth mismatch.
Preserve colored conductor response, validate each contributing ray texel against
the current material/normal/world plane, normalize valid weights and preserve the
raster result when none match. Tighten foreground rejection without changing
translucent depth behavior. Use actual capture-centre mapping from the native
compute shader, not the requested output UV.

Player secondary geometry is not currently implemented. Do not label a camera
depth mask or a box proxy as a reflected/skinned avatar or a proper animated player
shadow. Real offscreen avatar support needs a separate dynamic geometry path;
rebuilding the entire terrain BLAS every animation frame is not an acceptable fix.

Execution: inline under existing owner authorization. Tests first for the shared
scalar GLSL policy, then compile all actual shader variants, run Java/native
checks and dual-target Android export. Deliver a new version without changing
installed jars or personal configuration. Report live appearance as unverified.

Implementation: 0.8.5. The four-tap reconstruction verifies each exact producer
capture texel before accumulating lighting and reflection-hit coverage. Its shared
scalar policy has executable tests for copper color ordering, neutral dielectric
response, foreground depth rejection, odd/even source mapping and near-plane/tangent
rejection. Original shader compilation exposed an include-order dependency in
terrain fragments; explicit surface/policy includes fixed it before export.
The independent read-only reviewer confirmed the correction and reported no
remaining Critical/Important issue. No dynamic body proxy or terrain BLAS rebuild
on every player animation frame was added.

Verification: dual-target Android `test build` succeeds; 186 Java tests report
zero failures/errors and one skipped optional Sodium-resource test. All 52 Python
tests pass, including actual Solstice GLSL/SPIR-V variants and executable scalar
policy regressions. Ten Rust tests pass; four explicit desktop GPU acceptance
tests remain ignored. The final 0.8.5 JAR loads its Android/Bionic library on
OpenJDK 25.0.4, reports native ABI1 ready and passes packaged FFM calls/close.
`git diff --check` passes. Native sources/binaries did not change in this correction.

Delivery: `tools/export-android-mod.py` verifies the actual Bionic ELF and exports
`greenmarine-0.8.5-prototype.jar` to Android `Documents/koper/recents`, with matching
SHA256. No installed jars, owner settings, branches or sibling sources changed.
Gameplay appearance and Adreno frame-time costs still require live acceptance.
