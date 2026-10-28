# Changelog

## 0.9.11-prototype

- Registered block emitters no longer contribute a second emissive image through
  sparse GI rays. Local-light shadows/colour stay active; unknown and external
  emitters retain their fallback. Skip albedo sampling for zero-radiance bounces.
- GI with missing atlas mipmaps: use a bounded, UV-independent four-sample colour
  estimate when mip4 is unavailable. An atlas forced to mip0 no longer projects
  detailed emitter textures onto neighbouring wood and stone; reflections retain detail.
- Render-thread CPU follow-up from a fullpack Spark profile: memoize biome tint per
  block/tint index and serialize each nearby actor cube once. Rejected or throwing
  model captures roll back their complete payload without leaving partial actors.
- Grass shadow stability: remove the GI/AO filter that shifted its window at each
  native 8x8 workgroup boundary. Primary sun shadows now filter binary cutout alpha
  over the receiver pixel footprint instead of switching whole reduced pixels on/off.
  Opaque casters and legacy translucent-material transmission keep their existing
  behavior; no additional shadow rays or native ABI layout changes.
- Document AI-assisted development in the root README.
- Distant Horizons startup: discover RenderUtil without initializing its cached
  renderer definition, and defer clip queries until a matching DH depth texture
  exists. Early GreenMarine frames no longer freeze DH's renderer reference to null.
- Diffuse GI now accesses the averaged atlas mip requested by the bounce shader;
  the native surface sampler previously clamped every lookup to mip zero, projecting
  detailed block textures into indirect light. Reflection and alpha lookups retain LOD0.
- Solstice daylight haze: clear outdoor air no longer uses the dense indoor shaft
  scale. Scattering at equal path length uses the same response for sky and terrain,
  removing flat white foliage silhouettes and excessive landscape washout; indoor
  terrain-beam density/strength are preserved.
- Solstice corner/performance follow-up: renormalize lighting on each material face
  without restoring an unshadowed lightmap at the corner. Local shadows terminate
  on the first blocker and use two area-light taps plus a shadow-only actor path;
  moonlight no longer schedules invisible sun shafts. High now traces one native
  pixel per 3x3 viewport region, Ultra per 2x2; external packs retain half resolution.
  Water has stronger spectral extinction. Stars use angular point kernels gathered
  across cell/cube boundaries instead of a stretched planar pattern.
- Solstice lighting follow-up: GI misses no longer add sky light to caves; hemisphere
  sampling no longer rotates with the screen pixel pattern. Sun shadow solutions
  blend continuously at zero RT coverage, distant columns exclude leaves, and wider
  High/Ultra lighting reconstruction crosses coplanar material seams without holes.
  Local lights use small area emitters and actor occlusion, including thin parts near
  a source; partially blocked lights cannot suppress a stronger visible candidate.
  Dropped emissive items and interpolated burning-entity positions supply terrain
  light. Scene-only packs initialize their shared height-field binding before drawing.
- Solstice RT: reduced-resolution volumetric sun shafts use 8/12/16/24 steps by quality, a
  median+tent filter that rejects isolated fireflies, and the height field beyond the traced
  scene. Shadow rays that escape the finite scene into a mountain or tunnel wall no longer
  light the air as if they reached the sun, while cave-mouth beams keep working.
- Compute API: GreenMarine packs declare persistent `resources` (storage images and buffers) and `"stage":"compute"` passes (`.csh`, `groups`: screen / image / fixed) whose shaders bind resources purely by name; scene passes sample pack images through `inputs`. Java: `RenderSession.compute/storageTexture/storageBuffer/dispatch`, graph `storageTexture` + `PassContext.dispatch`, `MarineRenderer.computeCapabilities()`. Vulkan only, reported only after a pipeline is built on the host device; `"requires":"compute"` selects fallbacks elsewhere. See [compute](docs/COMPUTE.md).
- Raster storage: fragment/vertex programs read and write storage buffers and images (`ShaderProgram.withStorage`, `Material.storageBuffer/storageImage`); pack scene passes bind pack buffers/images by name. Optional `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics` and format-less storage image features are requested and reported only when enabled.
- Fixed the player losing every shadow while surface RT frames are skipped (busy submission slots during scene rebuilds): the vanilla blob now returns for skipped frames. Logs when the camera nears the edge of a lagging RT scene and when surface RT skips frames, for the shadows-while-moving report.

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
