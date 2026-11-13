# Image frame clear translation

Owner requested a bounded continuation: translate Iris operations into native
GreenMarine operations. Implement only image.clear per-frame zeroing in this turn.
Do not expand entity/hand/shadow support or regenerate the whole expensive pack.

- [x] Observe failing native manifest and converter resource-lowering tests.
- [x] Add optional clearEachFrame image metadata, default false for existing
  GreenMarine constructors/manifests. Preserve Iris true/false declarations.
- [x] Clear requested owned 1D/2D/3D images once before the first frame phase;
  retain existing allocation clear and persistent-image contents. Do not clear
  between phases or repeat on the same frame epoch.
- [x] Verify actual multiframe Vulkan image values, root/converter test/build
  and portable Rust checks. Record evidence and handoff, without release export.

Use existing test-driven and verification workflows inline. Native host image
zero clears already exist; no new Rust/backend implementation is needed.

## Verification

The native schema regression and resource-lowering regression both failed before
implementation. Actual Vulkan execution before the fix reported 94 wrong values
after four frames. After the fix, `runtime-green/image-clear-result.json` reports
four frames, 109 checked values and zero mismatches on RX 9070 XT/RADV. The probe
uses real BEGIN and COMPOSITE compute passes: per-frame images must hold 2, while
the persistent image holds 8. Every 3D slice and both 1D integer channels are
checked. Reentering the same frame after both passes preserves the actual 2D
texture, proving the once-per-epoch guard. No generated Eclipse assets were edited.

Evidence lives under `build/verification/image-clear-2026-11-05/` and adjacent
`image-clear-*.log` files. Root `./gradlew test build` and converter
`../gradlew test build` pass; `cargo test` passes 17 portable tests with 14
explicit skips. No Rust sources changed. Converter tests use the standard suite
without the optional expensive whole-Eclipse compilation, matching this bounded
task. The previous full Eclipse report remains historical until regeneration.

The converter emits each Iris image's clear flag as native `clearEachFrame`,
instead of reporting IMAGE_FRAME_CLEAR. Existing four/five/six-argument image
constructors and manifests retain persistent behavior. Newly allocated images
use their existing initialization clear; existing flagged images clear once
before SETUP/BEGIN and retain writes through later phases. Only owned images
are touched. Manual review checked allocation, retained images, viewport changes,
same-epoch reentry and reset paths. No agents or broad renderer work were needed.

Full Eclipse remains unfinished; entity/hand/shadow and other resource/state/
dimension requirements are outside this small task. No installed game JAR,
commit, publication or Android release export was changed/performed.
