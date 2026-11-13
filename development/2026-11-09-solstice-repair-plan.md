# Solstice repair implementation plan

> Use systematic debugging and test-driven development. Execute in the existing
> `main-development` linked worktree; preserve the pre-existing Iris work.

**Goal:** Repair default-resource Solstice texture and shadow artifacts first,
then improve light readiness, raster quality, controls and performance without
claiming unmeasured path-tracing quality or FPS.

**Architecture:** Reproduce the owner's symptoms with independent feature toggles
in an isolated Vulkan client. Repair the producer or consumer responsible for each
confirmed defect. Share portable shading through Renderpearl and preserve ABI1,
MarineFrame's 288-byte layout and declaration-ordered pack parameters.

**Tech stack:** Minecraft 26.3, Java25, Fabric Loader, Renderpearl Vulkan, Rust/FFM,
GLSL and opt-in actual Vulkan readbacks.

## Accepted requirements and baseline

Owner authorized implementation after planning. No additional resource packs;
default GreenMarine/Solstice resources only. GPU RX9070XT, Gigabyte1440p180Hz,
no Sodium, forced Hardware rays at all qualities. Latest owner's viewport is
2560x1370 (occasionally2560x1410). Reported Medium250FPS, High150FPS and
Ultra130–160FPS are observations, not a controlled benchmark. Desired Ultra
minimum140FPS is an acceptance target, not a guarantee.

The owner wants correct textures, stable shadows at corners/near a held torch,
no cave triangles, timely lava light, held lights on mobs, mountains/DH occluding
sunlight, continuous distance handover, attractive water/weather clouds and
footprints. Pure raster must look acceptable. Low/Medium should be raster-led
with optional RT, High RT-led with raster optimizations, Ultra should approach
path-traced lighting. Presets must also support meaningful manual controls.

## Constraints

- No changes to the Iris converter in this work; retain all existing changes.
- No new required Fabric API/KoperLib dependencies or JNI.
- Minecraft owns borrowed GPU objects and command encoders.
- No installed game JAR replacement, commit, push or Android export.
- Any eventual Android release must use Bionic ARM64 and the required exporter.
- English text in repository documentation; evidence/plans in development/.
- Full test/build success is separate from runtime rendering acceptance.

## Review focus

- Default atlas sprite gutters and arbitrary origins must not leak neighboring
  texture data into relief/materials.
- Invalid cached/missing scene data must report unavailable rather than use
  stale outputs after reload/world switches.
- A nearby light or secondary bounce must not self-occlude or leak through a
  corner due to mismatched ray origin/distance.
- Chunk availability and emitter edits must update light data promptly without
  repeatedly scanning the whole world each frame.
- Feature-off and raster paths must remain initialized and genuinely cheaper.

## Task 1: Default-material texture diagnosis and repair

Files: `src/probe/java/com/koper/greenmarine/probe/SolsticeRepairProbe.java`,
`RuntimeProbe.java`, `build.gradle`, the responsible Solstice shader include,
and a behavioral regression under `tools/` or `src/test/`.

- [x] Capture a reproducible sand/stone scene using default resources with all
  effects, relief disabled, GI disabled and RT disabled, one variable at a time.
- [ ] Trace actual atlas material presence, UV bounds and resulting shading.
- [ ] Write and run a failing behavioral shader/GPU regression for the confirmed
  defect, then minimally repair it and run the same regression green.
- [ ] Verify the same Vulkan fixture and retain matched before/after captures.

## Task 2: Static emitter freshness

Files: `client/rt/SurfaceLights.java`, a pure refresh policy/cache helper and tests.

- [x] Identify the loading/edit path that bypasses the20-tick refresh.
- [x] Reproduce delayed updates and test world reset, chunk replacement and
  unchanged data without excessive rescans.
- [x] Implement bounded invalidation/update behavior and verify tests.

## Task 3: Native shadow correctness

Files: confirmed producer paths in `engine/shaders/` and actual GPU regressions
in `engine/src/`. Do not change ray/sample budgets without cost evidence.

- [x] Trace near-light visibility and shadow origins/endpoints.
- [x] Reproduce independently in an actual Vulkan shader test before fixing.
- [x] Repair confirmed defects; preserve alpha-tested/actor shadow behavior.
- [x] Run relevant GPU regressions, `cargo test` and `cargo build --release`.

## Task 4: Integrate and verify

- [x] Review combined changes for shared-frame/resource lifetime regressions.
- [x] Run `./gradlew test build`, relevant Python shader fixtures and Rust checks.
- [ ] Obtain actual Vulkan gameplay evidence with default resources. Record
  CPU/GPU stage costs separately at a fixed camera/viewport/quality.
- [x] Record exact resolved symptoms and remaining requirements in an evidence
  file; compilation alone never closes a visual report.

## Subsequent work, retained explicitly

Held lighting on entities, DH-owned occluder data, raster shadow coverage and
distance cache invalidation need their own producer/consumer investigations.
Water/cloud redesign, manual preset controls, footprints and richer Ultra
transport follow correctness work. Each needs independent visual/performance
acceptance. None is considered completed by repairing Tasks1–3.

## Execution rulings and evidence

- Owner said to start when no questions remain; proceed without another design
  approval round. Implementation uses existing architecture and isolated probes.
- Already in a linked worktree, parent AGENTS.md does not exist. Local AGENTS.md
  was read. No new worktree or migration of the large dirty tree is necessary.
- Baseline `./gradlew test build` succeeded (up-to-date results, not a fresh
  forced rerun); log `/tmp/greenmarine-solstice-baseline.log`.
- Independent emitter and native-shadow investigations are delegated under the
  dispatching-parallel-agents skill; root owns texture diagnosis/integration.
- Ruling: add ordinary entity light reception to this first repair batch because
  the owner explicitly requested it and it shares the dynamic source snapshot.
  Actual Vulkan probe passed Hardware/Off, near/far, empty/re-equipped and pack
  teardown states. This does not close the first texture report.
