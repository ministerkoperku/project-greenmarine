#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform MarineFrame {
    vec4 Frame;
    vec4 Context;
    mat4 InverseProjection;
    mat4 InverseViewRotation;
    vec4 Parameters[8];
};
layout(std140) uniform RayFrame { vec4 RayInfo; };
uniform sampler2D ColorSampler;
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D RayNormalDepth;
uniform sampler2D RayAlbedo;
uniform sampler2D RayLighting;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

float marineParameter(int index) { return Parameters[index / 4][index % 4]; }
vec3 viewPosition(vec2 uv, float depth) {
    float z = Context.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = InverseProjection * vec4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}
float luminance(vec3 color) { return dot(color, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    float diagnostic = marineParameter(9);
    if (diagnostic > 0.5 && diagnostic < 1.5) { fragColor = vec4(pow(max(texture(RayAlbedo, texCoord).rgb, vec3(0.0)), vec3(1.0 / 2.2)), 1.0); return; }
    if (diagnostic > 1.5 && diagnostic < 2.5) { fragColor = vec4(texture(RayNormalDepth, texCoord).xyz * 0.5 + 0.5, 1.0); return; }
    if (diagnostic > 2.5 && diagnostic < 3.5) { fragColor = vec4(vec3(texture(RayNormalDepth, texCoord).w * 12.0), 1.0); return; }
    if (diagnostic > 3.5) { fragColor = vec4(pow(max(texture(RayLighting, texCoord).rgb, vec3(0.0)), vec3(1.0 / 2.2)), 1.0); return; }

    vec4 scene = texture(SceneSampler, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    vec4 effect = texture(ColorSampler, texCoord);
    bool high = Context.y >= 2.0;
    float shadow = (Context.z == 3.0 && high) ? clamp(effect.a, 0.0, 1.0) : 1.0;

    vec3 linear = pow(max(scene.rgb, vec3(0.0)), vec3(2.2));
    bool hardware = Context.z == 3.0;
    vec3 color = high ? (hardware ? linear*max(effect.rgb,vec3(0))+vec3(max(effect.a,0)) : linear+max(effect.rgb,vec3(0))) : linear;
    color *= marineParameter(0);
    color /= max(1.0,max(color.r,max(color.g,color.b)));
    float luma = luminance(color);
    color = mix(vec3(luma), color, marineParameter(1));

    float fog = marineParameter(8);
    if (depth > 0.0 && fog > 0.0) {
        float distance = -viewPosition(texCoord, depth).z;
        float amount = clamp(1.0 - exp(-max(distance - 24.0, 0.0) * 0.08), 0.0, 1.0) * fog;
        color = mix(color, vec3(luminance(color) * 0.9 + 0.05), amount);
    }
    vec2 centered = texCoord * 2.0 - 1.0;
    color *= mix(1.0, 1.0 - dot(centered, centered) * 0.35, marineParameter(3));
    float warmth = marineParameter(2);
    color *= vec3(1.0, 1.0 - warmth * 0.08, 1.0 - warmth * 0.18);
    color = pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
    fragColor = vec4(clamp(color, 0.0, 1.0), scene.a);
}
