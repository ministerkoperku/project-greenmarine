# Owner PC rendering reports — 2026-10-26

The owner asked to record these reports for another agent. The current editor/SDK
agent must continue the interface work rather than implement renderer fixes.
These are current owner observations, not reproduced or diagnosed here.

## Reported configuration

- Owner's PC modpack.
- Vulkan backend, High shader setting.
- Distant Horizons LOD distance: 128.
- Normal Minecraft render distance: 10 chunks.
- Owner called the shader "soltrace" in the report; confirm the exact pack/version
  (possibly Solstice RT) before interpreting it as a specific implementation.
- GPU, driver, exact GreenMarine version and logs have not been supplied for this report.

## Bugs reported

1. While flying/moving upward, shadows started flickering.
2. Afterward, the top faces of blocks turned white.
3. After disabling Distant Horizons, its LOD chunks stayed visible on screen and
   moved with the camera.
4. Reloading/restarting the shaders and re-enabling the named shader did not
   immediately remove the lingering LOD imagery.
5. The owner thinks the lingering imagery disappeared after some time; timing and
   exact recovery condition are uncertain.
6. FPS was too low with the configuration above. No measured FPS/frame times yet.

## Evidence boundaries

Do not claim these problems are caused by editor changes, stale history, DH, a
particular GPU feature or a specific renderer until investigated. Obtain the PC
latest.log, mod versions/renderer stack, GPU/driver, exact shaderpack id, and a
repeatable reproduction. Preserve the owner's original modpack configuration.

The owner clarified: "save what I wrote somewhere in a file and continue the
interface; another agent will fix what I just described."
