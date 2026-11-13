# Host geometry and tessellation implementation plan

**Goal:** Execute declared VS/TCS/TES/GS/FS chains on Minecraft 26.3's actual Vulkan device and command stream, retaining ordinary two-stage Renderpearl behavior.

**Architecture:** Keep the host VS/FS pipeline and binding ABI. GreenMarine validates the actual compiled chain, contributes intermediate-only descriptors to host binding discovery, and augments Vulkan graphics creation with the missing stage structures and patch state. Immutable program declarations produce separate mutable compilation snapshots. The host owns borrowed device/encoder handles; only resources created for a GreenMarine program are retired.

**Spec:** `development/2026-10-29-eclipse-conversion-plan.md`, step 6, and the read-only host-bytecode investigation in the current session.

## Constraints and evidence

- Preserve fragment-only `ShaderBinary`, the 288-byte MarineFrame, optional dependencies and existing storage hooks.
- Request geometryShader/tessellationShader independently; inspect actual enabled host features and limits before pipeline creation.
- `PipelineBuilder.generateBackendCreateInfo` directly compares VS/FS slots, cannot flatten interface structs, and discovers dense descriptors from only those two modules.
- `VulkanRenderPipeline.compile` uses VS|FS descriptor visibility and creates depth/no-depth variants. Its exception handlers only free stack allocations.
- Host push constants already use all stages; host command barriers already cover all commands and memory reads/writes.
- The actual Eclipse intermediate binaries comprise 10 GS, five TCS and five TES. Terrain uses three-point patches, triangular fractional-odd CCW tessellation and triangle-strip GS output.

## Execution

- [ ] Add failing tests for stage-aware binaries/declarations and SPIR-V chain interfaces, including struct members, matrices/arrays, stage outer arrays, missing producers and intermediate-only descriptors.
- [ ] Implement additive GraphicsStage/StageBinary/ShaderStageSource declarations and ShaderProgram.withStages without changing old constructors.
- [ ] Implement bounded pure SPIR-V reflection, real descriptor-word remapping and compiled-chain validation. Preserve patch and interpolation semantics and validate push-constant bounds.
- [ ] Connect ProgramSources and RenderSession to per-compilation snapshots; reject unsupported backend/features before use.
- [ ] Add optional host feature requests, actual-enabled checks and physical-device stage limits.
- [ ] Add scoped frontend hooks preserving vertex input and host binding discovery; use validated whole-chain interfaces instead of direct VS/FS validation for registered programs only.
- [ ] Add Vulkan stage/visibility/patch hooks with a transactional compilation scope, both pipeline variants, thread-local cleanup and host queued pipeline retirement.
- [ ] Add a real host GPU probe for geometry and full tessellation/geometry chains, including intermediate-only uniforms, readback, depth/no-depth variants and failure recovery.
- [ ] Run root Java tests/build with the coordinated Gradle slot, execute the Vulkan probe, and record measured evidence and outstanding world integration separately.

No installed game JAR replacement, commit or export is part of this task. Work remains in the owner's authorized current checkout. Root coordinates whole-workspace builds and integration review.
