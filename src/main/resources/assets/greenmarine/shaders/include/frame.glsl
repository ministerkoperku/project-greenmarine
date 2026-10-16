#ifndef GREENMARINE_FRAME_V1
#define GREENMARINE_FRAME_V1
layout(std140) uniform MarineFrame {
    vec4 Frame;
    vec4 Context;
    mat4 InverseProjection;
    mat4 InverseViewRotation;
    vec4 Parameters[8];
};
float marineParameter(int index) { return Parameters[index / 4][index % 4]; }
vec3 marineViewPosition(vec2 uv,float depth) {
    float z = Context.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = InverseProjection * vec4(uv * 2.0 - 1.0,z,1.0);
    return p.xyz / p.w;
}
vec3 marineRelativeWorldPosition(vec2 uv,float depth) {
    return (InverseViewRotation * vec4(marineViewPosition(uv,depth),0.0)).xyz;
}
#endif
