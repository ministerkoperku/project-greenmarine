# Public rendering API (0.9 prototype)

GreenMarine supplies a client-only rendering layer in
`com.koper.greenmarine.api.render`. It uses Minecraft 26.3 Renderpearl on the
actual host device: Vulkan is primary and OpenGL shares the same executor.
Fabric Loader is required; Fabric API, KoperLib and Fullpack are optional.
The existing EFFECTS/LIGHTS/PACKS APIs and schema-v1 packs remain supported.
This is an evolving prototype API, not Veil/Iris API or pack compatibility.

## 0.9 additions

Graph authors can explicitly declare `transientTexture` resources whose equal-spec,
nonoverlapping lifetimes share owned storage. Existing `texture` declarations keep
dedicated persistent storage. The immutable `allocationPlan()` exposes actual slot
counts and logical lifetimes. See [allocation and ownership](GRAPH-ALLOCATION.md).

Indexed/instanced contribution drawing reduces duplicated vertices and individual
submission calls for consumers that adopt it. It does not automatically batch
Minecraft's entities, add player geometry to RT, or implement compute. The new
[settings UI](SETTINGS-UI.md) distinguishes requested ray policy, actual readiness,
and stage CPU/GPU measurements. Profiling toggles preserve live rendering resources.

## Register a renderer

Register from your mod's client entrypoint and retain the returned lease:

```java
MarineRenderer.Registration rendering = MarineRenderer.register(
    "yourmod:geometry", MarineRenderer.Stage.BEFORE_LIGHTING,
    session -> new GeometryExample(session,
        frame -> frame.modelAt(worldX, worldY, worldZ), false));
```

`GeometryExample` is a working exported consumer in
`com.koper.greenmarine.example`, showing mesh creation, two color attachments,
material uniforms, a depth attachment and a shading/composition pass. Pass
world coordinates for a fixed object. The runtime fixture supplies a matrix
that holds the cube in front of the camera for controlled depth comparisons.
Registration alone enables a contribution; pack selection and F8 do not toggle
independent mod rendering. Close your lease when removing the contribution.

Factories run lazily on the render thread. They receive a fresh owned
`RenderSession` after world changes and resource reloads; registrations survive
both. Build reusable meshes, programs and graphs in the factory, then update
uniforms and execute them from its frame callback. Factory/callback failures
close that contribution's session and disable it until the next world/resource
reset; other contributions and the existing pack renderer continue.

Stages run in registration order:

| Stage | Placement |
| --- | --- |
| `BEFORE_LIGHTING` | After vanilla world rendering, before GreenMarine lights and packs |
| `BEFORE_PACKS` | After GreenMarine point lights, before pack scene passes |
| `AFTER_PACKS` | After pack scene passes, before the hand clears world depth |

These are contribution hooks, not replacement hooks for vanilla terrain,
entities or transparent geometry. Pack passes may modify the color of geometry
submitted before them. Contributions that composite without updating scene
depth do not automatically become part of later depth-based pack lighting.

`FrameContext` provides `sceneColor`, `sceneDepth`, `lightmap`, dimensions,
projection, view rotation, simulation time and the host clip-depth convention.
Projection/view getters return copies. `modelAt` subtracts the camera position
in double precision before converting to a float translation, avoiding world
coordinate cancellation. Combine model transforms before drawing. Minecraft
uses reverse depth on the tested backends: near values are larger and cleared
world depth is zero. A custom forward-depth program is also supported.

## Own resources explicitly

`RenderSession.texture(label, TextureSpec, frameWidth, frameHeight)` creates a
single-mip 2D renderable/sampleable texture and view. Choose an explicit
`GpuFormat`; unsupported allocations fail with no silent format substitution.
`TextureSpec.fixed` and `.scaled` support fixed and frame-relative sizes;
relative dimensions round up, with a minimum of one pixel.

