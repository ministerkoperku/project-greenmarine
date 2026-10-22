#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#include <greenmarine:surface.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif
uniform sampler2D Sampler0;
layout(location=0) in float sphericalVertexDistance;
layout(location=1) in float cylindricalVertexDistance;
layout(location=2) in vec4 vertexColor;
layout(location=3) in vec2 texCoord0;
layout(location=4) in float chunkVisibility;
layout(location=5) in vec3 surfacePosition;
layout(location=6) in vec4 surfaceColor;
layout(location=7) in vec2 surfaceLight;
layout(location=8) in float surfaceWave;
#ifndef OIT_ALPHA_ONLY
layout(location=0) out vec4 fragColor;
#endif
vec3 tone(vec3 color) {
    color*=marineParameter(3);
    return pow(max(color,vec3(0))/max(1.0,max(color.r,max(color.g,color.b))),vec3(1.0/2.2));
}
void main() {
    vec3 normal=normalize(cross(dFdx(surfacePosition),dFdy(surfacePosition)));
    if(dot(normal,surfacePosition-SurfaceEye.xyz)>0.0) normal=-normal;
    vec2 uv=gl_FragCoord.xy/Frame.xy;
    bool water=SurfaceInfo.x>.5 && marineWater(texCoord0);
    vec4 sampled=(UseRgss==1?sampleRGSS(Sampler0,texCoord0,1.0/TextureSize):sampleNearest(Sampler0,texCoord0,1.0/TextureSize));
    vec4 color=sampled*vertexColor;
    // All transparency phases agree on displaced geometry and material coverage.
    if(water) color.a=marineParameter(9)>.5?1.0:marineParameter(7);
#ifdef ALPHA_CUTOUT
    if(color.a<ALPHA_CUTOUT) discard;
#endif
#ifndef OIT_ALPHA_ONLY
    vec3 base=pow(max(sampled.rgb*surfaceColor.rgb,vec3(0)),vec3(2.2));
    vec3 ambient=vec3(.025+.08*max(SurfaceSun.y,0.0))+SurfaceSky.rgb*.2;
    float sun=max(dot(normal,SurfaceSun.xyz),0.0)*SurfaceSun.w*marineParameter(5);
    vec3 illumination=(ambient+vec3(sun))*surfaceLight.y+vec3(surfaceLight.x*1.3);
    color.rgb=tone(base*illumination);
    if(water) {
        vec3 view=normalize(SurfaceEye.xyz-surfacePosition);
        float fresnel=.02+.98*pow(1.0-max(dot(normal,view),0.0),5.0);
        vec3 reflected=reflect(-view,normal);
        vec3 sky=SurfaceSky.rgb*(.6+.4*max(reflected.y,0.0));
        sky+=vec3(1.0,.85,.6)*pow(max(dot(reflected,SurfaceSun.xyz),0.0),128.0)*SurfaceSun.w;
        vec2 refracted=clamp(uv+normal.xz*.008*marineParameter(1),vec2(.001),vec2(.999));
        float opaqueDepth=texture(MarineOpaqueDepth,refracted).r;
        float thickness=12.0;
        // Reversed depth clear is sky; never reconstruct its infinite position.
        if(opaqueDepth>1e-6) {
            vec3 behind=marineRelativeWorldPosition(refracted,opaqueDepth);
            if(length(behind)<length(surfacePosition)-.2) {
                refracted=uv;opaqueDepth=texture(MarineOpaqueDepth,uv).r;
                if(opaqueDepth>1e-6) behind=marineRelativeWorldPosition(uv,opaqueDepth);
            }
            if(opaqueDepth>1e-6) thickness=clamp(length(behind)-length(surfacePosition),0.0,20.0);
        }
        vec3 transmission=exp(-vec3(.35,.09,.045)*thickness*marineParameter(2));
        vec3 absorbed=mix(vec3(.015,.19,.25),texture(MarineOpaqueColor,refracted).rgb,transmission);
        float skyVisibility=marineSkyVisibility(surfaceLight.y);
        color.rgb=mix(absorbed,tone(sky*skyVisibility),fresnel*marineParameter(4));
        color.rgb+=pow(max(dot(reflected,SurfaceSun.xyz),0.0),96.0)*SurfaceSun.w*.12*skyVisibility;
    }
    if(water && marineParameter(9)>.5) color.rgb=vec3(clamp(.5+surfaceWave/.16,0.0,1.0),0,0);
    color=mix(FogColor*vec4(1,1,1,color.a),color,chunkVisibility);
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z,color.a);
#else
#ifdef OIT_ACCUMULATE
    color=sampleColorForAccumulation(color);
    vec4 fogColor=vec4(FogColor.rgb*color.a,FogColor.a);
#else
    vec4 fogColor=FogColor;
#endif
    fragColor=apply_fog(color,sphericalVertexDistance,cylindricalVertexDistance,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,fogColor);
#endif
}
