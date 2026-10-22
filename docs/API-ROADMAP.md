# Rendering API roadmap and Android audit

Audit date: 2026-10-17. This is a proposed backlog, not a support claim.
The local reference is GreenMarine 0.8, commit `4013caf`.

Delivery update: 0.8.1 fixes ARM64 target selection and packaging, includes a
dual Android/Bionic + Linux/glibc ARM64 JAR, and adds touch-accessible settings.
Android Java 25 FFM tests now pass, including packaged loading, logical close
and worker teardown. Android code mappings use process lifetime to avoid a
Bionic/Rust unload crash; native ABI versions remain unchanged. Gameplay in
the requested Poco F8 Pro/Zalith instance now has a positive owner field report
(Solstice RT shadows, approximately 60 FPS at low chunk-generation settings).
Version 0.8.2 adds a targeted Sodium terrain/material adapter for built-in
Solstice/Seabreeze; its rendered device acceptance remains pending.
See [installation](ANDROID-ZALITH.md) and
[verification evidence](../development/2026-10-17-android.md).

## 0.9.0 delivery update

Indexed/instanced contribution draws and opt-in transient graph allocation reuse
are implemented with ownership/range/lifetime tests. Existing graph texture
storage remains persistent unless explicitly marked transient. New settings expose
paginated pack/quality/ray controls and cached CPU/GPU stage timings. Repeated
world binding updates are skipped when pipeline/frame/capture state matches;
profiling no longer rebuilds scenes/programs/scripts.

These close portions of the mesh, allocation and diagnostics backlog. General
compute/storage resources, complete scene/material capture, PBR texture loading,
world shadow maps and volumetric cloud rendering remain unimplemented platform
work. No comparative FPS advantage is established by this release.

## Current foundation

GreenMarine already exports session-owned GPU buffers/textures/programs,
validated render graphs, contributed triangle meshes with MRT/depth,
world shader replacements, Lua packs, parameter persistence, lightmaps,
voxel lighting, optional native world/surface ray queries, temporal pack
history and CPU/GPU graph measurements. These should be extended rather
than replaced. See [rendering API](RENDERING-API.md),
[world programs](WORLD-PROGRAMS.md) and [surface rendering](SURFACE-RENDERING.md).

Iris primarily provides a shaderpack environment; Veil provides rendering
tools for mod developers. API breadth, pack compatibility and runtime
performance therefore need separate acceptance criteria. Merely exposing
more methods does not establish parity or better performance.

## Android: confirmed blockers and acceptance

The existing `GreenNative.openBundled()` accepts only Linux x86_64 and
`processResources` always places its host-built library in `native/linux-x86_64`.
On this ARM64 development host that packaging would label an ARM64 ELF as
x86_64. Android Bionic and Linux glibc are different native ABIs even when
both report `Linux/aarch64`; a proot process must use its own libc ABI.
Do not infer Android native ABI solely from the presence of `/system`.

The installed Rust target includes `aarch64-linux-android`, and a Termux
Android linker is present. Neither establishes a working release artifact.
The baseline `./gradlew test build` stopped at `compileRayShader` because
`glslangValidator` is absent. `cargo test --locked` passed 10 tests, with
four explicit desktop GPU acceptance tests ignored. ADB listed no devices.
No Android launcher or rendering session has been validated in this audit.

A second baseline run, `./gradlew test build -x compileRayShader
-x compileSurfaceRayShader`, reused the checked-in SPIR-V. The native release
compiled, but Java reported 162 tests with one failure:
`GreenNativeTest.bundledLibraryExercisesTheRealFfmContract`. The loader rejects
the ARM64 host. ELF inspection confirmed an ARM aarch64 library while ZIP
inspection found it under `native/linux-x86_64/libgreenmarine_engine.so`.
This independently reproduces both the loader and packaging defects. The
generated JAR is not an Android delivery artifact.

Android checklist (items 1–3 delivered; on-device gameplay checks remain):

1. Select native resources by OS, architecture and process libc. Keep Android
   ARM64 separate from Linux ARM64 and desktop x86_64; test proot detection.
2. Build/package the selected target deliberately. Reject unsupported target
   values and missing requested binaries instead of silently mislabeling them.
   Preserve a documented Java fallback when native loading is unavailable.
3. Cross-build an Android Bionic ARM64 release library, inspect ELF machine
   and dependencies, then test FFM ABI versioning on the intended Java runtime.
4. Document Java 25, Fabric Loader 0.19.5+, Minecraft 26.3 and launcher requirements.
   This remains a Java Edition mod JAR, not an Android APK or Bedrock add-on.
5. On the intended launcher, verify host Renderpearl Vulkan creation before
   diagnosing GreenMarine passes. A launcher's renderer label is insufficient
   evidence of a usable Minecraft Vulkan device and command encoder.
6. Start with Portable/Low and rays Off. Test pack selection, resize, resource
   reload, world changes, pause/resume, background/foreground and missing native
   library. Test optional voxel/hardware rays separately, with readiness checks.
7. The delivered pause-menu **GreenMarine** button opens settings by touch;
   desktop F6 also remains available. Confirm its layout in the actual launcher.

## Prioritized API work

