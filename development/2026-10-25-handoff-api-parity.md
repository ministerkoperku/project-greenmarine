# Handoff 2026-10-25: API parity with Iris, Eclipse as the benchmark

Branch `main-development`, worktree `/root/Downloads/project-greenmarine-development`.

## The goal (owner's words, restated)

GreenMarine is the project. PLANNED-FEATURES layer 1 is "OptiFine-format shaderpacks"; the
owner wants a converter that turns Iris packs into **GreenMarine API** packs (Eclipse → Vulkan
through GM), and above all wants the **GM API not to be behind Iris** in features, speed,
anything. Eclipse (Bliss fork, the owner's favourite, very heavy) is the benchmark pack the API
must be able to express. Solstice is what the owner plays. Not a "make me a working Iris" task,
and Vitrail is not an option (owner tried it: many visual bugs, works only for some packs).
Heavy GPU verification can move to the owner's PC (RX 9070 XT); lavapipe here is slow.

## Done this session

- dev tools 0.1.1/0.1.2: code editor (line numbers, GLSL colours, auto-indent, compiler errors
  mapped to editor lines through includes), Iris import of real packs (limits 8192 files /
  256 MiB), IMPORT-REPORT with IR-1 numbers.
- IR-1: all 250 default Eclipse stages translate and compile to strict Vulkan SPIR-V
  (`development-tools/.../iris/`, `irisCoverage` task, whole-pack tests with `-PirisPack`).
  See `2026-10-24-iris-conversion-plan.md`.
- Eclipse fixture: `/root/Downloads/eclipse-src/eclipse` ("© Chocapic13, all rights reserved":
  never commit or publish it or anything converted from it).

## Why the API is behind (measured, MC 26.3 Renderpearl, decompiled)

- `UniformType` = COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER only.
- `VulkanRenderPipeline.compile`: one push-descriptor set layout (flags 1), binding = index in the
  reflected uniform list, descriptorType 6/1/4 from the three types, stageFlags 17.
- `frontend/shaders/PipelineBuilder` (~line 268-345): walks `module.reflect().descriptors()`,
  requires every reflected name in the pipeline's BindGroupLayout, assigns bindings in first-seen
  order, and **rejects 1D and 3D samplers** ("3D textures not supported").
- `SPIRVModule.doReflection` enumerates `SpvUtil.DESCRIPTOR_TYPES` (spvc resource types); storage
  buffers/images are never listed.
- GpuBuffer usages: map, copy, vertex, index, uniform, texel, indirect. GpuTexture usages: copy,
  sample, attachment, cubemap. No storage bit anywhere; no 3D image type.
- No compute in the facade. The backend under it (`VulkanDevice`) exposes `vkDevice()`, the VMA
  allocator, a graphics queue with compute bit, `VK_KHR_push_descriptor`, embedded shaderc.
- Every texture lives in VK_IMAGE_LAYOUT_GENERAL; closing a pass records a full barrier.
- Viewport not flipped (OpenGL orientation); depth is reversed-Z (Iris packs assume GL depth).
- Vanilla 26.3 terrain vertex: Position, Color, UV0, UV2 (+ChunkPosition/Visibility in
  multidraw). No normal, block id, mid-texcoord, tangent. Sodium has its own compact format.
- Vitrail's docs (LGPL, read only for knowledge, do not copy code):
  github.com/avpbynf/Vitrail-Shaders/tree/main/docs (internals/game-graphics-api.md,
  terrain.md, render-targets.md, uniforms.md, translation.md, frame.md) record the traps.

## Next task: P0 "storage + compute + 3D" host extension (API-ROADMAP P0 Compute/Texture)

Make Renderpearl, through GreenMarine mixins, understand what Iris packs use, so every existing
GM surface (pack passes, world programs, render graph, session) gains it at once:

1. Reflection: add spvc STORAGE_BUFFER and STORAGE_IMAGE to the enumerated descriptor types
   (mixin on SPIRVModule/SpvUtil), and accept sampler3D in PipelineBuilder.
2. Layout: GM-side registry of names declared as storage buffer / storage image (BindGroupLayout
   cannot gain enum constants); mixin in VulkanRenderPipeline.compile maps those bindings to
   descriptor types 7 / 3 and stage flags incl. compute.
3. Binding: mixin where VulkanRenderPass pushes descriptors so storage names write
   VkDescriptorBufferInfo / VkDescriptorImageInfo(GENERAL, no sampler).
4. Resources: storage usage bit on GM-created buffers/textures (thread-local flag around
   createBuffer/createTexture, like Vitrail's colorimg mixin), 3D images via VMA with a
   GpuTexture-compatible view so passes can sample them.
5. Compute: `ComputeProgram` in the public API (session-owned, GLSL→SPIR-V via shaderc,
   push-descriptor layout, `dispatch(x,y,z)` on the frame command buffer between explicit
   barriers, outside render passes). Then a pack schema `compute` pass.
6. Capability query (P0): report storage/compute/3D as initialized paths.
7. Verify on lavapipe here (compute writes, a fragment pass samples it back pixel-exact), then
   on Adreno (phone) and the 9070 XT.

After that: Iris uniforms provider (~150), colortex named targets with flip/clear/format/mips,
shadow pass replay, terrain vertex extension (vanilla + Sodium), then the converter emits GM
packs and Eclipse coverage moves from "compiles" (IR-1) to "runs".

## Housekeeping rules learned this session

- Export: `Documents/koper/recents` holds only the newest jar of each mod; on export move the
  old jar to `Documents/koper/` (date suffix on name clash). Zips/SUMS/docs go to `koper/`.
- `nya~` only in commits; code comments with vibe but never explaining the obvious.
- `main-development` is intentionally ahead of `main`; don't "fix" that.
- Free RAM on the phone after GUI runs (`gradlew --stop`, kill Xvfb).
