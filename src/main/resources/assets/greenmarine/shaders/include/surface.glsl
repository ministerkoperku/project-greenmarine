#ifndef GREENMARINE_SURFACE_V1
#define GREENMARINE_SURFACE_V1
#include <greenmarine:frame.glsl>
layout(std140) uniform MarineSurfaceFrame {
    vec4 SurfaceInfo; // Opaque readiness, rain, reserved, reserved.
    vec4 SurfaceOrigin; // Physical camera position modulo 4096.
    vec4 SurfaceSun; // World direction and strength.
    vec4 SurfaceSky; // Linear sky color and rain brightness.
    vec4 SurfaceWater[3]; // Still, flowing and overlay atlas UV rectangles.
    vec4 SurfaceEye; // Perspective eye offset caused by bob/hurt transforms.
};
uniform sampler2D MarineOpaqueColor;
uniform sampler2D MarineOpaqueDepth;
bool marineWater(vec2 uv) {
    for(int i=0;i<3;i++) {
        vec4 rect=SurfaceWater[i];
        if(all(greaterThanEqual(uv,rect.xy)) && all(lessThanEqual(uv,rect.zw))) return true;
    }
    return false;
}
float marineWaterWave(vec2 relativePosition) {
    // Integer harmonics of 4096 preserve phase when the wrapped camera origin changes.
    vec2 p=relativePosition+SurfaceOrigin.xz;
    return sin(dot(p,vec2(157.0,47.0))*(6.28318530718/4096.0)+Frame.z*1.25)*.055
        +sin(dot(p,vec2(-61.0,233.0))*(6.28318530718/4096.0)-Frame.z*.8)*.025;
}
#endif