Items below are recommendations derived from the local public interfaces and
the primary references linked at the end. They are not assertions that every
listed feature exists in both comparison projects.

| Priority | Add or extend | Current gap and measurable acceptance |
| --- | --- | --- |
| P0 | Versioned capability contract | Extend backend/attachment flags with compute, storage, image formats, dimensions, alignment and driver limits; report implemented and initialized paths separately from advertised hardware features. Keep existing consumers source compatible. |
| P0 | Compute API | Add owned compute programs, dispatch, storage buffers/images and explicit graph access declarations. Verify write-to-sample and write-to-draw transitions on the actual host backend. Native RT compute is not a general public compute API. |
| P0 | Mesh submission | Public indexed and instanced draws, multiple vertex bindings and validated index ranges. Measure allocations and submission cost with a many-instance consumer. |
| P0 | Texture resources | Extend single-mip 2D attachments with mip levels, layers, cube/3D textures, upload and sampler controls, where the host supports them. Validate views, formats and subresource hazards. |
| P0 | Stable world/material contract | Define albedo, normal, roughness, metalness, emission and motion inputs; add PBR texture conventions. Existing ray/surface imports do not constitute complete world material coverage. |
| P0 | Compatibility acceptance | Exercise terrain, entities, block entities, particles, weather, hand and transparency; distinguish routing from tested shader variants. Add explicit Sodium/other renderer compatibility checks. |
| P0 | SDK distribution and compatibility | Publish a small consumer example and usable API artifact, document public/internal boundaries, version Java APIs and schemas, and verify an independently compiled consumer. Preserve MarineFrame v1 and native ABI contracts. |
| P1 | Earlier rendering hooks | Offer hooks around opaque/translucent/entity/hand stages with depth and ownership guarantees. Current contribution hooks all run after vanilla world rendering. |
| P1 | Composable shader modifications | Add structured injection/preprocessor hooks, deterministic ordering and conflict diagnostics; whole-program replacement alone makes independently authored effects harder to combine. |
| P1 | General graph history | Bring named previous-frame resources to public graphs, with camera-cut/resize/world/reload invalidation. Pack temporal history already exists; general graph history does not. |
| P1 | Resource graph scheduling | Add subresource access, transient resource reuse and a documented cross-contribution dependency policy. Retain current cycle, feedback and ownership validation. |
| P1 | Shaderpack authoring parity | Add named buffer formats/sizes, custom images/storage, richer per-stage inputs, profiles and actionable compiler diagnostics. Decide separately whether to build an Iris/OptiFine format adapter; current packs are not compatible. |
| P1 | Lighting extensibility | Add a public light-type/material registration contract, shadow policy and scalable tiled/clustered selection if justified by measurements. Current portable rendering selects at most 16 lights. |
| P1 | Debug tooling | Add graph/resource inspection, attachment previews, shader source/errors and per-pass GPU memory/timing reports. Existing timing counters provide a starting point. |
| P1 | Mobile resource budgets | Expose resolution scale, memory budget and optional feature policy without requiring a particular phone vendor; verify sustained frame time and lifecycle recovery on representative devices. |
| P2 | Pipeline compilation/cache | Reduce first-use stalls using host-supported compilation and caching, with reload invalidation and device/driver-specific keys. Do not move host GPU calls off-thread without a supported contract. |
| P2 | Temporal quality tools | Expose motion vectors, jitter and validity masks for reusable TAA/denoisers. Existing bounded filtering does not provide these for arbitrary consumers. |
| P2 | Advanced submission | Add indirect draws and GPU-driven culling where host capabilities allow; compare CPU and GPU costs against indexed/instanced baselines. |
| P2 | Optional model/animation layer | If full Veil-style tooling breadth is desired, design skinned meshes and animation integration as a separate optional module. It is not required for core shaderpack rendering. |

Recommended sequence: Android loading/build acceptance, P0 capability and
resource contracts, compute/mesh APIs, material/world coverage, compatibility,
then composability and tooling. Broader ray tracing and visual effects should
follow measured bottlenecks and consumer needs. FSR4 remains deferred under
the project's existing scope.

## What would establish parity or an advantage?

Use two independently authored consumers: a material/geometry mod and a
multi-pass shaderpack. Verify their resources, reloads, failures and cleanup
on desktop Vulkan and the selected Android Vulkan stack. Publish supported
features and fallback reasons. Compare CPU submission, GPU pass time, memory,
frame-time percentiles and sustained mobile behavior at identical resolution
and quality. Report device, driver, scene and warmup with every measurement.
Vulkan support and a versioned FFM boundary are promising foundations, but
cannot by themselves establish better performance or compatibility.

## Primary comparison references

- [Iris reference](https://shaders.properties/current/reference/overview/):
  shaderpack programs, buffers, uniforms and attributes.
- [Iris SSBOs](https://shaders.properties/current/reference/buffers/ssbo/) and
  [custom images](https://shaders.properties/current/reference/buffers/custom_images/):
  storage resources exposed to packs.
- [Veil project](https://github.com/FoundryMC/Veil): JSON framebuffers,
  post-processing, shader tools and skeletal model tooling.
- [Veil shader injection](https://github.com/FoundryMC/Veil/wiki/ShaderInject):
  JSON-directed shader modifications and ordering.
