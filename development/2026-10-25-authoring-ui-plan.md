# Authoring UI rebuild

The owner requested autonomous implementation, modern visuals, working painting,
shader writing assistance and free choice of save destination.

Use the existing Minecraft/Renderpearl widgets and optional addon. Keep the existing
linked development worktree and preserve the dirty main renderer checkout. No web UI
or new runtime dependency. Shared renderer UI changes must also be applied to main.

1. Reproduce invisible thin mask strokes in a reduced preview; test arbitrary material
   pack destinations and prefix-aware shader completions.
2. Add a dark navy/teal UI theme, flat accessible buttons, persistent workbench tabs
   and visible explanations. Retain import, diagnostics and all renderer controls.
3. Rebuild painter with separate Canvas / Texture / Material panels so small screens
   do not overlap controls. Show selected brush and material; preserve strokes across
   panels and navigation. Use coverage-aware previews, undo, pan and zoom.
4. Add an in-game file browser with editable paths, parent navigation, shortcuts,
   filenames and explicit overwrite confirmation. Use it for material pack exports,
   shader source copies and SPIR-V export.
5. Add documented lexical shader completions with local identifiers and function help,
   keyboard selection plus touch controls. Retain compile-before-apply validation.
6. Run Java suites/builds, Rust suite, Vulkan GUI probe including actual preview
   visibility and file browser interaction. Export verified addon and Android-native
   renderer into /storage/emulated/0/Documents/koper/recents. Do not install game jars.

Limitations: completion is lexical, not a GLSL language server. Linux Vulkan GUI
acceptance does not establish launcher-specific Android touch/IME behavior.
