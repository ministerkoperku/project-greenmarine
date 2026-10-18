# Poco F8 Pro / Zalith owner field report

This records owner observations separately from automated build/native tests.
No product code or installed game files changed during this screenshot audit.

Follow-up: the owner supplied the failing session's compressed log and
confirmed the with/without-Sodium comparison. The material-replay integration
hypothesis below is now confirmed by the stack trace. See
[log analysis](2026-10-17-sodium-log-analysis.md).

## Working configuration reported by the owner

- Device: Poco F8 Pro; launcher: Zalith.
- GreenMarine with Fabric API and the owner's Koper mods works together.
- The owner reports testing Solstice RT, hardware RT loading, working shadows
  and approximately 60 FPS with a low chunk-generation setting.
- Exact mod versions, render resolution, ray mode, driver and scene were not
  supplied. This is a successful user field report, not a controlled benchmark
  or blanket compatibility claim for every Koper module or GPU.

## Screenshot inspected

Original file:
`/storage/emulated/0/Pictures/Screenshots/Screenshot_2026-10-17-19-29-05-839_com.movtery.zalithlauncher.v2.jpg`.

The owner associates this failure with adding Sodium. Visible chat:

```text
LOADED: greenmarine:radiance — Portable quality profile
FALLBACK: greenmarine:seabreeze — Portable quality profile;
portable shader fallback (hardware dispatch inactive);
Hardware pipeline initialized; waiting for a world scene
FAILED: greenmarine:solstice_rt — Rendering failed:
Render pass color attachment count must match pipeline color target state count.
```

The overlay shows 121 FPS in this screenshot while Solstice RT has failed.
That value must not be attributed to the owner's separate working RT test.
The hardware initialization message does not itself prove a ray dispatch in
the displayed frame; it is consistent with initialization preceding scene
readiness. It does not invalidate the owner's earlier working observation.

## Diagnosis from code

This error describes render-target attachment counts, not RGB values, color
grading, or general phone GPU incompatibility. Solstice's terrain material
replay opens a pass with two color attachments: normal/depth (RGBA32F) and
albedo/material (RGBA16F), in `SurfaceScene.begin`.

`WorldPrograms.captureMaterials` replays the chunk groups into that pass.
`WorldPrograms.select` only knows host definitions collected from Minecraft's
registered required/optional pipelines. When a compiled pipeline's name is
unknown, selection returns that original pipeline unchanged. A foreign
terrain pipeline with a different color target count can therefore reach
the two-attachment replay and trigger exactly this validation error.

Sodium's separate terrain pipeline makes this the leading integration
hypothesis. The screenshot does not identify the actual offending pipeline
or stack trace, so it is not yet a confirmed Sodium-version-specific cause.
Fixing color constants or relaxing GPU validation would not address it.

Next evidence needed: the complete stack trace from the failing session's
`latest.log`, Sodium version, and whether the identical Solstice configuration
works without Sodium. Requested export location:
`/storage/emulated/0/Documents/Koper/recents/latest.log`.

A proper integration must retain Sodium's terrain layout/submission and
provide compatible material capture, or explicitly report that capture as
unavailable. Silently skipping it and reporting full Solstice RT support is
not acceptable. No speculative workaround was shipped from this audit.
