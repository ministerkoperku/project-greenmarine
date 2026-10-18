# Sodium terrain compatibility (0.8.2)

GreenMarine 0.8.2 adds a targeted adapter for Sodium
`0.9.3-alpha.1+mc26.3`, the version from the owner's failing log. It adapts
the built-in **Solstice RT** and **Seabreeze** terrain programs, including
Solstice's two-output material capture. Portable/Radiance fullscreen passes
continue through their existing path.

The adapter has passed pipeline contract checks and offline Vulkan/OpenGL
shader compilation/linking. A running Sodium + GreenMarine world on the
owner's Poco F8 Pro still needs acceptance; build evidence is not a measured
FPS gain or a claim of support for arbitrary Sodium versions/custom packs.

## Install and check on the phone

1. Replace the old mod with `greenmarine-0.8.2-prototype.jar`; keep only one
   GreenMarine JAR. Keep Minecraft 26.3, Java 25 and Fabric Loader 0.19.5+.
2. Use the same Sodium `0.9.3-alpha.1+mc26.3` and native Vulkan backend from
   the reported session. Existing GreenMarine settings are preserved.
3. Select Solstice RT from pause menu → GreenMarine. First reproduce the
   previous Medium/Rays Off test. The original attachment-count failure
   happened during raster material capture even with rays Off.
4. Then select the quality/ray settings used for the owner's successful
   no-Sodium RT test. Compare the same location, camera and resolution.
   Check the pack state and surface/world readiness; initialized hardware
   alone does not mean the current frame dispatched ray queries.
5. Exercise classic transparency and OIT, water/waves, foliage cutouts,
   block edits, world changes and resource reloads. Export the new session's
   log if a shader or binding fails. Preserve its complete first stack trace.

## How the fix works

Sodium submits a different packed vertex format and shader interface from
vanilla Minecraft. Replaying those draws into Solstice's two material
attachments previously bound Sodium's original pipeline unchanged. Renderpearl
correctly rejected the color target count.

The adapter observes immutable host definitions at the Renderpearl compile
boundary, with no references to Sodium Java classes in base APIs or entrypoints.
It recognizes the exact shader pair and ABI, including OIT pipelines whose
locations use the `minecraft:` namespace. Original packed vertex bindings,
uniform indices, topology, shader defines and 20-byte region push constants
are preserved. Existing vanilla behavior stays on its original path.

Sodium's own runtime `chunk_vertex.glsl` decodes the geometry. A small source
bridge maps that decoded data, atlas coordinates, light values, camera-relative
region offsets and visibility to the existing pack terrain interface. Raster
draws retain host attachments; material replay uses independent normal/depth
and albedo/material attachments plus private depth. There is no CPU vertex
conversion, mesh rebuild, GPU readback or extra full-world geometry copy.
The existing material replay draw itself still has a cost.

Only known compatible declarations are adapted. Unknown terrain capture
pipelines fail with their identifier rather than falsely reporting full
surface support. Other custom terrain programs require their own compatible
interface; this is not Iris-format pack compatibility.

## FPS expectations

Sodium may reduce CPU chunk drawing/submission overhead. That can raise FPS
when the renderer is CPU-limited. GPU-heavy ray tracing, reflection, lighting
and post-processing costs remain, and material replay still redraws terrain.
If the GPU is already the limit, FPS gains may be small; overhead can also
offset some savings. A frame cap may hide a reduction in frame time.

For a useful comparison, keep world, camera, render/simulation distance,
resolution, pack, quality, ray mode and FPS limit identical. Wait for chunk
loading and shader warmup, then compare sustained frame times and CPU/GPU
measurements at similar phone temperatures. Record scene and driver details.
The owner's earlier approximately 60 FPS no-Sodium observation is a baseline
for that configuration, not an estimate for the new combination.

## Developer verification

Use the matching upstream Sodium tag's `common/src/main/resources` directory:

```sh
GREENMARINE_TEST_SODIUM_RESOURCES=/path/to/sodium/common/src/main/resources \
./gradlew test build -PandroidNative=true -PandroidLinker=/path/to/android-clang
```

The shader test uses the actual Sodium include resources, the cached Minecraft
26.3 assets, `glslangValidator`, `spirv-val` and `spirv-dis`. It links 30 pairs:
Solstice raster/capture and Seabreeze raster, each in classic, cutout and all
three real Minecraft OIT variants, for Vulkan and OpenGL. It also inspects
packed vertex inputs and fragment output slots. Without the external Sodium
resource directory, only this optional integration test is skipped; Sodium
is not downloaded or made a required mod dependency by the base build.

Reference: [matching Sodium release source](https://github.com/CaffeineMC/sodium/tree/mc26.3-0.9.3-alpha.1).
