#ifndef GREENMARINE_SURFACE_RAYS_V1
#define GREENMARINE_SURFACE_RAYS_V1
layout(std140) uniform RaySurfaceFrame {
    vec4 SurfaceRayInfo; // Ready, output width, output height, finite ray reach.
};
uniform sampler2D MarineSurfaceNormalDepth;
uniform sampler2D MarineSurfaceAlbedo;
uniform sampler2D RaySurfaceLighting;
uniform sampler2D RayReflection;
bool marineSurfaceRayReady() { return SurfaceRayInfo.x>.5; }
#endif
