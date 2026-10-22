#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform ActorAtlasBlit {
    vec4 Destination;
    vec4 Sizes;
};
layout(location=0) out vec2 sourceUv;
void main() {
    const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
#ifdef VULKAN
    vec2 corner=corners[gl_VertexIndex];
#else
    vec2 corner=corners[gl_VertexID];
#endif
    vec2 pixel=Destination.xy+corner*Destination.zw;
    gl_Position=vec4(pixel/Sizes.xy*2.0-1.0,0.0,1.0);
    sourceUv=(corner*(Sizes.zw+4.0)-2.0)/Sizes.zw;
}
