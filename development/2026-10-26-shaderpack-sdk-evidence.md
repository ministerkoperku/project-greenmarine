# Shaderpack editor and SDK 0.2.0 verification

## Delivered

- Automatic caret completion popup; keyboard Up/Down, Enter/Tab and actual SDL mouse
  row insertion keep source focus. Escape dismisses the popup before closing the UI.
  GLSL core functions/types, current project identifiers/includes, GreenMarine
  bindings/helpers, JSON schema fields/enums and Lua API hints are included.
- Shared project document buffers and caret positions, tabs, navigable folder tree,
  full file manager, source creation templates, rename/move/delete and asset import.
  Dirty project sessions are never silently evicted. Drafts remain session memory
  until Save/export; process-restart recovery is not claimed.
- Offline SDK: current engine docs, all GLSL includes and public API source plus
  authoring reference and code snippets. Java-only API entries are labeled reference.
- SDK starter wizard: fullscreen (post effects/Lua/lightmap), compute (storage images
  and buffers with coherent raster path), world (terrain/sky/clouds). Raw JSON exposes
  the entire current pack schema without a restricted wizard-only subset.
- Project validation reads all dirty manifests/includes/stages, bounded Lua init,
  material masks and SPIR-V; reports document paths/lines and actual loader limits.
  Save preflights all changed documents and external edits before transactional writes.
- Whole-pack ZIP export can overlay every unsaved document without writing originals.

## Tests and GUI evidence

- `./gradlew -p development-tools test build -PgreenmarineJar=/root/Downloads/project-greenmarine-development/build/libs/greenmarine-0.9.11-ui.1.jar`: passed.
- 91 tests, zero failures/errors; 3 optional GPU/fixture tests skipped.
- SDK starters parse with the real schema; fullscreen/compute sources compile through
  Shaderc in unit tests. Multi-draft export preserves sources and asset bytes.
- Vulkan GUI `/tmp/gm-ui-20261025-1/evidence-sdk-final/probe-result.json`: passed.
  Actual typing displayed the caret popup; actual SDL row click inserted the function;
  Escape kept the editor open. Two documents retained independent text and carets.
- GUI exercised SDK compute creation and validation, world creation and validation
  against mounted Minecraft includes, SDK full-text search/reference reading,
  save/reload, Iris browser, complete ZIP, painter input and auto reload recovery.
- Visible widgets checked at 320x180, 360x240, 480x270 and 854x480.
- Independent SDK and project/validator reviews found draft eviction, popup Escape,
  empty-state handling, compact layout, resource origin and loader-limit issues.
  Corrections were applied before the accepted final test/GUI runs.
- Generated SDK resources are explicit dependencies of processResources and sourcesJar.

## Evidence boundaries

GUI runs used Linux ARM64 lavapipe Vulkan, including virtual-touchpad SDL events;
no physical Zalith or world gameplay acceptance is claimed. Shader preflight does
not establish GPU pipeline compatibility, every world/Sodium variant, hardware RT,
or a measured FPS budget. Completion is lexical/project assistance, not a semantic
language server. Existing renderer behavior was not modified in this SDK task.

The owner PC rendering reports are preserved separately in
`development/2026-10-26-owner-pc-rendering-bugs.md` for the other agent.

## Delivery

- `/storage/emulated/0/Documents/koper/recents/greenmarine-development-tools-0.2.0-development.jar`
- SHA-256 `d2044ec49248f993114208c7a11c99128d7731949a878b564bbc74cbd2271d2c`
- Export bytes match the built artifact; SDK data present, probe/native classes absent.
- Preview: `/storage/emulated/0/Documents/koper/recents/greenmarine-sdk-completion-0.2.0.png`
- Preview: `/storage/emulated/0/Documents/koper/recents/greenmarine-sdk-reference-0.2.0.png`
