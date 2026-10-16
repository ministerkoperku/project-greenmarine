#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:solstice_scene.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    fragColor=vec4(solScene(texCoord),texture(SceneSampler,texCoord).a);
}
