# Named scene targets implementation plan

> Execute inline as a delegated implementation task; preserve other agents' resource and converter work.

**Goal:** Schema 1 packs can render fragment outputs to named persistent colour attachments and safely sample preceding values, including self feedback.

**Architecture:** Add target declarations and per-scene-pass output mappings. Owned target pairs initialize deterministically, copy only identical attachments before partial writes, and swap after a successful draw. Existing unnamed output, lightmap, history and MarineFrame contracts remain unchanged.

**Tech stack:** Java 25, Minecraft Renderpearl, Vulkan typed integer clears, JUnit.

**Design:** Root approved top-level `targets` definitions (`format`, `scale` or `size`, `clear`, `clearEachFrame`) and scene `outputs` mapping location to target. Named targets are initialized before reads. Float and unsigned integer target formats share PackResources.Format. Existing final scene passes can sample named targets and compose for presentation.

## Constraints and review focus

- Preserve old ShaderPack and PackPass source constructors and schema 1 parsing.
- Never sample the active write attachment; target feedback always reads the preceding version.
- Exact format/dimension copies only, no HDR allocation downgrade.
- Reinitialize on pack/reload/viewport changes and destroy only owned targets.
- All MRT attachments share dimensions, names cannot alias storage resources, and unsupported output counts/formats fail explicitly.
- Integer clears preserve uint32 bits; integer presentation requires an explicit conversion shader.

## Tasks

- [ ] Add failing manifest regressions for initialized target feedback and more than eight raster passes; verify RED.
- [ ] Implement PackTarget, additive record constructors, output mapping and strict declarations; verify parser and scheduling tests GREEN.
- [ ] Add failing format-layout/feedback lifecycle regressions; implement exact owned pair lifecycle and MRT pipeline/render-pass integration.
- [ ] Verify old history/lightmap/single-output tests and all Java tests; run a controlled Vulkan MRT/feedback fixture in the coordinated build window.
- [ ] Document JSON and actual backend evidence/limits; hand off without committing.
