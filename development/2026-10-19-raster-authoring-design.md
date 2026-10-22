# Raster defaults and external pack authoring

Owner intent: optimize GreenMarine, provide default raster shaders and improve
external authoring and public APIs, without comparison-project names in code.
Continue the standalone Vulkan-first renderer and existing schema/API contracts.

Ruling: SESSION-HANDOFF.md explicitly authorizes autonomous implementation and
testing in this checkout. Preserve branches, installed artifacts and owner settings.
No commits or publication. Interpret external as independently authored native
GreenMarine packs until the format-compatibility question is answered.

Deliver an external pack source API with independent resource resolution per pack,
transactional catalog publication and source-specific errors. Scan game-directory
`shaderpacks/greenmarine` for resource-layout directories and ZIPs at reload, never
per frame. Read bounded immutable snapshots; reject filesystem symlinks, unsafe ZIP paths,
duplicate entries and excessive files/bytes. External assets can use mounted game
includes as fallback but cannot override another pack's private resources.

Provide a raster-only default for new settings, retaining existing selections.
Reuse world shader contracts with an explicit raster variant which does not sample
ray outputs. Export a ZIP and a standard-library authoring CLI for a standalone
editable starter, manifest validation and deterministic packaging. Cache duplicate
resource reads within a pack reload and compile the include matcher once.

Acceptance: external directory/ZIP parity, isolation, removal and failed-reload
retention; bounds/path validation; repeated shader references read once; raster
manifest has no ray requests; starter sources pass actual catalog loading; Java
test/build and Rust tests. Report gameplay/GPU acceptance separately.

Feature parity is a larger ongoing program: cascaded raster shadows, PBR materials,
additional world stages, compute/storage, richer texture resources, profiles,
custom expressions and legacy-format translation need separately verified host
integration. Do not advertise them as implemented by this authoring milestone.
