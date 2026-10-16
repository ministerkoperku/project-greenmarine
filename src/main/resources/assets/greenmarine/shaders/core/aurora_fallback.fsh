#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:frame.glsl>
#include <greenmarine:voxel.glsl>
uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 scene=texture(ColorSampler,texCoord);float depth=texture(DepthSampler,texCoord).r;
    if(depth<=0.0) {fragColor=scene;return;}
    vec3 color=scene.rgb*marineParameter(0);
    fragColor=vec4(clamp(color,0.0,1.0),scene.a);
}
