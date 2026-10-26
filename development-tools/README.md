# GreenMarine Development Tools

Optional authoring addon for ordinary GreenMarine0.9.11-prototype+, Minecraft26.3,
Java25 and Fabric Loader0.19.5+. Fabric API and KoperLib are not required.
Install the addon beside one ordinary GreenMarine renderer jar. Removing the
addon removes its tools without replacing the renderer or changing world saves.
The addon contains no GreenMarine native engine and does not bundle GreenMarine.

## Branch rule

**Do not merge main-development into main.** Shared renderer commits belong on
main first; synchronize main into main-development. Development-specific source
lives here, in its own mod, with its own version. Keep normal renderer fixes
separate from editor/compiler commits. README on this branch documents the
unpublished local0.9.10 baseline; identical main/development history is not yet
claimed for those pre-existing local changes.

## Open the workbench

Press F9 in the world, or F6 → Dev tools (touch accessible). Tools do not turn on
profiling or switch shaderpacks merely by opening them.

- New shaderpack project opens the SDK wizard: choose a namespace, display name and
  fullscreen, compute or world starter. Each project gets its own directory.
- Open shaderpack project opens source files in a shared project session.
- Import Iris pack preserves sources and creates an explicit compatibility report.
- Settings contains auto reload, verbose debug, the optional Slang path and UI size.
- Material studio opens the palette painter.
- Diagnostics reports renderer/pass readiness and separately measured CPU/GPU costs.

## Shaderpack projects and the embedded SDK

The workspace uses a default left explorer, document tabs, a compact menu bar and
one large editing area. `+F` in the explorer creates a file; `+D` creates a folder.
Type the name directly in the explorer and press Enter. Escape cancels. Paths are
relative to the selected folder; nested paths work. The extension chooses a source
template; `.png` creates a transparent 32×32 texture. SDK templates remain available
under Create when a more detailed starter is useful.

Click a file to open it or a folder to expand it. Double-click either to rename it
inline, or use F2. Folder rename preserves open documents, unsaved PNG/text edits
and caret positions. Empty folders are visible. File contains asset import,
rename, two-step deletion and whole-project ZIP export.

View splits the editor right or down, or returns to one group. Drag the explorer
edge or the divider between editor groups to resize them. Opening a tab or explorer
file targets the active group. Two views of one source share edits. Resizing updates the existing widgets and
retains text input focus, caret and selection. Narrow windows
use a horizontal split when two code columns would leave too little room.

PNG files open as editable canvases in the workspace. Enter a brush color as
`#RRGGBB` or `#AARRGGBB`, click/move/click to paint with a virtual mouse, right-click
to pick a color, and use Undo, Erase or brush size in the canvas toolbar. Wheel zooms
around the selected area. Ctrl+Z undoes a stroke; E toggles the eraser when the
canvas has focus. PNG drafts join Save and ZIP export and are checked for external
file changes before replacement. The painter supports PNG textures up to 256×256.

Open documents have tabs. Draft text, PNG pixels and caret positions survive switching files
and navigating through the SDK. Dirty projects are retained when clean sessions
are evicted. Drafts are session memory until Save or ZIP export; restarting the
process does not promise unsaved-draft recovery.

SDK opens an offline searchable catalog generated from the current renderer
checkout: documentation, every engine GLSL include, public API source and code
examples. Java-only APIs are explicitly labeled reference; they are not executed
by shaderpack scripts. The authoring reference covers the current schema,
parameters, scene/lightmap/compute passes, named inputs, resources/images/buffers,
world programs, surface/ray flags, histories, Lua, materials and SPIR-V/Slang limits.
SDK templates can be inserted at the caret or copied. New file offers dedicated
scene, lightmap, compute, include, vertex/world, Lua, material and manifest templates.

Check validates every project manifest and declared shader stage against the
snapshot of all unsaved documents, including unsaved includes and manifests.
It validates bounded Lua initialization without ticking scripts, material masks
and SPIR-V, and checks external loader resource/count/byte limits. Problems identify
the file and line; the issue strip cycles through them. Save performs this preflight,
checks externally changed dirty sources, saves all documents transactionally and
reloads resources. Runtime renderer status is still the authority for GPU compatibility.

## Workspace size and virtual mouse

UI size is available in the workbench, painter, source editor and settings. It changes
Minecraft's saved GUI scale: 1x gives the smallest controls and the most workspace;
Auto and higher factors enlarge controls. Pointer coordinates use the game's own
scale. This affects other Minecraft menus as well.

