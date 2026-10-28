# Solstice follow-up evidence — 2026-10-27

## Changes and scope

Continued OpenCode session `ses_e86245e1dffeYTeJAPLOQTRDjd` from `c0d2ff4` in the existing `greenmarine-dev` worktree. Preserved its accepted shaft/firefly changes. No commits, branch changes, installed mod replacements or original world/configuration edits.

- Removed added sky radiance on native GI misses and the screen-dependent hemisphere direction pattern.
- Blended complete near/native height-field sun solutions at the scene boundary. Distant columns exclude leaves; local foliage remains alpha tested.
- Reconstructed lighting across coplanar material boundaries; High/Ultra use wider lighting kernels while reflection matching/confidence remains separate.
- Added three-tap finite-area local shadows, alpha-tested actor occlusion, full actor ray endpoints and selection by visible source contribution.
- Added interpolated dropped/held luminous items and burning entities. Item models still use the host shaders.
- Moved shared height-field initialization before scene render passes, including packs without world programs.

## Checks

- `./gradlew test build`: passed. JUnit: 292 discovered, 279 passed, 13 opt-in tests skipped, zero failures/errors.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: 62 passed, including actual Solstice Vulkan/OpenGL shader compilation and software-GL reconstruction execution.
- `cargo test`: 17 passed, 9 explicit GPU tests skipped by default.
- `cargo build --release`: passed.
- `git diff --check`: passed.
- `cargo fmt -- --check`: reports existing formatting differences elsewhere in `external.rs`, `lib.rs`, `rt.rs` and historical portions of `surface_gpu_tests.rs`; these unrelated sections were preserved. The new Rust regression was formatted separately.

Explicit GPU checks ran on AMD Radeon RX 9070 XT, RADV, Mesa 26.2.2-arch3.2. Seven passed:

1. `surface_gpu_cave_misses_do_not_add_sky_and_actors_shadow_local_lights`
2. `surface_gpu_half_resolution_uses_sloped_capture_texel_centres`
3. `surface_gpu_sun_ignores_cutout_layers_and_stops_at_accepted_blocker`
4. `surface_gpu_actor_skin_alpha_and_nearer_block_occlusion`
5. `actor_compute_world_distance_parallel_inside_alpha_and_inverse_transpose`
6. `actor_compute_extended_index63_is_visible`
7. `async_gpu_retains_generations_and_reclaims_only_completed_fifo_flights`

Two additional tests failed identically on this change and a separately extracted, unmodified `git archive HEAD engine` baseline:

- `surface_gpu_offscreen_reflection_shadow_odd_dimensions_and_scene_reset`: expects class-1 generic material reflections; the baseline native shader does not trace that class.
- `surface_gpu_external_meshes_reflect_shadow_move_and_survive_flights`: tint-only albedo assertion, red 0.26635742 versus expected 0.43505388.

These failures are not counted as passes. Logs for changed and baseline code are under `build/verification/solstice-2026-10-27/` and its `baseline/` directory.

## Regression evidence

Before fixing, enabling GI with all secondary rays missing changed cave irradiance from `(0.199951, 0.199951, 0.199951)` to `(0.244385, 0.266602, 0.297607)`. It now preserves the baseline. The actor local-shadow test initially returned the fully lit value 0.677246 behind an opaque actor; corrected occlusion returns 0.199951. Separate assertions exercise a partial penumbra, a second visible source and a thin actor within the last 0.6 blocks before the emitter.

The boundary shader fixture initially jumped from 26 to 230 in an 8-bit output for native coverage 0 versus 0.000001. It now remains continuous. The actual coplanar material fixture initially found 2,296 missing/incorrect light pixels; it now passes. Restoring the old lighting kernel in a controlled mutation produced a 128/255 adjacent-pixel shadow jump and failed the new Ultra test; the shipped kernel passes while retaining black and white endpoints.

## Actual Minecraft Vulkan probe

Ran `runClient -PruntimeProbe -PtorchProbe` with explicit `probeRunDir` and `probeOutput`, using only a copied world/configuration under `build/verification/solstice-2026-10-27/game/`. The accepted run registered and rendered a scene-only fixture first, then selected Solstice High/Hardware and exercised placed, held and dropped torches.

`accepted-game-output/torch-result.txt` reports:

```
complete; sceneOnlyVerified=true; droppedLightVerified=true; lightsActive=true
```

The dropped torch was checked against the actual Java surface-light payload, not just the screenshot. The log confirms native hardware RT on Minecraft's Vulkan device and Solstice status Loaded. Captures: `torch-floor.png`, `torch-held.png`, `torch-dropped.png` in that directory. The probe client exited successfully; no development client was left running.

Last 256 surface-stage samples at 1200x675, High, native output 600x338:

| Stage measurement | Mean | p95 |
|---|---:|---:|
| CPU submission | 0.119249 ms | 0.187398 ms |
| GPU surface RT | 1.287488 ms | 1.318120 ms |

These are separate stage costs in a small test scene, not whole-frame FPS or a before/after performance comparison. The existing probe's old `gamerule doDaylightCycle` command is rejected by 26.3; setting midnight succeeded and the short lighting probe completed. The missing narrator library is an unrelated host log error.

## Artifact

Desktop Linux x86_64 build: `build/libs/greenmarine-0.9.11-prototype.jar`.

- JAR SHA-256: `872bea4beab8fdd1d45602f1605a7296716b06df7c3cf68bb0ccea8670cce460`
- Packaged native SHA-256: `1552d54f187a1f26b134bc41735498f5d97e38b120c48e847bef9e5866440b71`

Verified packaged native bytes against `engine/target/x86_64-unknown-linux-gnu/release/libgreenmarine_engine.so`, checked the three changed Solstice includes against source, and confirmed probe classes are excluded. The editable Solstice shaderpack ZIP was rebuilt as well.

No Android release was exported. This PC has neither the configured Android NDK linker nor the requested Android shared-storage destination. The desktop JAR must not be presented as an Android release; Android delivery still requires the documented Bionic ARM64 build and guarded exporter.

## Remaining acceptance

The screen-dependent sampling and material-seam causes were removed and exercised in fixtures. The exact owner-reported diagonal lines, cave movement transitions and foliage appearance still need comparison in the original scene; no original screenshots or paired captures of that scene were available here. Distant Horizons modpack visuals and Android runtime were not tested. These limits are separate from the successful isolated Vulkan and Minecraft probes above.
