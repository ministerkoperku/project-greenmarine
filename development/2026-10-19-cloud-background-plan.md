# Single procedural cloud background

Owner reports simultaneous vanilla/procedural clouds and a faint rectangular gap.
Confirmed: solstice_clouds shades host geometry while solSky also synthesizes cloud
layers. The scene shader fills only dark clear pixels, using an image-luminance
threshold; this cannot guarantee uniform sky coverage outside the host mesh.
No current screenshot of the rectangular symptom is available.

Implement one explicit background world-program stage, drawn after opaque depth
and before transparency, then copied into opaque refraction inputs. It draws a
fullscreen triangle at reversed-depth clear zero with EQUAL and no depth writes.
No input samples the active color/depth attachments. Pack uniforms are the same
initialized frame/surface/environment contracts. Existing packs keep their stages.
Solstice opts into the background program, suppresses its old sky/cloud fragment
layers and drops the scene's luminance-dependent refill. Do not change MC options
or affect other packs. Preserve ownership, actual formats, reload and failure paths.

TDD: require background target/vertex validation, assert the pipeline protects
opaque depth and retains color attachment contents, compile background shaders and
all old classic/OIT variants. Verify base Java/native checks, dual Android build,
native loading and guarded Android Documents export. Live cloud appearance remains
separate from compilation. Keep version 0.8.6 distinct from existing exports.

## Verification and result

Implemented `background` as an additive world-program target. Existing constructors
and stage ordinals are preserved. Solstice uses one new vertex/fragment pair;
Overworld host sky/cloud fragments are suppressed, and non-Overworld sky behavior
remains. The scene pass no longer refills pixels from a luminance threshold.
Background pipelines are cached per pack/format and closed on reset; borrowed
attachments retain contents and depth. Background draws contribute to actual
world submission status through the existing submission callback.

Dual-target `test build -PandroidNative=true` succeeds: 188 Java tests, zero
failures/errors, one skipped optional Sodium-resource test. Full Python suite:
52 passing tests; after the final dimension-preservation edit, the three Solstice
tests pass again, compiling all actual world/background/classic/OIT shader variants
for Vulkan and OpenGL. Rust has 10 passing tests and four explicit desktop GPU
tests ignored. Native sources are unchanged. Packaged Android/Bionic OpenJDK25
loading/FFM calls and close pass; both packaged native libraries match their
release outputs byte for byte. `git diff --check` passes.

Independent read-only review reports no Critical/Important defect. It verifies
the host closes the opaque pass before both injection points, no recursive world
pipeline replacement occurs, and all four background stage/backend compilations
pass. Opaque-depth EQUAL/no-write and actual-format behavior have direct Java tests.

No gameplay render was launched; the reported rectangular appearance remains
visually unverified. No installed jars, personal MC cloud options or owner settings
changed. Android delivery is the guarded 0.8.6 JAR in Documents/koper/recents,
verified by matching SHA256 against the build output.
