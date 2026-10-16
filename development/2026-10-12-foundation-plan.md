# GreenMarine foundation implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task. Owner approved the whole scope and requested execution without repeated confirmation.

**Goal:** Build a standalone mod with a working native bridge, a visible GPU effect and optional Fullpack declaration support.

**Architecture:** Java owns Minecraft lifecycle and a renderpearl post-processing pass. Rust owns native context validation and effect parameter evaluation through a versioned FFM ABI. Optional Fullpack classes remain separate from base startup.

**Tech stack:** Java 25, Minecraft 26.3, Fabric Loader 0.19.5, Loom 1.18.2, Rust, ash 0.38, GLSL.

**Spec:** `2026-10-12-foundation-design.md`.

## Global constraints

- No required Fabric API, KoperLib, Fullpack, Kender or Sodium dependency.
- Use only `project-greenmarine/`; preserve sibling changes and the original `readme.txt`.
- Borrow Minecraft's actual Vulkan physical/logical device; never destroy borrowed resources.
- No API changes in KoperLib. Fullpack integration is compiled only when explicitly enabled.
- Initial native artifact is Linux x86_64; report other platform gaps.
- FSR, ray tracing and OptiFine packs remain future renderer milestones.

## Review focus

- Non-finite light/config inputs must be rejected rather than reach a shader.
- Missing or incompatible native library must leave Minecraft usable.
- Absent optional mods must not trigger class loading failures.
- Reload and disconnect must not retain stale handles or GPU targets.
- The actual GPU used by Minecraft must be passed to Rust, including on multi-GPU systems.

### Task 1: Standalone build and effect data

**Files:** `build.gradle`, `settings.gradle`, `gradle.properties`, wrapper files, `.gitignore`, `AGENTS.md`, `src/main/resources/fabric.mod.json`, `src/main/java/com/koper/greenmarine/api/{Light,LightRegistry,EffectDefinition,EffectRegistry}.java`, corresponding tests.

**Interfaces:** `Light(double x,double y,double z,float red,float green,float blue,float radius,float intensity)`; `LightRegistry.create/update/remove/clear/snapshot`; `EffectRegistry.stage/commit/abort/get` for transactional declarations.

- [x] Write failing tests for malformed light data, stale handles after clear and atomic declaration reload.
- [x] Run `./gradlew test`; verify missing implementation failure, then implement data contracts and standalone build.
- [x] Run `./gradlew test`; require all tests green.

### Task 2: Rust/FFM contract

**Files:** `engine/Cargo.toml`, `engine/src/lib.rs`, `nativebridge/GreenNative.java`, `nativebridge/NativeStatus.java`, ABI tests.

**Interfaces:** `greenmarine_abi_version() -> u32`; `greenmarine_attach(instance,physical,device,queue,family) -> i32`; `greenmarine_sample(time,intensity) -> float`; `greenmarine_detach() -> void`.

- [x] Test ABI mismatch handling and Rust rejection of invalid handles/non-finite sample input before implementation.
- [x] Implement safe panic boundaries, ash wrapping of the borrowed context and a platform-aware bundled-native loader.
- [x] Run `cargo test` and Java tests using the real native library; verify known sample values and ABI handshake.
- [x] Run `cargo build --release`; ensure the standalone Gradle artifact bundles this library.

### Task 3: Minecraft host and visible effect

**Files:** `GreenMarine.java`, `client/{GreenMarineClient,GreenConfig,GreenPostEffect,VulkanHost}.java`, client mixins, shader resources, config tests.

**Interfaces:** client initialization loads config/native; render hook queries host once ready and records the post pass after Minecraft post effects, before UI; native sampling provides the configured pulse intensity.

- [x] Test config validation/defaults before implementation.
- [x] Implement a configurable glow/color pass through Minecraft's command encoder; no raw independent queue submission.
- [x] Add backend accessors for actual instance/physical/device/queue and reload/close/disconnect cleanup.
- [x] Run `./gradlew build`; launch a separate dev game directory without optional mods and inspect startup/render logs.
- [x] Exercise the visible effect in a world, resize/reload and shutdown where local display access permits; record missing checks honestly.

### Task 4: Optional Fullpack and handoff

**Files:** `compat/fullpack/GreenFullpackAddon.java`, `examples/`, `docs/README.md`, `development/PROGRESS.md`.

**Interfaces:** dedicated `greenmarine_effect` content type through Fullpack's addon entrypoint; reload stages definitions and commits only when Fullpack reports a healthy reload. No competing `FullpackAddons.effects` registration.

- [x] Exercise invalid/duplicate declarations through the real registry tests.
- [x] Compile adapter against local Fullpack with `./gradlew -PfullpackIntegration=true build`; check base and integration artifact metadata.
- [x] Run final Java/Rust suites and artifact inspection; review all changes and report runtime evidence and remaining prototype milestones.

Work stays uncommitted for review; no branch changes, sibling commits or external publication are part of this run.
