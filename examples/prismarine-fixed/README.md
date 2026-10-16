# Prismarine Showcase Corrected

Use GreenMarine 0.6+, install `prismarine-showcase-corrected-0.6.0-prototype.zip`
as a resource pack and select **Prismarine Showcase Corrected** in F6.
The original owner archive is unchanged. The corrected shaders use the separate
`prismarine_fixed` resource namespace, so both archives can remain enabled.

The hardware pass treats RayLighting as irradiance and multiplies actual scene
color by it. It matches ray/raster world planes and fades near the finite RT scene
boundary to the same global lighting baseline used for distant terrain. Composition
preserves color in highlights. OpenGL/voxel fallbacks retain voxel GI and bloom.
The Lua script is intentionally empty; this version does not create orbiting lights
attached to the camera. Fragment sources use GLSL; no obsolete compose binary is included.

This remains a fullscreen demonstration. For actual water vertex displacement,
refraction/absorption, terrain shading, sky and clouds use **Seabreeze World Shaders**.
Native RT covers loaded baked block-model geometry; water and entities are rasterized.
F6 reports Loading, Loaded, Fallback or Failed. Diagnostics show raw RT albedo (1),
normal (2), depth (3) and irradiance (4); these buffers intentionally expose finite RT
coverage rather than the final globally shaded scene.
