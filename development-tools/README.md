# GreenMarine Development Tools

Optional authoring addon for ordinary GreenMarine0.9.10+, Minecraft26.3,
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

- New editable shader pack creates a unique folder in shaderpacks/greenmarine.
- Edit directory shaderpacks lists bounded source files; ZIP packs stay read-only.
- Import Iris pack creates an editable project and an explicit compatibility report.
- Development settings contains auto reload, verbose debug, and the optional Slang path.
- Paint material masks opens the palette painter.
- Renderer/pass diagnostics show actual readiness, current source snapshot,
  declared pass scale/format/inputs, and separately measured CPU/GPU stage times.
  Declared pass data is not an actual allocation or GPU-time measurement.

## Shader editor and compilers

The editor keeps unsaved session drafts, supports multiline text, and shows
compiler diagnostics (full text in the Compile button tooltip). Compile never
writes files. Save + reload is enabled only for the exact successfully compiled
GLSL vertex/fragment candidate, or a validated JSON document. Failed compilation
keeps the previous file bytes. Actual runtime program status remains authoritative;
source compilation does not prove a pipeline or binding layout is compatible.
Select the created preview pack in F6 after resource reload to see its effect.

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

Export SPIR-V writes a content-named .spv under greenmarine-development/compiled.
It does not automatically bind a binary to a pack. Slang and compute compilation
are compiler tools, not a new compute shaderpack execution API. Includes and Lua
can be inspected; standalone validation of those fragments may require a wrapper.

## Material painter

Enter the runtime target texture ID, such as mechanics_dream:textures/craftbot.png.
Load albedo finds a mounted texture or a Kodel-style fullpack asset in mod roots.
An optional separate source ID handles nonstandard mounted layouts. Preview loads
at most256×256 PNGs, without editing their source colors. New selects a bounded
canvas; Load opens this editor's previously saved mask. Set canvas dimensions to match
the source texture for precise per-texel painting.

Choose a palette color, set surface/roughness/reflection/emission, and paint.
Palette colors are metadata, not new albedo colors. White motifs can receive the
same material as their blue rectangle, or a separate palette profile. Erase
restores earlier layers/fallbacks. Undo, visible zoom buttons, touch Paint/Pan mode, mouse-wheel zoom, right-drag pan
and brush sizes are available;
unsaved drafts survive screen navigation within this game session.

Save pack writes editable PNG+JSON to resourcepacks/greenmarine-development-materials.
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

Choose an Iris ZIP/directory in the importer (or drop one onto its screen).
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

The addon compiles against ../build/libs/greenmarine-0.9.10-prototype.jar. Override
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
