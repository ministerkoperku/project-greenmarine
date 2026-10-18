#ifndef GREENMARINE_SODIUM_TERRAIN
#define GREENMARINE_SODIUM_TERRAIN
// Sodium supplies the original uniform layout and packed vertex decoder.
#include <sodium:globals.glsl>
#define UseRgss (u_UseRGSS ? 1 : 0)
#define TextureSize (vec2(1.0) / u_TexelSize)
#define Sampler0 u_BlockTex
#define Sampler2 u_LightTex
#define ProjMat u_ProjectionMatrix
#define ModelViewMat u_ModelViewMatrix

#ifdef GREENMARINE_SODIUM_VERTEX
#include <sodium:chunk_vertex.glsl>
uniform isamplerBuffer u_SectionTimeInfo;
#ifdef VULKAN
layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

vec3 marineSodiumTranslation() {
    // LocalSectionIndex stores X at bit 5, Y at bit 0 and Z at bit 2.
    uvec3 section = (uvec3(_draw_id) >> uvec3(5u, 0u, 2u)) & uvec3(7u, 3u, 7u);
    return u_RegionOffset + vec3(section) * 16.0;
}
float marineSodiumVisibility() {
#ifdef USE_FOG
    int created = texelFetch(u_SectionTimeInfo, int(u_RegionID * 256u + _draw_id)).r;
    return created < 0 ? 1.0 : clamp(float(u_CurrentTime - created) * u_FadePeriodInv, 0.0, 1.0);
#else
    return 1.0;
#endif
}
#define Position _vert_position
#define Color _vert_color
#define UV0 (_vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink)
#define UV2 ivec2(round(_vert_tex_light_coord * 256.0 - 8.0))
#define ChunkPosition ivec3(0)
#define CameraBlockPos ivec3(0)
#define CameraOffset marineSodiumTranslation()
#define ChunkVisibility marineSodiumVisibility()
#endif
#endif
