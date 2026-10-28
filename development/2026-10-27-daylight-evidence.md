# Daylight scattering diagnosis and authorized Prism installation

## Reproduction

The owner identified the screenshot as `runClient` in `greenmarine-dev`, not the
Prism instance. Development logs confirm High, Vulkan, 2560x1370 capture with
854x457 native RT. The older installed Prism JAR was unrelated to this screenshot.

`DaylightProbe` reproduces the washed-out landscape and flat white foliage from a
fixed camera over natural terrain in a copied world. It captures default settings,
volumetric disabled, AO disabled, ray blend disabled, both volume/AO disabled and
raw AO/sun visibility. Original saves/configuration were not modified by the probe.

Disabling AO does not remove the white layer; disabling volumetric scattering does.
The old shader used dense air outdoors and multiplied surface endpoint scattering
by 5, versus 2 for sky endpoints. Identical air paths therefore painted different
brightness across cutout foliage and sky holes.

## Fix and regression

Outdoor extinction is now 0.001 instead of 0.009, interpolating continuously to the
existing indoor density 0.063. Gain interpolates from 1 outdoors to 10 indoors and
does not switch based on the endpoint being sky or terrain. Indoor terrain-beam
density/strength remain unchanged. The helper signature, night/dimension/underwater
guards, shaderpack parameters and all native ABIs remain unchanged.

The actual GLSL fixture supplies a fully sunlit 48-block march. Before the change,
alternating sky/surface endpoints differ by 108/255; the corrected shader passes
both endpoint-continuity and bounded perpendicular-sun scattering checks.
Independent review found no important correctness issue in the narrow fix.

## Runtime evidence and validation

Both daylight probes completed on Minecraft Vulkan/RX 9070 XT, High, 1200x675, from
the identical camera `(306.2684285744195, 77, 86.01359499320411)`, yaw 28.464844,
pitch 14, time 2500, clear weather. Captures/logs are under
`build/verification/daylight/{before,after}/` and corresponding `.log` files.

The default after image no longer contains the flat white tree crowns. Comparing
the default and volumetric-disabled images over the lower landscape region gives:

| Absolute RGB difference (8-bit scale) | Before | After |
|---|---:|---:|
| Mean | 18.9121 | 0.6463 |
| p95 | 39.0 | 1.3333 |

These quantify the excessive added white layer in this scene, not a universal
lighting-quality metric. Sun-facing glare and every possible modpack combination
are not separately accepted by these captures.

- `./gradlew test build`: passed; JUnit 293 discovered, 280 passed, 13 opt-in skips.
- Python suite: 65 passed, including actual Vulkan/OpenGL shader compilation.
- `cargo test`: 17 passed, 9 explicit GPU tests skipped by default.
- `cargo build --release` and `git diff --check`: passed.
- The two earlier baseline GPU failures remain documented in the preceding evidence;
  this change does not modify their native shaders or count them as passes.

## Owner-authorized installation

The owner explicitly requested replacing GreenMarine in their Prism "Niebo i Haos"
instance after completing the fix. This direct authorization supersedes the
repository's default rule against replacing installed jars during development.

Installed atomically into:

`/home/koper/.local/share/PrismLauncher/instances/niebo-i-haos-dev/minecraft/mods/greenmarine-0.9.11-prototype.jar`

Verified byte-for-byte against the final build output, checked the packaged Linux
x86_64 native against its build output, checked changed shader resources and
confirmed no probe classes leaked into the release. Exactly one enabled GreenMarine
JAR remains in that mods directory. Settings, worlds and other mods were preserved;
no owner process was stopped or started.

- Installed/build JAR SHA-256: `5c0fed8390310241b57c90a5c9510a175466eb1c1088cc940976abdc9081057f`
- Native SHA-256: `8eba240931b242a6a20da443e34a5268480c14292b7de09b8da6a4b84d54dd6a`
- Previous JAR SHA-256: `5f3040be83be2ed964c5c0a2266a85390aa5fe778101d24814719fe326c592f6`
- Verified backup: `.../niebo-i-haos-dev/backups/greenmarine-before-daylight-20261027-224348/greenmarine-0.9.11-prototype.jar`

Full installation receipt: `build/verification/daylight/install.json`.
This is the authorized desktop Prism installation, not an Android release/export.
