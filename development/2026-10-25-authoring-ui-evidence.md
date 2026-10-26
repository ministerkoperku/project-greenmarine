# Authoring UI verification

## Delivered behavior

- Navy/teal flat controls, renderer navigation tabs and four creation cards.
- Material Canvas/Texture/Material tabs, scrolling short panels, explicit brush mode,
  coverage-aware preview, undo/zoom/pan and destination-aware session drafts.
- Prefix shader suggestions with builtin explanations, source identifiers, keyboard
  and touch insertion, separate entry-point snippet, and source-copy export.
- In-game path browser for shader source, SPIR-V and material pack destinations;
  editable paths, Android Documents shortcut and explicit replacement confirmation.

## Verification

- Root: `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath' ./gradlew test build -Pmod_version=0.9.11-ui.1 -PandroidNative=true -PandroidLinker=/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang`: passed, 282 tests, 7 optional tests skipped.
- Addon: `./gradlew -p development-tools test build -PgreenmarineJar=/root/Downloads/project-greenmarine-development/build/libs/greenmarine-0.9.11-ui.1.jar`: passed, 64 tests, 3 optional tests skipped.
- Rust: `cargo test --locked`: passed, 17 tests, 8 ignored.
- Vulkan GUI probe: `/tmp/gm-ui-20261025-1/evidence-final/probe-result.json`, outcome passed. Source completion, compile/save/reload, error-line navigation, thin mask stroke visibility, paint/pan/zoom, PNG+JSON roundtrip, custom folder export and auto reload rejection/recovery passed.
- Actual GUI widgets checked for viewport containment and intersections at 320x180,
  360x240, 480x270 and 854x480. Review findings about overlapping controls and main
  completion were corrected; independent re-review found no remaining material issue.
- Android native ELF verified by `tools/export-android-mod.py`. Base jar contains
  no editor/probe classes; addon has no bundled renderer/native engine or probe classes.
- Final addon build includes subsequent tooltip and word-wrap polish. Layout and
  interaction code follows the accepted GUI run. No installed game jar was replaced.

## Limits

The GUI run uses Linux ARM64 lavapipe Vulkan. Android launcher touch/IME and world
rendering were not exercised. Suggestions are lexical assistance, not a language
server. External pack exports need installation into resourcepacks before use.

## Export checksums

- `greenmarine-0.9.11-ui.1.jar`: `7e5c8f9f173978af2a2992300762f8396c47a0160eedf388f2252d87fde04017`
- `greenmarine-development-tools-0.1.3-development.jar`: `9c717abf9167419f7f5de0891459f040e0d89b7c39d4f0fdeb3dc5a2584911fe`
