# Workspace editor implementation plan

> Execute inline using executing-plans; the owner explicitly delegated design decisions and asked to stop questioning the layout.

**Goal:** Replace the authoring button maze with a compact VS Code style workspace inside Minecraft.
**Architecture:** Preserve the current Java/Fabric addon, shared project buffers, validators, SDK, compiler and renderer. Add bounded PNG documents and project folder operations. Rebuild the editor shell around a default explorer, tabs, two editor groups, top menus and a small status bar. Reuse the material export format with an always-visible inspector and illustrative material preview.
**Spec:** Owner messages in this session: VS Code (not Visual Studio), side file list, direct file/folder creation, double-click rename, editor dominates, split panes, image tools and usable materials. Owner delegated remaining choices.

## Constraints
- Minecraft 26.3, Java 25, ordinary GreenMarine plus optional development addon.
- Preserve existing source edits, drafts, runtime API and file format contracts.
- No network service or AI-image dependency; image creation means a local PNG painter.
- Export finished addon to /storage/emulated/0/Documents/koper/recents/ and compare bytes.
- Keep implementation English and never replace installed game jars.

## Approach
A native Minecraft workspace reuses existing editor/render APIs and works with the virtual mouse. An embedded web editor would add a browser runtime; separate modal tools retain the reported navigation problem. Choose the native workspace. Inline creation and rename share the explorer row; richer actions live in small dropdown menus. Default Explorer stays visible; narrow windows may collapse it. Use two resizable code groups with shared project buffers, a texture canvas with hexadecimal color and undo, and a material inspector with immediate preview. Rendering previews are illustrative, not proof of in-world RT.

## Review focus
- Directory rename must retain unsaved text/image drafts and carets.
- Empty folders remain visible; no overwrite/path escape/symlink writes.
- Split views of the same file stay synchronized without losing the active caret.
- Image edits survive navigation and join Save/ZIP with text drafts; external changes cause conflict.
- At short GUI sizes controls cannot overlap or steal the main editor viewport.

## Task 1: Project operations and image documents
- [x] Add failing tests for empty folders, directory rename with drafts, PNG paint/undo, save/export and external change conflicts.
- [x] Implement ShaderProject folder entries/createFolder/rename and TextureDocument.
- [x] Extend transactional workspace save and archive overlays to PNG drafts.
- [x] Run addon tests.

## Task 2: Workspace shell
- [x] Test EditorLayout viewport bounds for narrow and short windows and split orientations.
- [x] Replace ShaderEditorScreen layout with explorer, inline creation/rename, tabs, top dropdown menus, status, and resizable split groups.
- [x] Add PNG widget with painting, palette/color entry, undo and same project Save/ZIP.
- [x] Preserve SDK/check/compiler/export actions in menus and add standard keyboard shortcuts.

## Task 3: Material workflow
- [x] Preserve canvas/export capabilities; place texture and material settings in a persistent scrollable inspector.
- [x] Add material presets, sliders and immediate illustrative lit-surface preview beside the mask canvas.
- [x] Ensure virtual mouse strokes still work and invalid numeric input stays recoverable.

## Task 4: Verify and deliver
- [x] Run full addon test/build, GUI probe and inspect screenshots at multiple dimensions.
- [x] Independent focused review; address important findings and repeat affected checks.
- [x] Update README/evidence; build distinct addon version, export and compare hashes.

## Execution ledger
- Ruling: Work inside the existing linked worktree; do not create another worktree or commit the owner's unrelated edits.
- Ruling: Reuse the tested renderer JAR greenmarine-0.9.11-prototype-dhfix1.jar; deliver the addon separately.

- Task 1: complete. WorkspaceEditingTest covers folder creation/rename, unsaved carets, image identity, PNG stroke undo, ZIP overlays, transactional save and external conflict. Initial missing APIs failed compilation before implementation; completed addon suite passed.
- Task 2: implemented. EditorLayoutTest covers compact geometry. Native workspace menus/tree, inline creation/rename, two resizable code/image groups, keyboard shortcuts and PNG painter are wired.
- Task 3: implemented. Persistent scrollable material inspector, bounded sliders, illustrative lit cube, original material mask/resource-pack formats and virtual pen retained.
- Review: fixed ghost dropdown widgets after menu actions, canceled compilation on group switches, captured compile include path, stable image document identity and PNG overlays in the actual ZIP export screen.
- Ruling: use project-wide PNG snapshots for ZIP and transactional Save; source validation still validates shader/Lua/manifest documents while the PNG model bounds/encodes its own pixel edits.
- Verification: initial GUI route passed bounds at 320x180, 480x270, 854x480, inline creation/rename, shared source and image widgets. Improved SDL input test correctly failed with single-click wrongly entering rename; switched to InputConstants mouse buttons and forwarded pen motion through Screen. Final stricter GUI run passed (evidence-sdl-green).
- Recovery: Android killed Termux during one GUI run; source and previous successful build survived. No restart from scratch. Probe now caps heap/CPU count; Gradle and lavapipe use two workers/threads.

- Task 4: complete. Final build passed, 94 JUnit tests passed with three optional skips; SDL GUI probe passed; addon and three previews exported to recents with matching bytes. Evidence: 2026-10-26-workspace-editor-evidence.md. Worktree and user changes retained; no commit, merge or installation performed.
