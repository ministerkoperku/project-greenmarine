# Shaderpack SDK and project editor

The owner requests automatic suggestions at the caret while typing, full multi-file
shaderpack projects and an embedded SDK exposing the current GreenMarine pack API.
They previously requested autonomous execution; preserve that preference.

## Global Constraints

- Work in the existing linked main-development worktree. Preserve all prior changes.
- Minecraft 26.3 / Java 25, optional addon independent of renderer internals at runtime.
- Use InputConstants SDL keys/buttons. Support Zalith virtual-mouse clicks.
- Main code viewport remains large; suggestions appear automatically at caret.
- Support the actual complete schema through editable JSON, not a restricted wizard.
- SDK references must derive from current repository API/docs/includes. Clearly label
  Java-only APIs and unsupported runtime paths. No Eclipse or third-party assets.
- Preserve drafts across file switches. Validate all project manifests/resources,
  not only the open source. Export every dirty document into the ZIP without
  silently modifying project files. Bounds/path/link checks remain in force.
- Gradle invocations are parent-owned and sequential. No commits/pushes/merges.
- Final addon delivery: /storage/emulated/0/Documents/koper/recents, verify bytes.

## Task 1: Embedded SDK catalog and generators

Create ShaderSdk.java, bundled docs/includes/API-source catalog and tests. Public
package-local contract:
record Entry(String id,String title,String category,String text)
static List<Entry> entries(); static List<Entry> search(String query);
static Map<String,String> starter(String namespace,String name,String template);
static List<String> templates();
Paths in starter are project-relative assets/... plus SDK.md, no enclosing project
folder. templates should include fullscreen, compute and world (using known working
repo shaders). All generated manifests must parse with current ShaderPack. Bundle
all root docs/*.md, shaders/include/*.glsl and API Java sources for in-editor search.
Generate index at resource-processing time (classpath paths under greenmarine-sdk).
Docs/includes are real repository data; do not invent supported runtime features.
Include concise authoring reference for every schema field, enums/limits, MarineFrame,
Lua, compute/storage, world targets, histories, materials and SPIR-V/Slang limits.
Provide code templates/snippets for scene, lightmap, compute, includes, vertex, world,
Lua, material JSON and complete manifest. No other source file ownership.

## Task 2: Project model and validation

Add transactional file creation/rename/import/save-all; allow .csh, docs and manifest
editing. Per-project buffers preserve drafts/cursors. Validation resolves unsaved
includes/manifests, all declared stages (including compute), scripts and materials.
Export ZIP overlays every dirty file. Add behavior tests before implementation.

## Task 3: Automatic completion popup and SDK assistance

Keep GLSL completions, add SDK bindings/functions, cross-file symbols, resource/include
ids, JSON manifest keys/enums and Lua API assistance. Popup renders at caret without
using a separate suggestion button. Enter/Tab and pointer row clicks insert; Escape
hides. Popup input precedes underlying editor controls. Add GUI acceptance checks.

## Task 4: Project UI and embedded SDK browser

File tree/list, open-document tabs, new project wizard and new/rename/delete/import
file actions. Large default editor, Files/SDK/Validate/Save/Export controls. SDK browse,
search, reference reader and templates integrated. Keep old constructors/entrypoints
working and route workbench project actions through the new project flow.

## Task 5: Verification and delivery

Parent-owned full addon tests/build, Vulkan GUI multi-file/draft/popup/SDK/validation/
export acceptance. Independent reviews. Export verified addon 0.2.0-development and
preview images; retain compatibility with the provided Android-native renderer.
