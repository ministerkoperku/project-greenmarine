#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <greenmarine:solstice.glsl>
layout(location=0) in float sphericalVertexDistance;
layout(location=1) in float cylindricalVertexDistance;
layout(location=0) out vec4 fragColor;
void main() {
    vec2 uv=gl_FragCoord.xy/Frame.xy;
    vec3 direction=normalize(marineRelativeWorldPosition(uv,.001)-SurfaceEye.xyz);
    vec4 sky=vec4(solDisplay(solSky(direction,true)),ColorModulator.a);
    fragColor=sky; // Procedural horizon haze already follows the submitted sun and weather.
}
