# LabPBR repair and material follow-ups

Goal: make resource-pack LabPBR inputs reach Solstice on Vulkan, preserve ordinary materials without maps, and deliver an Android-native build.

Architecture: retain the existing block-atlas-aligned material textures, shared Renderpearl upload/binding path, and shader/native ABIs. Diagnose CPU stitching, GPU upload and production GLSL decoding separately before choosing the repair. No sibling-mod API changes are planned.

Constraints: Minecraft 26.3, Java 25, optional Sodium/KoperLib, native ABI 1, MarineFrame 288 bytes. Work in the authorized checkout, preserving the owner's untracked Kender-material note; no commits, branch changes or installed-jar replacements.

## Tasks

- [x] Reproduce with generated stone `_s`/`_n` maps in a fresh development run, actual Minecraft atlas and production `labpbr.glsl`. Separately verify Renderpearl RGBA upload/readback on lavapipe.
- [x] Pin the identified failure in a regression test, repair its source, and verify the same fixture. Keep any material follow-ups bounded to the diagnosed path.
- [x] Cover map defaults, decode boundaries and shader compilation on Vulkan/OpenGL; verify resource reload if it affects the repair.
- [x] Run Java, Python and portable Rust suites, build both host and Bionic ARM64 natives, export with the guarded Android exporter, and compare delivered bytes.

Review focus: mixed mapped/unmapped blocks; resource reload; alpha/emission sentinels; atlas-edge UVs; resources released only after GPU use.

## Evidence

- Baseline `./gradlew test`: passed before production changes.
- Initial isolated Vulkan readback returns the exact uploaded RGBA channels, contradicting the historical assumption that all texture uploads read as zero. The first test expectation swapped red/blue; corrected from ARGB byte evidence before using the test as a regression.

- Root cause: Minecraft 26.3 TextureAtlasSprite stores gutter origins in getX/getY, but getU0/getV0 include padding. PbrAtlas copied to gutter origins. The actual-Minecraft Vulkan fixture found 1/1 maps yet decoded stone as absent. Repair: copy at rounded UV-origin times atlas dimensions.
- Bounded follow-ups: explicit per-texel map presence (valid black specular values must not mean missing; unmapped normals must preserve relief); use decoded dielectric F0; release CPU atlas images after immediate staging upload.
- Follow-up regression RED: `PbrUploadGpuTest.blackSpecularTexelsAreMaterialsOnlyWhereTheMapExists` executes production GLSL on Vulkan and rejects the all-black mapped texel as absent. Use a one-byte-per-atlas-texel R8 presence mask (bit 0 specular, bit 1 normal), preserving all LabPBR channels including normal alpha/height.

- Root repair GREEN: actual Minecraft Vulkan probe now returns present=255, roughness=16, metal=255 and raw specular (192,231,0,255). Before repair the same fixture returned absent and zero raw texels.
- Presence-mask GREEN: opt-in Vulkan upload and black-map decoding tests passed.

- Four opt-in Vulkan tests pass: upload after closing CPU image; mapped black vs absent map; dielectric/metal F0 and grazing Fresnel; unmapped normal/AO fallback.
- Initial actual-Minecraft mapped/removed/restored probe passed. Review found that fresh atlases did not test owner reset, so the probe now retains the real WorldPrograms PBR instance and relies on actual reset hooks across reloads; rerun required.

- Review: no Critical/Important production findings. Validation fix: a failed rerun must overwrite any stale passed result; probe writes running on entry and failed on exception, and clears prior error output. Verify with a deliberately invalid fixture and preseeded passed status.

- Final verification/delivery: see `2026-10-24-labpbr-evidence.md`. Actual owner/reset reload probe passed all three stages. Intentional invalid fixture correctly overwrote stale passed JSON with failed. Android JAR and Solstice ZIP exported to owner recents, hashes and packaged natives verified.