`session.buffer(label, usage, data)` snapshots the remaining input bytes into a
GPU buffer. `session.write(encoder, handle, offset, data)` validates owner,
copy-destination usage and write bounds. `session.mesh(label, vertexFormat,
vertexCount, data)` requires exactly the declared non-indexed triangle vertices
and matching byte stride. Draws use vertex binding zero. Indexed triangles and
instanced draws are available through `indexedMesh` and the extended pass draw
overload; see [indexed/instanced meshes](INDEXED-MESHES.md). Multiple vertex
bindings and general compute dispatch remain outside this API. Advanced callers
can use host objects under the same ownership rules.

Texture, buffer and program handles implement idempotent `close`. The session
closes every remaining owned object. Cross-session binding and use after close
fail. Creation, compilation, use, closure and `MarineRenderer.reset` require the
render thread; registration and lease closure can run on other threads. Lease
closure defers GPU cleanup to the next rendering hook or world/resource reset.
Do not close the borrowed host device, frame textures, transient slices or raw
objects exposed by another owner. Session closure never destroys frame imports.
Raw Renderpearl objects are an advanced escape hatch: retaining or destroying
them can bypass handle guards, so their host lifetime rules still apply.

## Compile shaders and bind materials

`ShaderProgram` combines a host `RenderPipeline` with immutable maps of GLSL
sources, include sources and optional validated fragment `ShaderBinary` objects.
Use namespaced `Identifier` overloads in the pipeline builder. Declare vertex
layouts, color target formats, depth state, topology and uniform bindings with
Renderpearl's types. GLSL is mandatory for both vertex and fragment stages.
Binary fragments are used on Vulkan when baseline capabilities and reflection
succeed; OpenGL uses the GLSL fallback. Includes and program source snapshots
are independent of future resource reloads. First-use compilation is synchronous
and can hitch; compile once in the session factory, not every frame.

`session.compile(definition)` returns a reusable `ProgramHandle`. Inside a graph
pass, create `ctx.material(program)` and supply each declared uniform:

```java
var material = ctx.material(program)
    .uniform("GeometryFrame", matrices)
    .uniform("Surface", materialBytes);
ctx.draw(material, List.of("albedo", "normal"), "meshDepth", mesh, true);
```

ByteBuffer uniforms contain whole 16-byte std140 slots and upload through host
transient memory. Their backing bytes can be reused after the call. Owned uniform
buffers are also accepted. Sampled textures use graph resource names:
`material.texture("Albedo", "albedo", FilterMode.NEAREST)`. Names and binding
types must match the pipeline; all declared bindings are required before drawing.
Materials and pass contexts expire after the pass callback. Frame contexts
expire after their contribution callback. Do not retain transient bindings.

Draw validates attachment count, actual formats, view dimensions, aspects and
mesh layout before opening a host pass. Color/depth outputs must be declared
writes; samplers must be declared reads. `clear=true` clears colors to zero and
depth to 1 for LESS/LESS_EQUAL tests, otherwise to 0. For custom clear values,
record an explicit encoder clear and draw with `clear=false`. Every attachment
must have the same view dimensions. The fullscreen helper draws three procedural
vertices; mesh draws use the declared triangle vertex count.

## Describe a graph

```java
RenderGraph definition = RenderGraph.builder()
    .importTexture("sceneColor")
    .texture("lighting", TextureSpec.scaled(GpuFormat.RGBA16_FLOAT, 0.5))
    .pass(new RenderGraph.Pass("compose", Set.of("lighting"),
        Set.of("sceneColor"), Set.of(), composeCallback))
    .pass(new RenderGraph.Pass("lighting", Set.of(),
        Set.of("lighting"), Set.of(), lightingCallback))
    .build();
GraphExecution graph = session.graph(definition);
// In the contribution callback:
graph.execute(frame);
```

Each resource has at most one writer. Owned reads depend on their producer;
imported reads consume the incoming snapshot and run before that import's
writer. `after` adds explicit pass dependencies. Compilation sorts stably and
rejects unknown resources/dependencies, cycles, uninitialized reads, duplicate
writers and same-pass sampling feedback. Split feedback into separate textures
and passes. Distinct import names must resolve to distinct physical textures;
aliases, including different views of one texture, are conservatively rejected
before commands are recorded.

