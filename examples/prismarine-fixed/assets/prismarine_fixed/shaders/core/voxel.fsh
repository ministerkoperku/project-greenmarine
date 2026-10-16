#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_GOOGLE_include_directive : require
#include <greenmarine:frame.glsl>
#include <greenmarine:voxel.glsl>
uniform sampler2D DepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (depth <= 0.0 || VoxelTracing.x < 0.5) { fragColor = vec4(0.0); return; }
    vec3 position = marineRelativeWorldPosition(texCoord, depth);
    vec3 dx = dFdx(position), dy = dFdy(position);
    vec3 product = cross(dx, dy);
    float magnitude = dot(product, product);
    if (magnitude < 0.0000001) { fragColor = vec4(0.0); return; }
    vec3 normal = product * inversesqrt(magnitude);
    if (dot(normal, -position) < 0.0) normal = -normal;
    vec3 tangent = normalize(cross(abs(normal.y) < 0.95 ? vec3(0.0,1.0,0.0) : vec3(0.0,0.0,1.0), normal));
    vec3 bitangent = cross(normal, tangent);
    ivec3 cell = ivec3(floor(position - normal * 0.08 - VoxelOriginSize.xyz));
    vec3 albedo = pow(marineVoxelMaterial(cell).rgb, vec3(2.2));
    if (dot(albedo, albedo) < 0.00001) albedo = vec3(0.5);
    int count = Context.y >= 3.0 ? 8 : 4;
    vec3 incident = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        if (i >= count) break;
        float radius = sqrt((float(i) + 0.5) / float(count)), angle = float(i) * 2.39996323;
        vec3 direction = tangent * (radius * cos(angle)) + bitangent * (radius * sin(angle)) + normal * sqrt(1.0 - radius * radius);
        MarineVoxelHit hit = marineVoxelTrace(position + normal * 0.08, direction, 12.0);
        if (hit.hit && hit.material.a > 0.0) incident += pow(hit.material.rgb, vec3(2.2)) * hit.material.a;
    }
    vec3 radiance = albedo * incident * (marineParameter(5) / float(count));
    float distance = -marineViewPosition(texCoord, depth).z;
    fragColor = vec4(radiance, distance);
}
