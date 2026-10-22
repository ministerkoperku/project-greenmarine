# Planned features

The original project goals, as set out by the author, with the
current state of each. Status reflects version 0.9.6.

## Goal

GreenMarine combines Java and native code to rewrite a large part of Minecraft's
rendering behind layers of abstraction, so that other mods keep working. Its purpose
is to bring modern rendering technology to Minecraft.

## Shader API in three layers

An advanced shader API on Vulkan, through [ash](https://github.com/ash-rs/ash):

| Layer | Plan | Status |
|---|---|---|
| 1 | **OptiFine-format shaderpacks**: existing OptiFine packs should simply work | Not started. GreenMarine packs use their own format; Iris/OptiFine packs do not load. |
| 2 | **GreenMarine shader API**: real ray tracing and other modern techniques without sacrificing much speed | Done and growing: JSON/Lua/GLSL packs, world shader programs, voxel rays and native Vulkan hardware ray tracing ([shaderpacks](docs/SHADERPACKS.md), [hardware RT](docs/HARDWARE-RT.md)). |
| 3 | **Mod layer**: mods use the API with the deepest access | In progress: public rendering API, render graphs, meshes, lights and the external renderer API ([rendering API](docs/RENDERING-API.md), [external renderers](docs/EXTERNAL-RENDERERS.md)). |

## Upscaling: FSR 3 / FSR 4

| Plan | Status |
|---|---|
| FSR 3 and FSR 4 upscaling | Not started (deferred). |
| If FSR 4 cannot run through the Vulkan API, make it work another way | Open; depends on the above. |

## Performance, alongside Sodium

| Plan | Status |
|---|---|
| Optimize Minecraft's rendering and runtime, working next to and together with Sodium | Partly: GreenMarine works with Sodium 0.9.3, including hardware RT ([Sodium](docs/SODIUM.md)); dedicated render optimization is ongoing. |
| Sodium stays optional and never limits this project | Done: Sodium is optional and not linked. |
| Optimizing what Sodium does is not the main task; the main tasks are the shader API and its optimization | Guiding principle. |
| Where useful, GreenMarine may optimize areas Sodium covers, but must then turn the corresponding Sodium methods off | Not started. |

See also the technical [roadmap](docs/API-ROADMAP.md).
