#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform MarineFrame {
    vec4 Frame;
    vec4 Context;
    mat4 InverseProjection;
    mat4 InverseViewRotation;
    vec4 Parameters[8];
};
uniform sampler2D ColorSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 source = texture(ColorSampler,texCoord);
    vec3 color = source.rgb * Parameters[0].x;
    float luma = dot(color,vec3(0.2126,0.7152,0.0722));
    color = mix(vec3(luma),color,Parameters[0].y);
    fragColor = vec4(clamp(color,0.0,1.0),source.a);
}
