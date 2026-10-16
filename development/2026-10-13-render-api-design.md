# Public rendering API, first implementation

The owner authorizes autonomous implementation from verified 0.3, preserving
packs and existing public contracts. This stage is additive and continues the
standalone Java 25 / Minecraft 26.3 Renderpearl renderer. Vulkan is primary;
OpenGL shares the exact host commands. Fabric API and KoperLib remain optional.
FSR4, hardware RT, raw native drawing and vanilla terrain replacement are out
of scope. Work stays inline without commits, branch/index or installed-jar changes.

## Architecture and alternatives

Use a client-only `MarineRenderer` registry of namespaced stage contributions.
A factory receives an owned `RenderSession` on the render thread and returns a
frame callback. Registrations survive world/resource reload; sessions do not.
The stages surround existing lighting/pack rendering before hand depth clears.
Factories/callbacks fail independently and are retried only after reset.

A native Vulkan executor would duplicate host synchronization and lack an
OpenGL fallback. Extending only the ordered pack list would not give mods a
geometry/material API. An additive host-backed session is the selected approach.

Sessions own typed texture, buffer and compiled program handles. Closing is
idempotent, use after close and cross-session bindings fail. Imported frame
textures are borrowed and never destroyed. Handles expose scoped Renderpearl
objects for advanced use; callers must obey host render-thread and lifetime
rules. Allocation/compilation failures clean partial resources. Resources and
compiled programs are reusable across frames; frame-sized textures resize only
when dimensions change. No implicit format fallback: unsupported allocations
fail the contribution rather than silently changing a program's contract.

Program definitions snapshot GLSL stages and includes and optional validated
fragment SPIR-V with mandatory GLSL fallback. Host pipeline declarations carry
vertex layouts, target formats, depth and blending. Material bindings must match
declared uniform names/types and supply every declared uniform before drawing.
Meshes validate vertex stride/count and support non-indexed triangle draws.

## Graph and frame

A reusable graph has named texture resources, one writer per resource, named
reads/writes and explicit pass dependencies. Compilation validates unknown
resources/dependencies, unproduced reads, feedback and cycles, then uses stable
topological order. Imported reads are ordered before their writer; owned reads
after their producer. A pass cannot sample its own output. Attachments must
have matching dimensions/aspects and match pipeline formats. Callback access
is checked against declared reads/writes. Host Renderpearl handles transitions;
the graph does not claim to implement Vulkan barriers itself. Graph textures
are cached across frames and recreated transactionally on resize. No transient
aliasing or history resources in this first release.

Frame context borrows scene color/depth/lightmap, snapshots projection/view,
camera position, time and depth convention. It computes camera-relative model
matrices using double subtraction before float conversion. Borrowed objects and
transient uniform uploads expire at the end of the callback.

## Consumer and acceptance

A public-API example rasterizes a mod-owned mesh into albedo, normal and depth
attachments, then shades/composites it with depth rejection against vanilla.
It uses two color attachments and a material uniform; it is an initial G-buffer
for contributed geometry, not a vanilla-world/PBR G-buffer. It is opt-in and
exercises the same exported API available to other mods.

Unit tests pin graph ordering/errors, immutable program data, material binding
validation, handle ownership/lifecycle, mesh bounds and large-coordinate math.
Runtime probes exercise actual Vulkan and OpenGL draws, depth occlusion,
resize/reload, registration cleanup and shader-failure isolation. CPU submission
and asynchronous GPU timestamps are reported separately after warmup. Compare
reused graph execution to first-use costs; do not claim whole-frame FPS gains.
Base/Fullpack Gradle builds, existing pack tests, Rust tests and packaging checks
remain acceptance requirements. Runtime results are separate from compilation.

## Final clarifications

Physical texture aliases among imports are rejected even when names differ.
Depth clears use 1 for LESS/LESS_EQUAL, otherwise 0; callers can record an
explicit clear for other conventions. Render-thread guards run before reset
mutates session references. Graph execution may submit commands recorded before
a callback failure and is not a transaction. Fixed-size graph textures remain
allocated across window resize. The shaderpack source bridge imports snapshots
and leaves binding initialization to the Java consumer.
