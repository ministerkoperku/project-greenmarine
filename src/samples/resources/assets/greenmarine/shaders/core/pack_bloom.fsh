#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D ColorSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec2 pixel = 1.0 / vec2(textureSize(ColorSampler,0));
    vec3 bright = vec3(0.0);
    for (int y=-1;y<=1;y++) for (int x=-1;x<=1;x++) {
        bright += max(texture(ColorSampler,texCoord + vec2(x,y)*pixel*2.0).rgb - vec3(0.7),vec3(0.0));
    }
    fragColor = vec4(bright/9.0,1.0);
}
