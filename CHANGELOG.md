# Changelog

## 0.9.11-prototype

- Compute API: GreenMarine packs declare persistent `resources` (storage images and buffers) and `"stage":"compute"` passes (`.csh`, `groups`: screen / image / fixed) whose shaders bind resources purely by name; scene passes sample pack images through `inputs`. Java: `RenderSession.compute/storageTexture/storageBuffer/dispatch`, graph `storageTexture` + `PassContext.dispatch`, `MarineRenderer.computeCapabilities()`. Vulkan only, reported only after a pipeline is built on the host device; `"requires":"compute"` selects fallbacks elsewhere. See [compute](docs/COMPUTE.md).
- Raster storage: fragment/vertex programs read and write storage buffers and images (`ShaderProgram.withStorage`, `Material.storageBuffer/storageImage`); pack scene passes bind pack buffers/images by name. Optional `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics` and format-less storage image features are requested and reported only when enabled.

## 0.9.10-prototype

- Added CPU material addon API1 and editable PNG+JSON per-texture materials from mods, resource packs and optional shaderpack manifests.
- Solstice applies masked roughness/reflection/emission/classes with explicit LabPBR precedence; external renderers can prepare material uploads before capture passes.

## 0.9.9-prototype

- Solstice supplies conservative texture-family PBR defaults for albedo-only packs, with bounded matte roughness variation and existing procedural relief. Explicit LabPBR maps, builtin profiles, water and transparent materials remain authoritative.

## 0.9.8

- Fixed LabPBR maps being copied into atlas gutters instead of the visible block UV rectangles on Minecraft 26.3.
- Explicit material-map presence preserves valid black specular texels and procedural relief on unmapped blocks.
- Solstice uses LabPBR dielectric F0 for sun highlights and reflections. CPU atlas images are released after staging uploads.
- Added opt-in Vulkan material tests and an isolated Minecraft probe covering resource-pack removal and restoration.

## 0.9.7

- Solstice RT: ray-traced volumetric sunlight. The native pass marches each reduced
  camera ray with shadow rays (4/6/8/12 steps by quality) and the scene pass adds
  forward-scattered sun shafts (`volumetric`).
- Ray-traced ambient occlusion from the existing GI rays darkens sky ambient and,
  partially, block light in corners, under canopies and overhangs (`occlusion`).
- Every surface gets a GGX sun highlight shadowed by the traced sun ray
  (`specular`), and texture relief perturbs shading normals from atlas luminance
  (`relief`). These are procedural; resource pack normal/specular maps are not read.
- Optional native export `greenmarine_surface_rt_record_extra` with a third output
  (`RaySurfaceExtra`, include `surface_extra.glsl`); older natives fall back to the
  previous two-output call.
- `surfaceDiagnostic` 4, 5 and 6 show traced occlusion, sunlit ray fraction and raw
  reflection hits / native sun visibility.
- Solstice sky and light use a single-scattering atmosphere (Rayleigh + Mie, air mass): blue
  noon sky, visible sun disc, orange/red sunsets that also tint terrain light and fog, darker
  nights. Fog is thinner and takes the sky colour of its direction; cloud coverage defaults to 0.5.
- `weather` (replaces `waveDiagnostic`, same slot): 0 auto gives every in-game day its own mood
  from clear to overcast, 1-4 pin clear, hazy, cloudy, overcast. Wave heights moved to
  `surfaceDiagnostic` 7.
- Rain wetness builds over the rain's length and dries over minutes; puddles grow from noise
  hollows with wetness, ripple while it rains, and lingering wetness thickens fog.
- Indoor volumetric light: with no sky light at the camera the air is denser, so window beams show.
- Block-light shadow rays ignore alpha-tested geometry (grass around a held torch).
- Distant Horizons water matches near Solstice water (no biome hue boost, nominal floor seen
  through 16 blocks, 0.9 opacity) and greenish swamp water is recognised.
