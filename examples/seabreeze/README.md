# Seabreeze World Shaders

Requires GreenMarine 0.6 or newer. Enable the resource pack, then select
**Seabreeze World Shaders** in F6. Vulkan uses real ray queries when the host
features and loaded geometry are ready. OpenGL and Rays Off retain all water,
terrain, sky and cloud programs with raster lighting.

Water waves displace actual terrain vertices. Refraction samples an independent
opaque color/depth snapshot; absorption depends on reconstructed water thickness.
Reflections use the sky and sun, not traced reflection rays. Native rays currently
trace loaded baked block-model geometry in a finite scene; fluids, entities,
biome-tinted RT albedo and distant geometry are not part of that scene.

The F6 water controls update without rebuilding world pipelines. Shader sources
are included in assets; this is a GreenMarine pack, not an Iris-format pack.
