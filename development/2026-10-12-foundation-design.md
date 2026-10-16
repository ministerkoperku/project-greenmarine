# GreenMarine foundation design

Date: 2026-10-12. Status: owner approved the design and execution; implementation evidence is recorded in `PROGRESS.md`.

## Agreed intent

GreenMarine is an independent Minecraft rendering mod. It must operate without
KoperLib, Fullpack, Kender or Sodium installed. Optional Fullpack integration
must support authoring visual effects and controlling lighting. The owner
permits Fabric API or KoperLib Core dependencies if needed; this design chooses
Fabric Loader alone for the base mod.

The original concept remains in `../readme.txt`. The full direction includes
Java/native rendering, Rust `ash`, OptiFine-format shaderpacks, a dedicated
shader API, deep mod access, FSR 3/4, ray tracing and rendering optimization.
This design covers the first foundation milestone. It does not remove those
requirements or claim that the foundation delivers them.

## Initial target and structure

Proposed target: Minecraft 26.3, Java 25, Fabric Loader 0.19.5 and Fabric Loom
1.18.2, consistent with the inspected current workspace builds. Use a project
Gradle wrapper and a Java 25 toolchain, without a machine-specific Java path.
Initial native development verification runs on Linux x86_64. Other native
platforms remain unsupported until separately built and checked.

Proposed mod id: `greenmarine`; Java packages: `com.koper.greenmarine`.

| Component | Responsibility | Dependencies |
|---|---|---|
| Java base/client | Lifecycle, configuration, public effect/light data, diagnostics | Minecraft and Fabric Loader |
| Minecraft host adapter | Backend detection and borrowed Vulkan context access | Minecraft 26.3 renderpearl and narrowly scoped mixins/accessors |
| Native engine | Versioned C ABI, context validation and owned GPU resources | Rust and `ash` |
| Optional Fullpack adapter | Pack declarations, script extensions and reload lifecycle | Fullpack APIs as compile-only dependencies |
| Optional KFX adapter, later | Transfer resolved effect/light data | Effects/KFX as compile-only dependencies |

Optional integration classes stay separate from base entrypoints and public
base APIs. Use Fullpack's own `koperlib-fullpack-addon` and
`koperlib-fullpack-client-addon` discovery keys where applicable; these
entrypoints must be inert when Fullpack is absent. Do not nest or redistribute
KoperLib jars. Build the base mod independently of sibling build outputs;
an explicit integration build profile can compile adapters against local
Fullpack jars. Its resulting artifact still has no required Fullpack dependency.

## Java/native and GPU ownership

Use Panama FFM, with an explicit ABI version handshake and status codes. Catch
Rust panics at exported boundaries. Unsupported ABI, missing native library or
Vulkan load failure produces an explanatory diagnostic and disables the
affected path without breaking ordinary Minecraft rendering.

Minecraft owns its Vulkan instance, physical device, logical device, queues,
swapchain and frame command buffers. GreenMarine borrows these handles and
destroys only resources it created. Obtain the actual physical device used by
Minecraft. Describe both supported and enabled device features; do not select
the first enumerated GPU or use a feature merely because hardware supports it.

All host GPU access follows Minecraft's render-thread lifecycle. The initial
native context attachment validates host handles and capability metadata;
it performs no independent queue submission or swapchain replacement. Any
GPU recording added later requires an explicit insertion point, image layout
contract, submission order and retirement fences. Device loss, resize, resource
reload and shutdown must invalidate stale frame resources. Coexistence with
Kender needs shared queue synchronization and pass ordering to be verified.

## Effect and lighting contracts

Define a small base API independent of Fullpack types: namespaced effect ids,
validated light descriptions, lifecycle handles and capability reports. A
light description carries position, RGB color, radius and intensity. Reject
non-finite values, non-positive radius and negative intensity. Zero intensity
is allowed to disable a light. Support create/update/remove/clear operations
with state cleared on world changes and disconnect.

The first milestone establishes the contract and deterministic CPU state.
It does not claim that a registered light illuminates terrain. Scene lighting,
glow/bloom and shadowed lights are distinct renderer capabilities; report them
separately. Rendering light must not silently change vanilla gameplay light
levels, spawning rules or server state.

Fullpack support uses a dedicated `greenmarine_effect` content type and
`greenmarine` script namespace. Definitions are validated before replacing
the active registry on successful reload. A failed reload keeps the last
valid definitions. Authoring exists independently in GreenMarine resources;
Fullpack is an additional authoring path, not the only loader.

The inspected `FullpackAddons.effects` accepts one provider, already used by
KFX. GreenMarine must not register a competing provider, overwrite `koper.kfx`
or replace existing effect ids. Its adapter registers its own content and
script extensions. Existing `koper_lib:render/light` and legacy `KfxLight`
can feed a later optional KFX adapter; merely parsing these declarations is
not proof of a rendered light or full graph compatibility.

Pack declarations and server-triggered effects have different lifecycles.
Server scripts must not call client rendering code. Multiplayer spawn/update/
stop requires a later explicit payload contract and per-client capability
negotiation. GreenMarine may be present on a server for this optional adapter;
the ordinary client renderer must work on servers without GreenMarine.

## First milestone and acceptance

The first implementation stage produces:

1. A standalone Fabric artifact with base and client entrypoints, targeted
   metadata, Gradle wrapper, root-anchored local ignore rules and project
   contributor instructions.
2. An independent Rust native library, versioned FFM loader and diagnostics
   that distinguish library availability, backend and renderer capabilities.
3. A Minecraft 26.3 adapter that identifies the active backend and passes
   the actual Vulkan context to Rust without taking ownership of it.
4. Validated light data and handle lifecycle tests, with diagnostic state
   visible through logs. No implied terrain illumination.
5. Optional Fullpack declaration loading/reload integration and an example
   authoring fixture using the base effect/light data contract. Fullpack
   script execution and rendered effect control follow in the next milestone.

Verify Java compilation and artifact dependencies; run meaningful tests for
light validation/lifetime, optional integration isolation and ABI mismatch;
finish Rust changes with `cargo build --release` in this project's engine.
Compile the optional adapter against current Fullpack without altering its API.
No KoperLib API changes are planned.

Runtime acceptance separately checks Minecraft startup with only the base mod
and Loader, OpenGL behavior, Vulkan context attachment, clean shutdown, world
disconnect, and startup with Sodium and with KoperLib/Fullpack/KFX. Missing
runtime checks stay open; no FPS benefit, shader compatibility or light output
is claimed from compilation or CPU tests.

## Subsequent renderer milestones

After the foundation, establish one actual Vulkan render pass and explicit
resource barriers; then render scene lights/effects and expose pack/script
control. Preserve optional Sodium behavior through scoped adapters and explicit
ownership of overlapping optimizations. OptiFine-format compatibility requires
pack semantics, shader translation and pass integration, not just GLSL parsing.

FSR 3.1 has a documented Vulkan path. FSR 4.x needs a separate interop
investigation: AMD currently documents DirectX 12, while OptiScaler documents
Vulkan-to-DX12 interop. Retain FSR 4 as a requirement and distinguish native
Vulkan, interop and unsupported device/platform capabilities. Ray tracing
requires correctly enabled device features and scene acceleration structures;
it is not enabled by the foundation's capability query.

Technical evidence and primary links are recorded in `INITIAL-AUDIT.md`.