- Metals render as metal: no diffuse term, the traced reflection tinted by albedo Fresnel,
  roughness blurring towards the surface's irradiance; `reflection` scales metalness.
- LabPBR loader (`PbrAtlas`, `labpbr.glsl`): `_s`/`_n` maps from resource packs laid out like
  the block atlas, overriding roughness/metal/class in capture and shading, normal map + AO,
  emission. Not working yet: the shader reads zeros from the uploaded maps (see PbrAtlas).
  Without packs it is inert (1x1 placeholders). `surfaceDiagnostic` 9 shows what it reads.
- `surfaceDiagnostic` 8 shows height-field sun visibility.
- Sun shadows beyond the traced scene from a 512x512 column height field (distant mountains no
  longer let sunlight through until they snap into the scene); Distant Horizons LODs use it too.
- The traced scene spans two sections below and above the camera's, so rising a few blocks no
  longer drops the ground and every shadow.
- LOD and sky ray directions use two points on the pixel ray: view bobbing no longer swings LOD
  positions by hundreds of blocks (LODs darkening while walking).
- DH 3.3 writes LOD depth into the host buffer; LODs are relit unless a chunk is clearly nearer.
- Fixed surfaces closer than about one block dropping out of the material capture
  (reflections turning into sky, near walls losing RT light): the derivative normal
  underflowed its normalization floor.

## 0.9.6

- External renderer API (`ExternalRenderers`): meshes from other mods are ray traced
  (shadows, reflections, GI) through per-mesh BLAS and a per-frame instance TLAS;
  a raw Vulkan hook into the material capture pass; RT output views for external
  forward shading; `external_surface.glsl` reference shading.
- Material capture pass renamed so renderers that match world passes by label do
  not inject into it.
- GPU tests choose their device with `GREENMARINE_GPU_TEST_DEVICE`.
- Documentation rewritten around the current state.

## 0.9.5

- Solstice RT: the local player casts a native sun shadow, in first person too.
- KAvatar reflections fixed (actor atlas pipeline identifiers, Vulkan vertex index).
- Release jar ships Marine Raster, Seabreeze and Solstice RT; demo packs moved to
  `src/samples`, saved selections of them migrate to Raster.

## 0.9.4

- Diffuse GI uses fixed cosine directions instead of a per-quarter-block random
  rotation, removing lighting jumps while the camera moves.
- Extended 64-box actor ABI and a combined texture atlas for KAvatar accessories.

## 0.9.3

- Reconstruction tracks partial producer support, reducing edge popping.
- Coated dielectric profiles for exposed/weathered/oxidized copper.
- Optional KAvatar body capture.

## 0.9.2

- Material capture and native tracing run before terrain colour; terrain composes
  RT lighting and reflections once.
- Local player body in native reflections.

## 0.9.1

- Reflection reconstruction from actual capture texel centres; grazing-angle
  footprint handling. Diamond and netherite material profiles.

## 0.9.0

- Indexed and instanced meshes, opt-in transient graph texture reuse, cached world
  bindings, paginated touch settings with CPU/GPU timings.

## 0.8.x

- 0.8.9: Solstice quality budgets (quarter-resolution RT at Low/Medium).
- 0.8.8: consistent world depth for post effects (gizmo overlays).
- 0.8.7: porous materials, filtered clouds, output dither, rough-reflection filter.
- 0.8.6: single procedural sky/cloud background stage.
- 0.8.5: metal reflection colour, validated reduced ray samples.
- 0.8.4: Android native delivery fix, glass/ice/metal materials, voxel sun shadows.
- 0.8.3: Marine Raster default pack, external folder/ZIP packs, pack CLI.
- 0.8.2: Sodium terrain adapter.
- 0.8.1: Android and Linux ARM64 native bundles, pause-menu settings.
- 0.8.0: named scene-pass inputs, covered scene publication, worker scene assembly.

## Earlier

- 0.7: Solstice RT, surface rendering API, temporal history.
- 0.6: world shader programs, Seabreeze.
- 0.5: native Vulkan hardware world rays.
