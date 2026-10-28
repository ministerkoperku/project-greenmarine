# Distant Horizons initialization and cave follow-up

## Confirmed startup crash

The owner's `crash-2026-10-27_23.03.35-client.txt` reports a null static
`RenderUtil.RENDER_DEF` in DH's first LOD projection update. Inspection of the
installed DH 3.3.4 bytecode confirms that its static initializer snapshots a lazily
registered renderer definition. GreenMarine's reflective discovery previously
initialized this class before DH had registered that definition.

The bridge now discovers RenderUtil with initialization disabled. It also defers
clip-method invocation until DH supplies a depth texture matching the current
viewport; previously even a null depth frame invoked those methods. DH's texture
getter itself does not initialize RenderUtil, as verified against its bytecode.

Two isolated-classloader JUnit regressions execute the actual production bridge
against a minimal lazy DH fixture. They verify both metadata-only discovery and
missing-depth frames leave the utility uninitialized; later invocation captures
the registered definition. Both failed before the fix and passed afterwards.
Independent review found no important issue in the narrow change.

An isolated copied-world Vulkan run with the actual DH 3.3.4 JAR reached
`Distant Horizons LOD lighting active: depth 1200x675 D32_FLOAT` and completed all
seven daylight variants with a normal save/exit. Evidence is in
`build/verification/cave-followup/dh-runtime.log` and `dh-runtime/`. This exercises
the real bridge/render startup, not the entire owner's modpack. DH is currently
disabled in Prism; its installed enabled/disabled state was preserved.

## Confirmed indirect-light texture sampling bug

The native surface sampler clamped max LOD to zero although diffuse bounces
explicitly request atlas mip four. The sampler now permits available mip levels;
reflection, alpha and actor-skin lookups remain explicitly at mip zero. The world
sampler is unchanged, and no native ABI or borrowed-handle ownership changed.

The Vulkan regression uses a green base mip and a red coarse mip. Before the fix
diffuse GI remained green `[0.199951, 0.649902, 0.199951, 0]`; afterwards it uses the
coarse red mip `[0.297851, 0.199951, 0.199951, 0]`, while reflections remain detailed
green `[0, 0.649902, 0, 1]`. This fixes a real path for projecting fine block/lichen
patterns into indirect light; it does not establish that every reported cave
artifact has the same cause.

## Validation and delivery

- `./gradlew test build`: successful, 295 tests discovered, 282 passed, 13 opt-in skips.
- Python suite: 65 passed.
- `cargo test`: 17 passed, 10 GPU tests opt-in; `cargo build --release`: successful.
- Eight relevant explicit Vulkan GPU regressions passed before the interruption;
  their individual logs remain in `build/verification/cave-followup/`. Two unrelated
  archived-baseline GPU failures remain documented in the earlier evidence.
- `git diff --check`: passed.

The owner's previous explicit Prism-update authorization persists. The desktop
JAR was atomically installed with its old bytes backed up, exact build-output
identity verified, packaged Linux native checked against its build output, changed
shader resources compared, and absence of probe classes verified. No settings,
worlds or other mods were modified. Receipt and hashes:
`build/verification/cave-followup/install.json`. This is not an Android export.

## Cave and frame-cost investigation

`CaveProbe` completed enclosed daytime stone-room and close held-torch views on
Vulkan, High. It captures default, shafts disabled, GI disabled, rays disabled,
native sun visibility, and native coverage variants. These separate fixtures are
in `cave-before/` and `cave-close/` under the same verification directory. The
enclosed-room native sun-visibility image is black, including the shadowed pillar;
the default and shafts-disabled room retain the same light distribution. The
close held-torch view also has no visible bright line at its central pillar edge.
These scenes have not reproduced the owner's remaining line or cave-mouth report.

Full static-light scans were timed 30 times in each scratch scene with emitting
blocks distributed through nearby sections. Median/max were 1.184/1.840 ms and
1.471/1.940 ms. This is not sufficient evidence for a 25 ms frame. The proposed
incremental scanner redesign was therefore deferred rather than changing light
readiness/publication without a demonstrated bottleneck.

The existing natural-terrain performance route completed separately on Vulkan,
RX 9070 XT, Ultra, 1200x675, VSync off, throttle reason NONE. Whole-frame intervals:

| Phase | Frames | Median ms | p95 ms | Maximum ms |
|---|---:|---:|---:|---:|
| Hardware, stationary | 2110 | 3.331 | 4.571 | 18.101 |
| Hardware, moving | 5392 | 2.731 | 3.889 | 16.953 |
| Raster, stationary | 3453 | 1.736 | 2.817 | 13.789 |
| Raster, moving | 9547 | 1.501 | 2.197 | 8.927 |
| Disabled, stationary | 12719 | 0.486 | 0.859 | 7.494 |

The hardware surface stage's last 256 stationary samples separately average
0.0572 ms CPU submission and 0.5661 ms GPU; moving averages are 0.0371/0.3920 ms.
No measured frame exceeded 25 ms. These are short standalone-mod runs in a copied
world, not a reproduction of the owner's full modpack, resolution, hardware load
or affected cave. No universal FPS claim or stutter fix follows from them.
`performance/result.json`, `performance/profile.jfr` and `performance.log` preserve
raw frames, stage timings and profiling evidence.

Bright cave seams, reported cave-mouth light and intermittent FPS drops remain
unresolved pending the affected world/dimension/coordinates and current run target.
Those details have been requested asynchronously. Original saves/configurations
were preserved; all probe clients completed and exited normally.
