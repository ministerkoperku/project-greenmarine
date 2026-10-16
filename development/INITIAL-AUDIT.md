# GreenMarine initial audit

Date: 2026-10-12. Status: source inspection and technical recommendations;
no renderer implementation or runtime verification yet.

## Owner intent

The original `../readme.txt` describes a Java/native Minecraft renderer using
Rust and Vulkan through `ash`. Its goals are rendering optimization, modern
graphics technology including FSR 3/4, and three shader integration levels:
OptiFine-format shaderpacks, a dedicated shader API with ray tracing support,
and deeper access for mods. Sodium must remain optional and must not constrain
the architecture. Preserve the original description.

## Current local evidence

- GreenMarine currently contains the original concept description and this
  audit; it has no Gradle wrapper, Fabric metadata, Java source or Rust crate.
- There is no project-specific `AGENTS.md`. Workspace instructions apply.
- The directory currently belongs to the workspace repository as untracked
  content; no separate repository or submodule has been created.
- KoperLib targets Minecraft 26.3, Fabric Loader 0.19.5 and Fabric API
  0.161.0+26.3 in `../../koperlib/gradle.properties`. These are reference
  versions, not an owner-confirmed GreenMarine support matrix.
- Kender has a Panama FFM bridge in `KenderBridge.java`, Minecraft Vulkan
  handle access in `KenderVk.java`, and an `ash` backend in
  `../../koperlib/engine/koperlib-kender/src/vk/mod.rs`. Java wraps the
  renderpearl frontend/backend; Rust borrows Minecraft's device and queue.
  Source presence does not establish compatibility or correct synchronization.
- The inspected Kender initialization selects the first physical device.
  A GreenMarine integration should receive the actual physical device used by
  Minecraft, alongside enabled device features, rather than inherit that
  assumption. Physical-device support alone does not prove that a feature is
  enabled on Minecraft's logical device.
- Sibling projects contain existing user changes. This audit changes none of
  them, and makes no claim that they build or run successfully.

## Packaging choices

| Choice | Initial effort | Long-term tradeoff |
|---|---|---|
| KoperLib/Kender module | Likely lowest: bridge and backend infrastructure already exist | Ties rendering changes and distribution to KoperLib |
| Separate mod requiring KoperLib | Reuses infrastructure with separate mod packaging | Still requires KoperLib and coordination of shared APIs |
| Separate mod with optional KoperLib integration | More initial integration and lifecycle work | Independent installation and releases; explicit compatibility work remains necessary |

Owner decision after this audit: a separate Fabric mod with optional Fullpack
effect and lighting integration. KoperLib and Fullpack must not be required.
The owner permits Fabric API or KoperLib Core if necessary; the proposed
foundation requires neither. Borrowing Minecraft's Vulkan context remains a
technical proposal. Reuse lessons and
appropriate small infrastructure pieces after checking their dependencies and
license; avoid copying the whole Kender implementation.

## FSR findings

- AMD documents a Vulkan implementation of FSR 3.1, including upscaling and
  frame-generation swapchain integration. Frame generation also requires UI
  composition; a post-processing callback alone does not establish support.
  [AMD FSR 3](https://gpuopen.com/fidelityfx-super-resolution-3/).
- AMD's current ML upscaling page lists DirectX 12 support. Do not describe
  FSR 4.x as an officially available native Vulkan backend on that evidence.
  [AMD FSR Upscaling](https://gpuopen.com/amd-fsr-upscaling/).
- OptiScaler documents FSR 4 for Vulkan through DirectX 12 interop. This is
  evidence of an alternative route, not a working Minecraft integration or
  proof of support in a native Linux JVM. GreenMarine's FSR 4 requirement
  remains open and needs an interop investigation, not removal.
  [OptiScaler compatibility notes](https://github.com/optiscaler/OptiScaler/wiki/FSR4-Compatibility-List).

## Proposed first implementation milestone

After deciding packaging and reviewing the design, establish a buildable
Java 25/Fabric frontend and Rust native bridge, explicit Vulkan resource
ownership, capability reporting, and one diagnostic render pass. The host
adapter must account for render-thread access, enabled features, image layouts,
queue synchronization, resize, reload and shutdown. Failure must leave the
ordinary rendering path usable and report a concrete reason.

Verify startup and the diagnostic pass with Sodium absent and present,
resource reload, resize and shutdown. Separately check coexistence with
Kender. Compilation alone cannot mark these runtime checks passed.

Later milestones retain all three shader integration levels, FSR 3/4 and
hardware ray tracing. OptiFine compatibility needs shaderpack semantics and
render-pass integration in addition to compiling GLSL into SPIR-V. Sodium
coexistence needs explicit ownership of any overlapping optimization paths;
the first milestone should not disable its methods speculatively.

## Decisions still needed

Packaging is selected: standalone with optional Fullpack integration.
The first-stage design is in `2026-10-12-foundation-design.md` for owner review.
Minecraft 26.3/Java 25 and Linux x86_64 development verification are proposed
initial targets; cross-platform support requires separate build/runtime evidence.