The workspace keeps menus at the top, tabs immediately below, and status/problems
at the bottom. Check validates the project; Save saves every changed document and
reloads resources. Ctrl+N creates a file, Ctrl+S saves all, Ctrl+Enter checks the
project and Ctrl+\ splits the editor. SDK is under Help (also Tools on compact
windows). Tools retains standalone compilation, source-copy and SPIR-V export.
Interface scale is under View.

The material workspace has a large mask canvas and a persistent scrollable
inspector with the target texture, surface preset, mask regions and sliders for
roughness, reflection and emission. Preview switches the canvas to an illustrative
lit cube that responds to the sliders. This CPU preview is not in-world shader or
hardware RT acceptance. The original texture is preserved; the mask assigns
surface properties. Pen/Drag/Pan, erase, undo, wheel zoom, mask/albedo views, saved
mask loading and explicit resource-pack export remain available. Resize and source
override are farther down the inspector. Scroll over the inspector to reach them.

## Shader editor and compilers

The authoring workspace uses a neutral dark theme, restrained blue accents, compact
menus and flat explorer rows. The editor area occupies the main window.

The editor keeps unsaved session drafts and has line numbers, GLSL syntax colors,
auto-indent (Enter keeps indentation, `{` indents, `}` dedents) and Tab as four spaces.
Suggestions open automatically next to the caret while typing. They cover GLSL
functions/types/keywords, project identifiers and includes, GreenMarine frame/binding
names, schema JSON keys/enums and the Lua authoring API. Up/Down selects; Tab or
Enter inserts; Escape dismisses the popup without leaving the editor. Clicking a
popup row with a virtual mouse inserts it and keeps text focus. There is no separate
suggestion button. Function insertions place the caret inside parentheses.
This is lexical/project assistance with offline references, not a semantic language
server or a claim that every API token is legal in every shader stage.

Compiler errors and warnings mark source lines. Include errors map back to the
include directive. Standalone compilation and binary export are in Tools;
project-wide Check/Save use every referenced stage, including compute (.csh).

GLSL compiles through Minecraft's bundled LWJGL Shaderc to Vulkan SPIR-V, including
vertex, fragment and compute stages. GLSL330 version lines are normalized for
Vulkan validation; the editable source remains unchanged. Imports use bounded
include resolution. Standalone compilation does not emulate every Renderpearl
source transform or runtime define.

Slang compilation needs an explicitly configured executable for the current
platform, in config/greenmarine-development.json:

```json
{"slangCompiler":"/absolute/path/to/slangc"}
```

The tool invokes the compiler without a shell, limits diagnostics and execution
time, and removes temporary files. No executable is downloaded automatically.
Without a compatible compiler, the editor reports Slang unavailable. A Linux
binary is not an Android/Bionic binary; Android Slang support is not claimed.
Single-source compilation is supported; a complete external Slang module/SDK
layout is not provided by this addon.

**Planned:** a Slang in-game test on desktop (real `slangc`, PC) is scheduled for when
the owner is back home. Until then Slang support is untested end to end.

Export SPIR-V opens the in-game path browser for a file name and destination you choose.
It does not automatically bind a binary to a pack. Slang compilation remains an optional compiler tool. Compute passes use the current
GreenMarine shaderpack API and require its initialized Vulkan compute path. Includes and Lua
can be inspected; standalone validation of those fragments may require a wrapper.

## Complete shaderpack export

Pack ZIP in the source editor exports the complete containing project. Choose a name
and destination with the file browser. ZIP entries start at the pack root and include
all manifests, shaders, includes, textures, scripts, licenses and project reports.
Include unsaved sources overlays every changed project document inside the archive;
it does not write drafts to the project. An unfinished draft may not compile.

Imported Iris projects also expose Export ZIP. Their archives include preserved
original sources and IMPORT-REPORT.md. Exporting does not turn an unsupported Iris
project into a runnable GreenMarine pack. Source-copy and SPIR-V export remain
separate actions in Tools. Exports are staged before replacing an existing ZIP.

## Material painter

Open Material / block lighting under Create. The canvas and inspector stay in the
same screen. Load texture previews the target albedo; adjust material sliders for
the selected mask region and paint that region over the texture. Preview offers a
lit-cube illustration; Paint returns to the mask canvas. Save and Save as write a
normal material resource pack. This format continues to use per-texture masks.

Enter the runtime target texture ID, such as mechanics_dream:textures/craftbot.png.
Load albedo finds a mounted texture or a Kodel-style fullpack asset in mod roots.
An optional separate source ID handles nonstandard mounted layouts. Preview loads
at most256×256 PNGs, without editing their source colors. New selects a bounded
canvas; Load opens this editor's previously saved mask. Set canvas dimensions to match
the source texture for precise per-texel painting.

