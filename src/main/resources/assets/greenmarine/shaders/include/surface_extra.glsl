#ifndef GREENMARINE_SURFACE_EXTRA_V1
#define GREENMARINE_SURFACE_EXTRA_V1
// Reduced-resolution companion of RaySurfaceLighting. x = local ambient occlusion,
// y = sunlit fraction of the camera ray, z = marched length in blocks (0 = none),
// w = 1 + indoor factor when written. Unavailable outputs are all zero.
uniform sampler2D RaySurfaceExtra;
float marineSurfaceOcclusion(vec4 extra) { return mix(1.0,extra.x,clamp(extra.w,0.0,1.0)); }
#endif
