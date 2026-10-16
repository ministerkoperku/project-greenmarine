#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>
#include <greenmarine:solstice.glsl>
layout(location=0) in float vertexDistance;
layout(location=1) in vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location=0) out vec4 fragColor;
#endif
void main() {
    vec4 color=vertexColor;
    color.rgb*=mix(vec3(.32,.42,.64),vec3(.95,.94,.88),solDay());
    color.rgb*=1.0-Environment.y*.48;
    color.rgb+=Climate.z*.4;
    color.a*=clamp(marineParameter(SOL_CLOUDS),0.0,1.0)*.6;
#ifndef OIT_DEPTH_BOUNDS
    color.a*=1.0-linear_fog_value(vertexDistance,0,FogCloudsEnd);
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z,color.a);
#else
#ifdef OIT_ACCUMULATE
    color=sampleColorForAccumulation(color);
#endif
    fragColor=color;
#endif
}
