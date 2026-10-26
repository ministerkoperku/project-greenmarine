# Studio 0.1.4 follow-up evidence

## Root cause and correction

The original painter compared GLFW button numbers (left 0, right 1), but Minecraft
26.3 uses SDL button ids (left 1, right 3). A real primary click was routed to pan.
Previous probes bypassed standard widget dispatch and repeated these wrong ids.
CodeEditBox also used GLFW key codes, breaking SDL Tab/Enter and suggestion keys.
Both now use InputConstants. A real SDL LEFT test reproduced the old failure at
/tmp/gm-ui-20261025-1/evidence-sdl-red/probe-result.json. The separate old virtual
sequence also failed at evidence-virtual-red2. The first exploratory probe run was
invalidated by concurrent Gradle class output cleanup; it was discarded and rerun
sequentially before these accepted reproductions.

Pen mode supports tap/release, move, tap to finish without requiring a held virtual
button. Drag and Pan are retained. Painter canvas is maximized without a permanent
sidebar. Shader source occupies most of its window by default, with secondary
controls in Tools. UI size uses saved Minecraft GUI scale, so rendering and pointer
coordinates agree. Import provides ZIP/folder browsing with Documents/Downloads,
and accepts a single enclosing archive directory or selection of shaders itself.
Pack ZIP exports every project file with optional in-archive draft overlay.

## Verification

- `./gradlew -p development-tools test build -PgreenmarineJar=/root/Downloads/project-greenmarine-development/build/libs/greenmarine-0.9.11-ui.1.jar`: passed, 69 tests, 3 optional tests skipped, zero failures/errors.
- Vulkan GUI probe `/tmp/gm-ui-20261025-1/evidence-followup-green/probe-result.json`: passed. Real MouseHandler SDL primary click/release/move paints; drag/pan/zoom and PNG+JSON roundtrip pass. SDL Enter and Tab, suggestion insertion via real Screen.mouseClicked, and focus preservation pass.
- Actual UI-scale application enlarges the workspace. Widgets checked at 320x180,
  360x240, 480x270 and 854x480. The source viewport exceeds half the screen height.
- Browser imports a wrapped Iris ZIP with its license preserved; whole-project ZIP
  export contains manifest, converted shader, preserved originals and report.
- Unit tests additionally preserve texture bytes and includes, overlay only the ZIP
  draft, reject traversal/links and retain an old export after a rejected operation.
- Independent review confirmed SDL ids, focus restoration and compact search reset;
  no important outstanding review issue was reported.
- GUI validation is Linux ARM64 lavapipe, including the virtual-touchpad event
  sequence. No physical Zalith session or world rendering is claimed.
- Only the addon changed in this follow-up. It requires the compatible GreenMarine
  renderer alongside it. No native library belongs in this addon; the previously
  exported greenmarine-0.9.11-ui.1.jar remains the Android-native renderer.

## Delivery

- `/storage/emulated/0/Documents/koper/recents/greenmarine-development-tools-0.1.4-development.jar`
- SHA-256: `d895398c488b85cd3df6adc26f66c20cb6167c61e37f6cb2066b0dc1faf0f996`
- Export bytes match the built artifact. Probe classes are excluded.
