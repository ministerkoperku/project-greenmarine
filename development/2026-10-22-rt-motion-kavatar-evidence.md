# GreenMarine 0.9.3 verification

Owner reports movement glitches with RT enabled, strongest on oxidized copper
but also visible on iron. Their KAvatar body is absent. Mobs have not been tested
by the owner; they are not currently ray targets. No fresh gameplay recording or
running Android world was available. The exact overall glitch remains a runtime
acceptance question; do not equate the controlled regression with full resolution.

## Verified causes and changes

The reconstruction divided hit validity by accepted weight alone. A tiny
compatible producer could remain full-strength until all support was rejected.
The actual GL shader fixture observed blue255 at every accepted pixel, including
partially represented boundaries. A failing regression asserted support fading.
After tracking accepted/intended footprint weights, the same fixture returned
blue64..255 with zero missing reflective pixels and no cross-plane color leaks.
Five subpixel horizontal phases passed. Normalized native radiance and sun
visibility remain intact; terrain applies support once to both native lighting
and reflections. The original six-argument GLSL function remains as an overload
that fades hit validity, so water does not receive support twice. No additional
rays, texture taps, frame resources, ABI fields or temporal history were added.

Oxidation stages lacked profiles and fell back to generic class1. Three coated
dielectric class9 profiles now distinguish exposed, weathered and oxidized full
copper cubes. Neutral F0.04 avoids using green patina albedo as metallic Fresnel.
Actual Minecraft26.3-snapshot9 model/blockstate fixture tests follow all four waxed
and four unwaxed full-cube IDs to the four real texture IDs and serialized UVs.
Original32/1040 and extension8/272 layouts remain unchanged; five extension slots
are used. Native class9 dispatch compiles, but its dedicated full-RT radiance
acceptance case remains ignored without the specified ray-query GPU.

KAvatar replaces vanilla body rendering through AvatarLayer rather than removing
AvatarRenderState extraction. The previous path always captured vanilla geometry.
An optional reflection-only bridge now uses actual KAvatar/Kodel evaluated frame,
animated matrices, cube templates, UVs, transforms and texture without mutating
the host player model or requiring those libraries in GreenMarine's base linkage.
Method/field lookup and static cube geometry are cached. Complete same-texture
parts fit the existing actor budget; unsupported bodies are rejected atomically
and accessories have explicit omission diagnostics. No sibling project changed.

## Verification

- Red/green scalar and actual GLSL partial-footprint regressions passed.
- Copper material tests:7 passed, including actual vanilla model/texture fixtures.
- KAvatar adapter tests:8 passed with real isolated KAvatar0.3.0/Kodel0.1.2/Fabric
  API fixture jars. Hyper Chibi14/Baby12 wide/slim, actual walking animation,
  all-face UV/deformed-bound equivalence to KodelModelRender, rotated/mirrored
  UVs, root/anchor placement,17-cube rejection, invalid pose, absent dependency
  and public API resolution were checked. No KAvatar fixture test was skipped.
- Full Android dual-native Gradle test/build: successful. Java243 tests, zero
  failures/errors, one optional Sodium fixture skipped.
- Python57 tests passed, including actual Vulkan/OpenGL shader and SPIR-V variants.
- Rust12 tests passed; six explicitly gated GPU tests ignored by default.
  Production actor GLSL Vulkan compute separately ran and passed on llvmpipe.
- `cargo clippy --all-targets -- -D warnings`: passed.
- Actual Bionic Java25 actor-FFM smoke:29 checks plus library lifetime and worker
  teardown passed. Packaged-native ABI1/FFM/resource-close smoke passed.
- Both packaged native entries exactly matched their target release build bytes.
- Independent review found no confirmed Important defect in weighting/copper or
  the completed optional KAvatar adapter. Runtime movement and appearance unverified.

## Delivery and limits

Guarded Android export checks ELF/Bionic dependencies, no RPATH/RUNPATH, metadata
and exported checksum. Artifact:
`/storage/emulated/0/Documents/koper/recents/greenmarine-0.9.3-prototype.jar`

JAR SHA256:
`d858579ccc25424691651bac202970680f43ae3befa4caa394c8eb21aca6222a`

Android native SHA256:
`c4ae21a916ede4f10bd621808c24b119a77d636b9780c344bd13b77f21e9013e`

Linux ARM64 native SHA256:
`8e87b3d075bcb93c0ab743e9a594ffb47b9a51b85c8d1eebe0bc073386a93d7e`

KAvatar support is bounded to16 closed boxes and one texture. Differently textured,
oversized, open-cube or triangle-mesh parts cannot all be represented by this ABI;
status explains rejection/omission. Armor, capes, held items, mobs and arbitrary
entities are not added. Copper coverage is the full-cube oxidation textures,
not every cut/chiseled/grate/door/bulb texture. The five-phase fixture confirms a
specific popping contributor; neither total gameplay glitch removal nor a new
Android FPS improvement is established.
