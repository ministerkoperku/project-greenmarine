# Confirmed Sodium material-replay incompatibility

Evidence: owner-exported
`/storage/emulated/0/Documents/Koper/recents/2026-10-17-7.log.gz`,
session 19:28–19:29. The owner additionally confirms an A/B test: the same
GreenMarine setup works without Sodium and fails after adding Sodium.

## Versions and failure

- Minecraft 26.3; Sodium `0.9.3-alpha.1+mc26.3`.
- Solstice selection at 19:28:58: `quality=MEDIUM, rays=OFF` in this session.
- Failure: `Render pass color attachment count must match pipeline color
  target state count.`

Relevant stack, excluding unrelated session information:

```text
FrontendRenderPass.setPipeline(FrontendRenderPass.java:103)
DefaultChunkRenderer.render(DefaultChunkRenderer.java:126)
SodiumWorldRenderer.renderLayer(SodiumWorldRenderer.java:303)
SodiumWorldRenderer.drawChunkLayer(SodiumWorldRenderer.java:293)
SodiumChunkSection.renderGroup(SodiumChunkSection.java:40)
WorldPrograms.captureMaterials(WorldPrograms.java:162)
LevelRenderer.handler$...$greenmarine$classic(...)
```

This confirms the material-replay integration hypothesis from the screenshot
audit. `SurfaceScene.begin` creates two material outputs. The virtual
`chunks.renderGroup` dispatches to Sodium's replacement implementation, which
sets its ordinary compiled terrain pipeline. `WorldPrograms.select` recognizes
only registered vanilla host definitions and returns an unknown Sodium
pipeline unchanged. Renderpearl correctly rejects the incompatible color
attachment count before drawing.

Inspection of the matching Sodium release source confirms that its terrain
renderer uses Sodium shader interfaces, packed vertices, `u_Globals`, texture
bindings and region push constants. Redirecting it to vanilla Solstice shaders
or merely changing attachment counts would not provide correct rendering.

## Native device result

At 19:28:45 this log confirms successful borrowing of the real host Vulkan
context and initialization of the native RT pipeline. Enabled host features
include acceleration structures, ray queries and buffer device addresses.
The failing operation is a raster material-capture pipeline bind, not native
RT initialization. Render size in this session is 1630×750.

Because this session selected rays Off, it is not evidence of active RT
dispatch or RT frame rate. The owner's separate successful no-Sodium
Solstice test with shadows and approximately 60 FPS remains recorded in
`2026-10-17-poco-field-report.md`.

## Resolution boundary

GreenMarine currently supports the owner's successful no-Sodium configuration.
Full Sodium coexistence for Solstice requires compatible terrain/material
capture preserving Sodium's vertex format, uniforms, push constants and
submission, followed by a rendered Vulkan acceptance test on the phone.
A defensive fallback could prevent the pack failure, but would disable
surface-dependent effects; it must not be advertised as full Solstice RT
compatibility. No such fallback or unverified shader adapter was shipped in
this diagnostic update. The exported 0.8.1 JAR is unchanged.

Primary source inspected:
[Sodium matching release renderer](https://github.com/CaffeineMC/sodium/blob/mc26.3-0.9.3-alpha.1/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer.java).
