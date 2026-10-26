# Sidebar and split-divider drag fix — 0.3.1-workspace.1

## Owner report and evidence boundary

The owner reported the game closing while dragging the workspace sidebar and
provided https://mclo.gs/Lh8rkw8. The raw client log was retrieved from
https://api.mclo.gs/1/raw/Lh8rkw8. It identifies addon 0.3.0-workspace.1 on Zalith,
Minecraft 26.3 and Java 25; it ends after world save/pause without a crash stack,
native signal or out-of-memory report. No definitive Android termination cause
can be extracted from that log. A separate crash report was requested without
blocking investigation.

## Confirmed defect

Both sidebar and split-divider mouseDragged paths called rebuildWidgets() for
every pointer movement. This destroys the active CodeEditBox and clears/restores
its focus each time. CodeEditBox.setFocused forwards every transition to
Minecraft.onTextInputFocusChange, which reaches the platform text-input manager.
That repeatedly restarts native text input during an ordinary resize and rebuilds
all project widgets rather than updating geometry.

The new GUI regression failed on the shipped implementation with:
`Sidebar drag rebuilt the focused editor during pointer movement`.

## Repair

- Existing code, image and color widgets are resized in place; tabs and inline
  entry widths are repositioned without rebuilding the screen or changing focus.
- MultilineTextField has immutable wrapping width. CodeEditBox rewraps that internal
  field while retaining the same focused outer widget, source, character limit,
  caret and selection. Listeners are attached after restoring content to avoid
  synthetic edit notifications during geometry updates.
- Left-button guards restrict drag capture, movement and release. Sidebar width
  remains bounded and its saved width survives split resizing with Explorer hidden.
- No renderer or native engine changes; the fix ships only in the developer addon.

## Verification

- Red GUI report: `/tmp/gm-workspace-20261026/sidebar-red/workspace-result.json`.
- Initial green sidebar report: `/tmp/gm-workspace-20261026/sidebar-green/workspace-result.json`.
- Final GUI report: `/tmp/gm-workspace-20261026/sidebar-final/workspace-result.json`: passed.
- Final GUI exercises 40 sidebar movements and 20 split-divider movements, stable
  editor identities/focus, draft/caret/selection preservation, release cleanup,
  source synchronization, inline operations, PNG painting and material sliders.
- The GUI fixture uses Linux ARM64 lavapipe Vulkan with bounded heaps and threads;
  it does not reproduce the owner's complete Android modpack or prove the exact
  Android crash cause. Physical Zalith acceptance remains with the owner.
- Independent focused review found no concrete P1/P2 issue in the resize fix.

## Release verification and delivery

- Full addon test/build passed; 97 tests, zero failures/errors, three optional skips.
- git diff --check passed. Exported addon bytes match the build output.
- Destination: `/storage/emulated/0/Documents/koper/recents/greenmarine-development-tools-0.3.1-workspace.1.jar`
- SHA-256: `450232cd9371ec2ba2e1208117ec453271a7d082a5efce9b21cdd0e4cccd9d28`