The executor imports standard host frame names. For your own named resources,
use `graph.execute(frame, Map.of("source", ownedTextureHandle))`; imported owned
handles must belong to the graph's session. FrameContext's texture map also
allows explicitly borrowed custom views. The caller owns their lifetime.

Graph attachments are cached, reused and replaced only when their actual size
changes. Fixed targets survive window resizing. If replacement allocation fails,
new partial allocations are closed and old attachments remain available. Pass
accessors enforce declared reads/writes. `ctx.encoder()` permits advanced host
commands; respect the declarations and do not submit this encoder yourself.
Renderpearl supplies resource transitions and submission. Graph execution is
not transactional: a callback failure can submit already-recorded commands
before its owning contribution is disabled. There is no transient aliasing,
resource history, cross-contribution graph merging or implicit hazard tracking
for arbitrary raw host commands.

## Use shaderpack snapshots

`ShaderProgram.fromPack(pipeline, loadedPack, additionalSources)` imports a
`PackCatalog.LoadedPack`'s fragment sources, include snapshots and matching
fragment binary. Supply the vertex GLSL source in `additionalSources`.
Overriding the fragment GLSL drops the corresponding pack binary to avoid
executing stale binary code. Fetch the pack in the session factory so resource
reset obtains the latest valid catalog snapshot.

This bridge reuses shader assets; it does not automatically write MarineFrame,
voxel data, parameter values or start Lua scripts. Declare and initialize all
bindings required by the selected shader. Schema-v1 manifests still describe
ordered pack passes; general graphs and meshes are contributed through Java.
Pure resource packs continue to use [SHADERPACKS.md](SHADERPACKS.md).

## Measure and inspect

`MarineRenderer.capabilities()` reports the active host backend and attachment
limit, implemented geometry support and Vulkan fragment-binary availability.
Hardware ray tracing reflects the initialized, enabled native pipeline.
`FrameContext.worldRaysReady()` additionally checks current-frame data readiness.
The borrowed frame imports `worldLighting` (RGBA16F irradiance), `worldNormalDepth`
(RGBA32F world normals/reverse depth) and `worldAlbedo` (RGBA16F linear atlas color)
are half resolution when ready and distinct initialized 1x1 zero textures otherwise.
Never close them. Import them by name in graphs and test readiness before sampling.
The original FrameContext constructor remains available with readiness false. `MarineRenderer.status()` reports per-mod
initialization/failure/frame counts. `RenderSession.stats()` reports cumulative
texture/buffer allocations and program compilations.

Enable `graph.profiling(true)` to collect bounded CPU submission samples and
asynchronous GPU timestamps after 40 warmup frames. `graph.timing()` includes
first execution time separately. CPU time includes allocation when needed,
query polling, command recording and submission; it excludes session-factory
shader compilation and other Minecraft rendering. GPU time covers commands in
that graph, not the frame or adjacent graphs. Disable profiling to close query
resources. Timestamp support may be unavailable; zero samples means no GPU
measurement. These are local measurements, not an FPS guarantee.

The accepted Vulkan/OpenGL fixture, captures, timings, resource reuse counts
and regression evidence are in `development/evidence/render-api/README.md`.
The example provides albedo, normal and depth for its contributed mesh. Vanilla
rasterization stays in place; there is no vanilla G-buffer, PBR texture system,
multi-bounce lighting or FSR4. Native world ray queries and their finite coverage
are described in [HARDWARE-RT.md](HARDWARE-RT.md).

## Host world programs (0.6)

Mods can register shader packs with `PackProgram` declarations to replace selected
Minecraft world shader stages. `WorldPipeline.decorate` preserves host pipeline state
and existing uniform slots. `ShaderProgram.source(id,type)` supports matching resource
ids across vertex/fragment stages. See [world programs](WORLD-PROGRAMS.md).

Surface material and reflection imports plus independent readiness flags are
additive in 0.7; see [surface rendering API](SURFACE-RENDERING.md). Legacy primary
ray imports remain independent when a pack explicitly selects surfaceOnly.
