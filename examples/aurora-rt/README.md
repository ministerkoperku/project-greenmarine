# Aurora RT example

Requires Minecraft 26.3 and GreenMarine 0.5+. Place the ZIP in resourcepacks,
enable it in Minecraft, then open F6 and select `aurora:rt_example` with
Hardware or Auto rays. Loader 0.19.5+, Java 25 and native access apply.

Vulkan hardware ray queries require the complete enabled host feature set and
GreenMarine's Linux x86_64 native library. Unsupported devices/OpenGL use an
explicitly reported portable exposure fallback. `LOADED` confirms the pack's
selected shaders submitted with ready RT data; `FALLBACK` means no hardware
ray dispatch, and `FAILED` includes a reason. Hardware capability alone is
insufficient. This is a GreenMarine pack, not an Iris/OptiFine pack.

Native BLAS/TLAS contain loaded baked model triangles, atlas UVs, cutout tests
and emission. Four diffuse rays and a sun shadow query use real world geometry,
including offscreen models. The 96x64x96 block scene moves on a stable world
grid. Shadows/GI blend into the shared global lighting baseline over eight
blocks at the scene edge. RT shadow coverage is finite; entities, liquids,
special block renderers and distant geometry remain vanilla.

Exposure, sunStrength, giStrength and blend are editable in F6. Diagnostic
0 is normal, 1 is model albedo, 2 is model normals, 3 is ray/raster alignment:
green matched, red unmatched (including clouds and cutout silhouettes).
Half-resolution primary tracing can still shimmer on thin alpha silhouettes.
This is diffuse lighting and shadows, not multi-bounce path tracing/reflections.

Edit the JSON/Lua here. `./gradlew exampleShaderpack` copies the actual built-in
GLSL into the ZIP under assets/aurora/shaders/core, avoiding a divergent shader
copy. Shader includes are provided by GreenMarine, and MarineFrame remains v1.
