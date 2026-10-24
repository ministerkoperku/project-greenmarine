# Development Tools verification

Implemented as an independent optional Fabric addon: the ordinary renderer's
source/build/native files match shared baseline0efb967. Original main checkout
and installed game jars were not modified. Main-development initially matched
remote main, then preserved the existing local0.9.10 common baseline separately.

## Checks

- Root clean dual-native `test build`:274 tests,7 skipped, zero failures/errors.
- Addon suite with Vulkan test enabled:48 tests, zero failures/errors/skips.
- Actual Shaderc compiles GLSL vertex/fragment/compute and bounded nested imports.
- Iris GPU test converts a GLSL120 effect, renders a2x2 known input through the
  actual Renderpearl Vulkan pipeline, and checks every output pixel51/51/51/255.
- Standalone converter distribution executes without Minecraft/Fabric. The
  unpacked launch script converted a real simple source fixture successfully.
- Isolated actual Minecraft26.3 client on Vulkan/lavapipe: touch launcher from
  ordinary settings, source editor compile/save/reload, catalog publication,
  clean reopening after external edits, actual craftbot fullpack preview,
  mouse paint, touch zoom/pan, PNG/JSON save/reopen, rejection of broken GLSL
  without catalog change, and successful auto reload after valid edits all pass.
- GUI screenshots wait for rendered frames, not only game ticks. Probe source
  and test classes are absent from the distributed addon jar.
- Addon jar contains no renderer classes, bundled GreenMarine or native engine.
  It uses the installed ordinary GreenMarine API and Minecraft's Shaderc.

## Review corrections

Independent review reproduced wrong-stage application, stale clean drafts,
symlink export escapes, partial material saves and incomplete SPIR-V acceptance.
Regression repairs cover stage/file policy, dirty-only drafts, transactional
confined material exports and stage-aware structural validation. A second review
caught deleted include bypasses, invalid material JSON and preserved Iris source
misclassification. Read-only ReloadValidator tests cover these without mutating
MaterialAddons generation.

## Limits

The GUI probe runs at the title/menu level, not in a rendered world. The Iris GPU
probe separately verifies the converted fullscreen shader's real pixel output.
Neither proves complete Iris shaderpack compatibility. Only the documented simple
fullscreen subset translates automatically; unsupported packs retain original
sources and a manual-conversion report without a misleading selectable manifest.
Slang uses a configured external executable; a real Slang compiler and Android
Slang compilation were not tested. Linux ARM64 lavapipe verification is separate
from final Android launcher/GPU/UI acceptance. The standalone distribution carries
Linux/glibc LWJGL natives; Android/Bionic CLI support is not claimed.

The wider material/HDR, shaderpack graph, language ABI, temporal, diagnostic and
mobile-budget renderer milestones remain open in the development plan.

## Delivery

Addon SHA256: `15c6eedbccbcdd63d79c96614f44f2425c2c3251438f7421cd0ad7095be5dea9`.
Standalone converter SHA256: `18a2de4d21cc4a29b9cf27162cd08ec25854d4d1eecf5200fd84c839cd06ff57`.
Exports are byte-identical to their project build outputs in Android shared
Documents/koper/recents. Existing installed mod jars were not replaced.
