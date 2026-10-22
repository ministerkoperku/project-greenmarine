#version 330
#extension GL_ARB_separate_shader_objects : require
#ifndef OIT_ALPHA_ONLY
layout(location=0) out vec4 fragColor;
#endif
void main() {
    // Procedural clouds are supplied once by the explicit background program.
    discard;
}
