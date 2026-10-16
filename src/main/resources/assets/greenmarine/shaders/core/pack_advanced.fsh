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
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

vec3 viewPosition(vec2 uv,float depth) {
    float z = Context.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = InverseProjection * vec4(uv*2.0-1.0,z,1.0);
    return p.xyz / p.w;
}
void main() {
    vec4 source = texture(SceneSampler,texCoord);
    vec3 color = source.rgb * Parameters[0].x;
    if (Context.y >= 2.0) {
        float depth = texture(DepthSampler,texCoord).r;
        if (depth > 0.000001) {
            vec3 center = viewPosition(texCoord,depth);
            vec2 pixel = 1.0 / vec2(textureSize(DepthSampler,0));
            float occlusion = 0.0;
            for (int i=0;i<8;i++) {
                float angle = float(i)*0.7853981634;
                vec2 uv = texCoord + vec2(cos(angle),sin(angle))*pixel*4.0;
                float neighborDepth = texture(DepthSampler,uv).r;
                if (neighborDepth > 0.000001) {
                    vec3 neighbor = viewPosition(uv,neighborDepth);
                    float separation = neighbor.z-center.z;
                    occlusion += smoothstep(0.03,0.15,separation)*(1.0-smoothstep(0.2,1.0,length(neighbor-center)));
                }
            }
            color *= 1.0 - Parameters[1].y*occlusion/8.0;
        }
        color += texture(ColorSampler,texCoord).rgb*Parameters[1].x;
    }
    float luma = dot(color,vec3(0.2126,0.7152,0.0722));
    fragColor = vec4(clamp(mix(vec3(luma),color,Parameters[0].y),0.0,1.0),source.a);
}
