#version 330
#extension GL_ARB_separate_shader_objects : require

struct PointLight {
    vec4 PositionRadius;
    vec4 ColorIntensity;
};
layout(std140) uniform GreenFx {
    vec4 Frame;
    vec4 Glow;
    mat4 InverseProjection;
    mat4 InverseViewRotation;
    PointLight Lights[16];
};
uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;
#ifdef GREENMARINE_VOXEL
layout(std140) uniform VoxelFrame {
    vec4 VolumeOriginSize;
    vec4 Tracing;
    uvec4 Occupancy[256];
};

bool occupied(ivec3 cell) {
    if (any(lessThan(cell,ivec3(0))) || any(greaterThanEqual(cell,ivec3(32)))) return false;
    int index = (cell.z * 32 + cell.y) * 32 + cell.x;
    int word = index / 32;
    return (Occupancy[word / 4][word % 4] & (1u << uint(index % 32))) != 0u;
}

float voxelVisibility(vec3 surface,vec3 normal,vec3 light) {
    if (Tracing.x < 0.5) return 1.0;
    vec3 origin = surface + normal * Tracing.w - VolumeOriginSize.xyz;
    vec3 delta = light - VolumeOriginSize.xyz - origin;
    float enter = 0.0, leave = 1.0;
    for (int axis=0;axis<3;axis++) {
        if (abs(delta[axis]) < 0.000001) {
            if (origin[axis] < 0.0 || origin[axis] >= 32.0) return 1.0;
        } else {
            float a = -origin[axis] / delta[axis], b = (32.0 - origin[axis]) / delta[axis];
            enter = max(enter,min(a,b)); leave = min(leave,max(a,b));
        }
    }
    if (enter >= leave) return 1.0;
    ivec3 step = ivec3(sign(delta));
    ivec3 cell = ivec3(floor(origin + delta * enter + sign(delta) * 0.0001));
    vec3 next, advance;
    for (int axis=0;axis<3;axis++) {
        if (abs(delta[axis]) < 0.000001) { next[axis] = 1e20; advance[axis] = 1e20; }
        else {
            next[axis] = (float(cell[axis]) + (step[axis] > 0 ? 1.0 : 0.0) - origin[axis]) / delta[axis];
            advance[axis] = abs(1.0 / delta[axis]);
        }
    }
    for (int i=0;i<96;i++) {
        if (occupied(cell)) return 0.0;
        float crossing = min(next.x,min(next.y,next.z));
        if (crossing >= leave) break;
        for (int axis=0;axis<3;axis++) if (next[axis] <= crossing + 0.000001) {
            cell[axis] += step[axis]; next[axis] += advance[axis];
        }
    }
    return 1.0;
}
#endif
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 source = texture(ColorSampler, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    float ndcDepth = Frame.z > 0.5 ? max(depth, 0.000001) : max(depth, 0.000001) * 2.0 - 1.0;
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, ndcDepth, 1.0);
    vec3 surface = (InverseViewRotation * vec4(view.xyz / view.w, 0.0)).xyz;
    vec3 normalCross = cross(dFdx(surface), dFdy(surface));
    vec3 normal = normalCross / max(length(normalCross), 0.00001);
    if (dot(normal, -surface) < 0.0) normal = -normal;
    vec3 illumination = vec3(0.0);
    if (depth > 0.000001) {
        for (int i = 0; i < int(Frame.w); i++) {
            vec3 delta = Lights[i].PositionRadius.xyz - surface;
            float distanceSquared = dot(delta,delta);
            float radius = Lights[i].PositionRadius.w;
            if (distanceSquared >= radius * radius) continue;
            float distance = sqrt(distanceSquared);
            float falloff = max(1.0 - distance / Lights[i].PositionRadius.w, 0.0);
            float diffuse = max(dot(normal, delta / max(distance, 0.0001)), 0.0);
            if (diffuse <= 0.0) continue;
#ifdef GREENMARINE_VOXEL
            if (i < int(Tracing.y)) diffuse *= voxelVisibility(surface,normal,Lights[i].PositionRadius.xyz);
#endif
            illumination += Lights[i].ColorIntensity.rgb * Lights[i].ColorIntensity.a * falloff * falloff * diffuse;
        }
    }
    vec3 bloom = vec3(0.0);
    float weightSum = 0.0;
    if (Glow.y > 0.0) for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float weight = (x == 0 && y == 0) ? 4.0 : 1.0;
            vec2 offset = vec2(x, y) * Glow.z / Frame.xy;
            bloom += max(texture(ColorSampler, texCoord + offset).rgb - vec3(Glow.x), 0.0) * weight;
            weightSum += weight;
        }
    }
    vec3 color = source.rgb + max(source.rgb, vec3(0.08)) * illumination * 2.0 + bloom / max(weightSum,1.0) * Glow.y;
    fragColor = vec4(color, source.a);
}
