#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <greenmarine:solstice.glsl>
layout(location=0) out vec4 fragColor;
void main() {
    // Overworld sky is supplied by the complete clear-depth background stage.
    if(Dimension.w>.5) discard;
    vec2 uv=gl_FragCoord.xy/Frame.xy;
    vec3 direction=normalize(marineRelativeWorldPosition(uv,.001)-SurfaceEye.xyz);
    fragColor=vec4(solDisplay(solSky(direction,true)),ColorModulator.a);
}
