# Roadmap

This is a backlog, not a support claim. Each item lists the gap and what would
count as done; "done" always means verified on the host backend, not only exposed.

## In place

- Session-owned GPU buffers, textures, programs and validated render graphs, with
  opt-in transient texture reuse ([rendering API](RENDERING-API.md),
  [graph allocation](GRAPH-ALLOCATION.md)).
- Contributed meshes with MRT/depth, indexed and instanced draws
  ([indexed meshes](INDEXED-MESHES.md)).
- World shader programs for terrain, block, entity, sky, clouds, weather and
  particles, with a Sodium terrain adapter ([world programs](WORLD-PROGRAMS.md),
  [Sodium](SODIUM.md)).
- Lua/JSON/GLSL shaderpacks with optional SPIR-V, external folder/ZIP packs and an
  authoring CLI ([shaderpacks](SHADERPACKS.md), [external packs](EXTERNAL-PACKS.md)).
- Voxel rays, native world and surface ray queries, local player actor, external
  renderer geometry and material capture ([hardware RT](HARDWARE-RT.md),
  [external renderers](EXTERNAL-RENDERERS.md)).
- Compute on the host device: session compute programs, storage buffers and storage
  images, graph storage textures with access-checked dispatches, and pack-level
  resources + compute passes bound by name ([compute](COMPUTE.md)).
- Pack temporal history, block material profiles, touch settings with CPU/GPU
  timings, Android ARM64 packaging.

## Prioritized work

| Priority | Item | Gap and acceptance |
| --- | --- | --- |
| P0 | Capability contract | Report compute, storage, formats, limits and *initialized* paths (not just advertised features) through one versioned, source-compatible query. |
| P0 | Compute API | Base in place (see above). Remaining: storage buffers/images in raster stages, dispatch batching under one barrier pair, indirect dispatch/draw, Adreno and desktop verification. |
| P0 | Texture resources | Mip levels, layers, cube/3D textures, uploads and sampler control where the host supports them, with subresource hazard validation. |
| P0 | Material contract | Albedo, normal, roughness, metalness, emission and motion inputs with PBR texture conventions; today's capture covers terrain and registered external renderers only. |
| P0 | Entity shading in packs | Solstice entity/block programs so host-rendered entities and block entities match terrain lighting; gameplay-tested variants, not routing alone. |
| P0 | SDK artifact | A published API jar, a small independent consumer example and documented public/internal boundaries. MarineFrame v1 and native ABI stay stable. |
| P1 | Earlier hooks | Contribution hooks around opaque, translucent, entity and hand stages with depth/ownership guarantees (current graph hooks run after the world). |
| P1 | Composable shader edits | Structured injection with deterministic ordering and conflict diagnostics, beside whole-program replacement. |
| P1 | General graph history | Named previous-frame resources for public graphs with camera-cut/resize/reload invalidation. |
| P1 | Pack authoring parity | Custom images/storage, richer per-stage inputs, profiles and better compiler diagnostics. An Iris/OptiFine format adapter is a separate decision. |
| P1 | Lighting extensibility | Public light types, shadow policy and clustered selection if measurements justify it (point lights are capped at 16). |
| P1 | Debug tooling | Graph/resource inspection, attachment previews, shader errors, per-pass memory and timing. |
| P1 | Mobile budgets | Resolution scale and memory budget policies, verified for sustained frame time on phones. |
| P2 | Pipeline cache | Fewer first-use stalls through host-supported compilation caching with reload invalidation. |
| P2 | Temporal tools | Motion vectors, jitter and validity masks for reusable TAA/denoisers. |
| P2 | GPU-driven submission | Indirect draws and GPU culling, measured against instanced baselines. |
| P2 | Upscaling | FSR-class upscaling; deferred by the owner. |

## Measuring progress

Claims of parity with Iris or Veil need two independent consumers (a geometry mod
and a multi-pass pack) verified on desktop Vulkan and Android, with CPU submission,
GPU pass time, memory and frame-time percentiles at identical resolution and
quality, and device, driver, scene and warmup recorded.

## References

- [Iris reference](https://shaders.properties/current/reference/overview/), including
  [SSBOs](https://shaders.properties/current/reference/buffers/ssbo/) and
  [custom images](https://shaders.properties/current/reference/buffers/custom_images/).
- [Veil](https://github.com/FoundryMC/Veil) and its
  [shader injection](https://github.com/FoundryMC/Veil/wiki/ShaderInject).
