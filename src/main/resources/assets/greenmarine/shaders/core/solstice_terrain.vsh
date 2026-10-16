#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#include <greenmarine:solstice.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif
layout(location=0) in vec3 Position;
layout(location=1) in vec4 Color;
layout(location=2) in vec2 UV0;
layout(location=3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location=4) in ivec3 ChunkPosition;
layout(location=5) in float ChunkVisibility;
#endif
#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif
layout(location=0) out float sphericalVertexDistance;
layout(location=1) out float cylindricalVertexDistance;
layout(location=2) out vec4 vertexColor;
layout(location=3) out vec2 texCoord0;
layout(location=4) out float chunkVisibility;
layout(location=5) out vec3 surfacePosition;
layout(location=6) out vec4 surfaceColor;
layout(location=7) out vec2 surfaceLight;
layout(location=8) out float surfaceWave;
void main() {
    vec3 position=Position+(ChunkPosition-CameraBlockPos)+CameraOffset;
    bool water=SurfaceInfo.x>.5 && marineWater(UV0);
    surfaceWave=water?marineWaterWave(position.xz)*marineParameter(SOL_WAVES):0.0;
    position.y+=surfaceWave;
    surfacePosition=position;surfaceColor=Color;surfaceLight=clamp(vec2(UV2)/240.0,0.0,1.0);
    gl_Position=ProjMat*ModelViewMat*vec4(position,1.0);
    sphericalVertexDistance=fog_spherical_distance(position);
    cylindricalVertexDistance=fog_cylindrical_distance(position);
#ifndef OIT_ALPHA_ONLY
    vertexColor=Color*sample_lightmap(Sampler2,UV2);
#else
    vertexColor=Color;
#endif
    texCoord0=UV0;
    float distance=length(position);
    chunkVisibility=mix(1.0,ChunkVisibility,clamp((distance-16.0)/16.0,0.0,1.0));
}