Choose a mask region, set its surface and roughness/reflection/emission sliders, and paint.
Palette colors are metadata, not new albedo colors. White motifs can receive the
same material as their blue rectangle, or a separate palette profile. Erase
restores earlier layers/fallbacks. Undo, visible zoom buttons, touch Paint/Pan mode, mouse-wheel zoom, right-drag pan
and brush sizes are available;
unsaved drafts survive screen navigation within this game session.

Save pack writes editable PNG+JSON to the current destination, initially
resourcepacks/greenmarine-development-materials. Save as opens an in-game folder
browser with Parent, Game folder and Documents shortcuts, an editable directory path
and a pack-folder name or absolute path. Empty name uses the current folder.
Existing destinations require a second Save press to confirm. Later Save pack uses
the chosen destination, including after navigating away and restoring the session.
External exports do not automatically enter Minecraft's resource-pack list; copy
the pack into the instance's resourcepacks directory to enable it.
Enable that pack in Options → Resource Packs, above older material overrides,
then reload resources. Saving does not silently enable it. Generic definitions
layer through GreenMarine API1; per-shaderpack targeting remains editable in JSON.

## Auto reload and verbose debug

Development settings saves both options in config/greenmarine-development.json;
both default to off. Auto reload polls editable directory projects and this
editor's material pack every750ms, requires two identical bounded snapshots, and
validates declared shaders/materials before requesting a resource reload. Broken
GLSL or an invalid material definition leaves the current runtime snapshot in
place. The external file itself is not reverted. Deleting a referenced include
is rejected; removing a whole pack is allowed. Preserved Iris originals are not
mistaken for executable GreenMarine stages.

Clean editor files reopen from disk so external edits are visible. Only unsaved
session drafts are retained; a changed external version is reported when an
unsaved draft is restored. Auto reload does not promise every runtime pipeline
error can be diagnosed by source compilation alone.

Verbose debug logs renderer/ray readiness transitions, declared pass information,
compiler diagnostics and reload activity. Renderer profiling has a separate
explicit toggle in diagnostics. These tools do not dynamically enable Vulkan
validation layers and do not turn a selected-but-unavailable RT path into support.

## Iris source conversion

Choose Browse and import Iris ZIP / folder. The in-game picker lists ZIPs and
folders, has name filtering and shortcuts to shaderpacks, Downloads and Documents.
Clicking a ZIP starts import; for a directory, open it and choose Import this folder.
A single enclosing directory in an archive is unwrapped automatically. Selecting the
shaders directory itself selects its enclosing pack. Dropping a path or typing one
in the importer remains available.
The original archive stays untouched. A supported single fullscreen final or
composite effect produces an editable GLSL GreenMarine project. World programs,
multiple render targets, extra passes and unsupported uniforms are preserved for
manual work, with an IMPORT-REPORT.md and no misleading selectable manifest.
This is a first source converter, not a full Iris compatibility implementation.
See [Iris import](IRIS-IMPORT.md) for the precise supported subset and standalone
CLI. The CLI distribution runs outside Minecraft without installing the addon.

## Build

From the repository root:

```sh
./gradlew build -PandroidNative=true -PandroidLinker=/path/to/android-clang
./gradlew -p development-tools test build
```

The addon defaults to ../build/libs/greenmarine-0.9.11-prototype.jar. The workspace
rebuild was verified with `-PgreenmarineJar=/absolute/path/to/greenmarine-0.9.11-prototype-dhfix1.jar`. Override
with -PgreenmarineJar=/absolute/path/to/greenmarine.jar when needed. Build outputs
are separate: the root renderer jar and development-tools/build/libs addon jar.
Build a standalone CLI distribution with `./gradlew -p development-tools irisConverterDistribution`.
The generated launch script uses its packaged Java/LWJGL libraries.

Only the renderer needs an Android native engine; the addon uses the host JVM,
Minecraft's Shaderc and the ordinary GreenMarine API.

These tools do not implement Slang pack execution, renderer motion vectors,
temporal reconstruction, a new shaderpack render graph or measured phone budget
policies. Those tasks remain tracked separately in the development plan.

## Verification scope

The opt-in Vulkan test converts an Iris GLSL120 effect, executes the converted
GLSL through Renderpearl and checks every pixel against the expected color.
Run under a suitable Vulkan/SDL environment with -PdevelopmentGpuTest=true.
The probe source set (-PdevelopmentProbe=true) runs a separate test-only mod:
it checks GUI screens, source compilation/save/reload, external edit reopening,
material painting/export, touch zoom/pan and auto reload rejection/recovery.
Probe classes are excluded from the distributed addon jar. Final Android GPU/UI
acceptance remains separate from Linux ARM64 lavapipe validation.

Task breakdown: [development plan](../development/2026-10-24-development-tools-plan.md).
