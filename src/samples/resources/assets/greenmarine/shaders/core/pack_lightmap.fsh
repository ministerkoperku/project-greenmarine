#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_GOOGLE_include_directive : require
#include <greenmarine:frame.glsl>
uniform sampler2D ColorSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec3 source = texture(ColorSampler, texCoord).rgb;
    float warmth = Parameters[0].z;
    vec3 tint = vec3(1.0, 1.0 - warmth * 0.25, 1.0 - warmth * 0.6);
    vec3 ambient = vec3(Parameters[0].w) * (1.0 - texCoord.y);
    fragColor = vec4(clamp(source * tint + ambient, 0.0, 1.0), 1.0);
}
