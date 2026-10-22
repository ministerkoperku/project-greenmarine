# Shader settings

Open GreenMarine with F6 or its existing settings entrypoint. The settings screen
keeps its parent screen and returns to it with Done/Escape. Changes apply
immediately. Pack, quality, ray mode and profiling edits preserve all saved
per-pack parameter overrides.

Choose **Shader pack** for a paginated list of pack names, including **Shaders
off**. The selected row is marked. Tooltips expose the stable resource ID to
distinguish packs with identical names; a missing current pack stays visible as
Unavailable. Quality and ray modes have direct choice lists and descriptions.
The list preserves catalog order and does not silently replace a missing pack.

**Renderer status** shows requested rays, the policy-selected path, actual
Loaded/Loading/Fallback/Failed/Disabled state and full paginated reason text.
A selected Hardware path alone does not establish a successful ray dispatch.
The live header also shows runtime status, backend and path. Unsupported paths
continue using the renderer's existing fallback rules.

**Pack parameters** uses declaration order, readable names and sliders with
visible values. Tooltips include the original parameter key, range and default.
All parameters remain reachable through pagination. Reset restores this pack's
defaults while retaining other packs' overrides. Slider edits retain immediate
preview, rollback on errors and persistence when released or leaving the screen.

**Performance** enables profiling and displays separate pack CPU/GPU, world GPU,
world-copy CPU, surface-ray CPU/GPU and ray-scene CPU/GPU mean/p95 costs. Tooltips
include sample count, minimum and maximum. Reporting snapshots, sorting and label
formatting occur at most once per 500 ms while the page is visible, rather than
on every draw. A GUI rebuild can format its visible controls again. GPU timings
need warmup and host support; no samples is distinct from zero cost. Old samples
may remain when profiling is disabled. These overlapping stage costs must not
be added as a whole-frame total or interpreted as an FPS claim.

Controls use a single responsive column up to 460 GUI pixels wide and 28-pixel
heights at ordinary sizes. The same pagination policy applies to settings,
choices, metrics and sliders. Very short screens reduce controls to keep
navigation accessible. Pure tests cover 120/240/854 widths, 160/240/480 heights,
empty and large catalogs, stale page indexes, unavailable selections, readable
names and the reporting refresh interval. Compilation against Minecraft 26.3's
GuiGraphicsExtractor is verified separately from gameplay. Real Android touch,
keyboard narration and interactive visual acceptance still require a game client.
