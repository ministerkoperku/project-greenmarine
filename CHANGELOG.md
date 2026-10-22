# Changelog

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
