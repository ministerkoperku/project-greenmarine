#version 450
#extension GL_GOOGLE_include_directive : require
#include <greenmarine:frame.glsl>
// Adapted from the engine's ComputeReflectionTest and COMPUTE.md binding examples.
layout(local_size_x = 8, local_size_y = 4) in;
layout(rgba16f) uniform writeonly image2D Computed;
layout(std430) buffer Statistics { uint processedPixels; };
uniform sampler2D SceneSampler;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(Computed);
    if (any(greaterThanEqual(p, size))) return;
    vec2 uv = (vec2(p) + 0.5) / vec2(size);
    vec4 color = texture(SceneSampler, uv);
    imageStore(Computed, p, vec4(color.rgb * vec3(1.03, 1.0, 0.98), color.a));
    // Persistent modulo-2^32 counter, cleared on reload/pack change (not every tick).
    atomicAdd(processedPixels, 1u);
}
