# Workspace editor 0.3.0 verification

## Delivered behavior

- Native Minecraft workspace with a default explorer, tabs, compact top menus and
  bottom status/problem navigation. Neutral dark theme; the code area is the main
  viewport. Existing SDK, validators, shader compiler and export actions remain.
- Explorer +F / +D creates a file/folder inline: name, Enter. Extensions choose the
  source template; PNG creates a transparent 32×32 texture. Empty folders are
  visible. Double-click or F2 renames inline; directory rename retains open drafts.
- Two editor groups split right or down, with draggable group/sidebar dividers.
  Source edits synchronize between views; opening documents targets the active group.
- PNG documents support ARGB color entry, virtual pen, color picking, erase, brush
  size, undo and wheel zoom. Session drafts join transactional Save and ZIP export;
  externally changed image files cause conflict rather than silent replacement.
- Material canvas has a persistent scrollable inspector, surface presets, mask
  regions and roughness/reflection/emission sliders. Preview draws an illustrative
  CPU-lit cube; material-mask PNG/JSON export formats stay unchanged.

## Verification

- Final full addon command:
  `./gradlew -p development-tools test build --no-daemon --max-workers=2
  -Dorg.gradle.jvmargs=-Xmx768m
  -PgreenmarineJar=/root/Downloads/project-greenmarine-development/build/libs/greenmarine-0.9.11-prototype-dhfix1.jar
  --console=plain`: BUILD SUCCESSFUL (44 seconds).
- JUnit: 97 tests, zero failures/errors, three optional GPU/fixture tests skipped
  (94 passed). Added project/image transaction, directory rename/draft, geometry,
  and material preview tests.
- Final Linux ARM64 lavapipe Vulkan GUI:
  `/tmp/gm-workspace-20261026/evidence-sdl-green/workspace-result.json`: passed.
  Checked window bounds at 320×180, 480×270, 854×480; exercised inline new file,
  empty folder, actual explorer double-click rename, split buffer synchronization,
  texture painting through Screen input, inspector sliders and preview screenshots.
- Stronger SDL input regression was red before using InputConstants mouse buttons:
  a single click incorrectly entered rename. Correct constants, image color-field
  input priority and explicit virtual-pen movement forwarding fixed the real screen
  route. The final probe verifies a moved pen changes intermediate pixels.
- Independent focused review: no unresolved P1/P2 findings after corrections to
  menu widget dismissal, asynchronous compilation on group switch, stable shared
  image identity, PNG draft overlays and PNG extension handling.
- git diff --check passed. Distribution contains the SDK index; probe classes and
  native engines are absent, as expected for the separate Java authoring addon.

## Recovery and limits

Android killed Termux during an intermediate GUI rerun. The source tree and prior
successful build survived. The final rerun used bounded heaps, two Gradle workers,
Java ActiveProcessorCount=2 and two lavapipe threads and passed. This does not
establish physical Android/Zalith acceptance or guarantee Android will retain a
background Termux process.

The cube preview is illustrative and does not execute the selected shaderpack or
claim in-world hardware RT behavior. Materials still use per-texture mask regions.
PNG dimensions are bounded to 256×256; unsaved documents remain session memory
until Save/export, without process-restart recovery. Existing renderer behavior
and unrelated owner edits were preserved. No installed game JAR was replaced.

## Delivery

All outputs were exported to Android shared storage and compared byte-for-byte
with their build/screenshot sources:

- `/storage/emulated/0/Documents/koper/recents/greenmarine-development-tools-0.3.0-workspace.1.jar`
- JAR SHA-256: `d65de07a16f8323ea17afbf9a5c2527a607f730d7e17761b344c8c93e6862410`
- `greenmarine-workspace-0.3.0.png`
- `greenmarine-materials-0.3.0.png`
- `greenmarine-textures-0.3.0.png`

Install the addon alongside one ordinary GreenMarine renderer JAR, replacing the
older development addon. The addon needs no separate native library. The verified
renderer fixture was greenmarine-0.9.11-prototype-dhfix1.jar.
