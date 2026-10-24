# Kender models: material follow-ups for the PBR work

Owner notes from the first GreenMarine + KoperLib field test (2026-10-24, Poco F8 Pro, Solstice RT), kept for
when LabPBR materials are finished. Everything else on physical objects looked right.

* **Mechanics Dream robots** (craftbot, mini_craftbot, refinebot, cookbot, bot_fabricator, bot_dispenser):
  the orange and grey parts are metal and should be visibly reflective; today they are not. The blue part on
  the craftbot is a screen and should read as glass (smoother, more reflective, not metal).
* Today the material is one class per model: the bots' block JSON says `"surface": "metal", "roughness": 0.35`
  (Kodel `kender` section, KoperLib `KodelBlockBook.parseSurface`), so the whole model is one metal, screen
  included. Per-texel materials need texture maps.
* Plan once LabPBR loads correctly on the GPU: ship `_s` (and optionally `_n`) maps next to the Kodel textures
  (`mechanics_dream` fullpack `textures/`), and let KoperLib's material capture read smoothness/F0 from them
  instead of the per-model class. Capture shader: `MAT_FRAG` in `koperlib/engine/koperlib-kender/src/vk/geo.rs`;
  the RT overlay that shades Kender pixels: `GMO_FRAG` in the same file. Both take class/roughness from a push
  constant today.
* "No reflectiveness" may also be the reflection dispatch: GM traces surface reflections by class and
  roughness from the capture; check that metal at roughness 0.35 is above its reflection cut-off, or lower the
  bots' roughness, before blaming the overlay.

KoperLib side of the bridge: `koperlib/development/2026-10-24-greenmarine-bridge.md`.
