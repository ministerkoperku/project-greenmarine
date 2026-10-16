#ifndef GREENMARINE_SOLSTICE_RAYS
#define GREENMARINE_SOLSTICE_RAYS
#include <greenmarine:rays.glsl>
float solRayCoverage(vec3 position) {
    vec3 edge=min(position-RayBoundsMin.xyz,RayBoundsMax.xyz-position);
    return smoothstep(0.0,8.0,min(edge.x,min(edge.y,edge.z)));
}
#endif
