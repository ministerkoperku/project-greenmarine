# Solstice lighting follow-up implementation plan

> Execute inline with `superpowers:executing-plans`; preserve the existing OpenCode changes.

**Goal:** Continue the owner's OpenCode session: stable cave/surface lighting during movement, smoother local shadows, dropped emissive item lighting, and actor shadows from local lights.

**Architecture:** Keep Minecraft material capture, Renderpearl passes and native Vulkan ray queries. Correct light transport and reconstruction at their sources; preserve ABI1, MarineFrame288, shaderpack parameter order and optional integrations.

**Tech stack:** Minecraft 26.3, Java 25, Rust/ash, GLSL, JUnit, Python fixtures.

## Recovered requirements and design

The session `ses_e86245e1dffeYTeJAPLOQTRDjd` is the source of the owner's reports. The owner accepted the previous firefly and underground shaft fixes. Preserve them. Later reports concern cave brightening as the finite RT scene approaches, snapping shadows, diagonal screen-fixed lines, pixelated block-light patches, harsh leaf shadows, and dropped items not emitting light. The owner explicitly requested autonomous continuation.

Use the existing reduced-resolution trace with geometry-aware reconstruction rather than multiplying Ultra's native pixel count by four. Remove sky radiance added on GI misses (the terrain already supplies skylight); use stable hemisphere quadrature instead of the screen-space Bayer direction pattern. Blend complete local/native shadow solutions at the finite-scene boundary, rather than changing the height-field start distance on a boolean coverage test. Use non-leaf columns for coarse distant terrain occlusion; local leaves remain actual alpha-tested geometry. Add interpolated item/entity lights and actor-aware finite-area block-light shadows.

Visual hypotheses remain hypotheses until exercised. Screen-fixed lines may have additional causes. No whole-game FPS or visual acceptance claim follows from compilation or isolated fixtures.

## Review focus

- Closed caves and empty ray misses must not gain sky radiance.
- Zero/tiny coverage, scene recentering and unavailable native data must keep initialized, continuous fallbacks.
- Reconstruction must reject separate planes and preserve reflection confidence at material edges.
- Local light rays must respect alpha-tested actor skins and their finite light distance.
- Dropped/held light positions must interpolate; empty/nonemissive items must not emit.

## Tasks

1. [x] Add behavioral Vulkan/GLSL regressions for cave GI, block-light actors, screen-dependent sampling and boundary lighting; observe failures before fixes.
2. [x] Correct native GI and local-light shadows, then run targeted GPU tests and release build.
3. [x] Correct continuous height-field handover and geometry-aware lighting reconstruction; verify shader variants and executable reconstruction fixtures.
4. [x] Add dropped item light extraction and interpolated burning-entity lights; verify item classification and Java compilation/tests.
5. [x] Run `./gradlew test build`, Python suite, `cargo test`, `cargo build --release`, explicit applicable Vulkan tests; review changes and record actual evidence/remaining limits.

## Execution ledger

- Existing linked worktree on `main-development`; no new worktree, branch, commit or installed-jar replacement.
- `../AGENTS.md` is absent in this workspace. Project `AGENTS.md` read.
- Android shared storage and Android NDK linker are absent on this PC. Do not export a desktop-only artifact as Android.
- Reproduced red/green: native cave GI miss radiance; actual screen-dependent hemisphere overload (previous test silently exercised its unused stable helper); zero-coverage sun discontinuity; coplanar material seam holes; Ultra reconstruction step (old radius mutation: 128/255 jump); actor local shadow and finite penumbra.
- Independent review found scene-only height-field upload inside a render pass, a partially visible source suppressing a brighter second light, and actor shadows truncated before the emitter. Fixed all three; the latter two have red/green Vulkan regressions. The scene-only first-use path passed the actual Minecraft Vulkan probe.
- Final results and remaining visual acceptance limits: `2026-10-27-solstice-followup-evidence.md`. Two explicit legacy GPU tests also fail on archived HEAD and remain outside this follow-up.
