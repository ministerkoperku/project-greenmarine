#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:frame.glsl>
void main() {
#ifdef VULKAN
    int index=gl_VertexIndex;
#else
    int index=gl_VertexID;
#endif
    vec2 uv=vec2((index<<1)&2,index&2);
    // Both host backends use reversed depth; clip-space conventions differ.
    gl_Position=vec4(uv*2.0-1.0,Context.x>.5?0.0:-1.0,1.0);
}
