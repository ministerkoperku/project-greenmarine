#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_GOOGLE_include_directive : require
#include <greenmarine:frame.glsl>
uniform sampler2D ColorSampler;
uniform sampler2D SceneSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 effect = texture(ColorSampler, texCoord);
    vec2 pixel = 1.0 / vec2(textureSize(SceneSampler, 0));
    vec3 bright = vec3(0.0);
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        bright += max(texture(SceneSampler, texCoord + vec2(x, y) * pixel * 2.0).rgb - vec3(0.75), vec3(0.0));
    }
    vec3 bloom=bright*(marineParameter(4)/9.0);
    if(Context.z==3.0) fragColor=vec4(effect.rgb,dot(bloom,vec3(.2126,.7152,.0722)));
    else fragColor=vec4(effect.rgb+bloom,effect.a);
}
