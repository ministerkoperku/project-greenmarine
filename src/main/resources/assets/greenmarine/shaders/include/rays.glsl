#ifndef GREENMARINE_WORLD_RAYS_V1
#define GREENMARINE_WORLD_RAYS_V1
layout(std140) uniform RayFrame {
    vec4 RayInfo;
    vec4 RayBoundsMin;
    vec4 RayBoundsMax;
    vec4 RaySun;
    vec4 RayOptions; // Ambient, GI strength, scene age seconds (0..1), scene present.
};
uniform sampler2D RayLighting;
uniform sampler2D RayNormalDepth;
uniform sampler2D RayAlbedo;
bool marineHardwareRayReady() { return RayInfo.x > 0.5; }
#endif
