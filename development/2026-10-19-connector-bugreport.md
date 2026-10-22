# Connector held-item rendering report

Reported by owner during gameplay on 2026-10-19. Holding Connector in the hand
makes terrain and shader effects disappear; some block faces become transparent.
Owner supplied this as a bug report only and asked to leave it alone if the cause
is Mechanics Dream. No reproduction, version/pack/settings capture or attribution
has been established. Do not claim a fix or change Mechanics Dream from this report.

For later diagnosis: compare empty hand/Connector at the same pose, with effects
enabled/disabled, and inspect render-state restoration at held-item submission.
This is an investigation lead, not a confirmed cause.

## 2026-10-20 owner follow-up and investigation

The owner now reports that the connector overlay itself works, but shader effects
stop while holding it. After putting it away, terrain disappears with tiny camera
movements. The owner compared without GreenMarine and reports no issue there.
This supersedes the original uncertain attribution as a user observation; the
full gameplay sequence has not been reproduced locally.

Read-only investigation of local Mechanics Dream/KoperLib sources confirms
connector attachment points and cables submit always-on-top gizmos. Minecraft
26.3 `LevelRenderer.executeAlwaysOnTop` clears its selected depth attachment to
zero. When `consistentDepthRequired` is false this is the main world depth;
when true Minecraft allocates a separate gizmo depth and integrates it afterwards.
`GameRenderer.renderLevel` derives that flag solely from vanilla post effects.
GreenMarine post effects run separately at `render3dHud` HEAD and had not declared
their depth requirement. Consequently gizmos could erase the exact depth consumed
by GreenMarine scene shading/history and later world-depth users.

Minimal correction: OR the host flag with GreenMarine's enabled state at the
single `LevelRenderer.render` invocation, argument 6. Do this before the frame
graph is constructed. Preserve any existing host/mod requirement and vanilla
behavior when GreenMarine is disabled. Let Minecraft own/manage attachments;
no changes to Mechanics Dream/KoperLib, no optional dependency, no native change.

Regression: missing-hook test failed with NoSuchMethodException before the fix;
flag matrix checks cover enabled/disabled/re-enabled and existing host effects.
An ASM contract test checks the hook selector/index against the actual 26.3
GameRenderer bytecode to catch targeting the wrong argument/overload. These are
integration-contract tests, not a live Android reproduction or visual acceptance.
