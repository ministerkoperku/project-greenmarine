# Studio follow-up

Owner reports drawing still fails and the painter is hard to use. Earlier evidence
called Screen.mouseClicked/mouseDragged directly, bypassing MouseHandler and the
launcher input route. Treat that report as unresolved; do not use the earlier
preview fix as proof of a fix for this report.

Authorized scope: persistent adjustable UI size, substantially larger shader source
viewport, simpler Iris ZIP/directory selection, complete shaderpack export, and
painting input diagnosis/repair. Owner already requested autonomous work.

- Test the actual MouseHandler press/movement/release path, with input diagnostics.
- Put canvas in a standard focusable input widget; maximize painting space and
  keep Texture/Material tools out of the canvas. Add visible active tool/state.
- Expose Minecraft UI scale with immediate resize and save, reachable in the editors.
- Make a compact editor toolbar and a large default code viewport. Put language,
  stage, export and secondary actions on a separate Tools panel.
- Provide open-mode file browsing for Iris ZIPs/directories with shortcuts/search,
  and preserve clear compatibility reports.
- Export whole project ZIPs including manifests, shaders, includes, textures,
  scripts and optional current draft; retain source/SPIR-V exports separately.
- Test bounded/transactional ZIP export and browser selection. Build/GUI-check and
  deliver only changed addon alongside the compatible existing renderer.
