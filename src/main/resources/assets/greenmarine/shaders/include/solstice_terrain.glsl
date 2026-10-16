#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef GREENMARINE_SURFACE_CAPTURE
#include <minecraft:oit.glsl>
#include <greenmarine:surface_rays.glsl>
#include <greenmarine:solstice_rays.glsl>
#endif
#include <greenmarine:solstice.glsl>
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
#ifdef GREENMARINE_SURFACE_CAPTURE
layout(location=0) out vec4 captureNormalDepth;
layout(location=1) out vec4 captureAlbedo;
#elif !defined(OIT_ALPHA_ONLY)
layout(location=0) out vec4 fragColor;
#endif
void main() {
    vec3 derivativeNormal=cross(dFdx(surfacePosition),dFdy(surfacePosition));
    vec3 normal=derivativeNormal*inversesqrt(max(dot(derivativeNormal,derivativeNormal),1e-12));
    if(dot(normal,surfacePosition-SurfaceEye.xyz)>0.0) normal=-normal;
    bool water=SurfaceInfo.x>.5 && marineWater(texCoord0);
    vec4 sampled=UseRgss==1?sampleRGSS(Sampler0,texCoord0,1.0/TextureSize):sampleNearest(Sampler0,texCoord0,1.0/TextureSize);
    vec4 color=sampled*vertexColor;
    if(water) color.a=marineParameter(SOL_WAVE_DEBUG)>.5?1.0:marineParameter(SOL_WATER_ALPHA);
#ifdef ALPHA_CUTOUT
    if(color.a<ALPHA_CUTOUT) discard;
#endif
    if(color.a<.01) discard;
    vec3 base=solLinear(sampled.rgb*surfaceColor.rgb);
    float puddle=solPuddle(surfacePosition,normal,surfaceLight.y);
    float wet=clamp(Environment.z*marineParameter(SOL_WETNESS)*surfaceLight.y,0.0,1.0);
    float roughness=clamp(marineParameter(SOL_ROUGHNESS)*(1.0-wet*.65),.06,.98);
    if(water) roughness=.09;
#ifdef GREENMARINE_SURFACE_CAPTURE
    captureNormalDepth=vec4(normal,gl_FragCoord.z);
    captureAlbedo=vec4(base,(water?4.0:(puddle>.12?3.0:1.0))+roughness*.5);
#elif defined(OIT_ALPHA_ONLY)
    executeAlphaOnlyPhase(gl_FragCoord.z,color.a);
#else
    vec2 uv=gl_FragCoord.xy/Frame.xy;
    vec3 view=normalize(SurfaceEye.xyz-surfacePosition);
    vec3 reflected=reflect(-view,normal);
    vec3 illumination=solIrradiance(normal,surfaceLight.y,surfaceLight.x);
    vec3 lit=base*illumination*(1.0-wet*.16);
    // Snow is a climate overlay on exposed top faces, preserving atlas textures.
    float snow=Climate.y*marineParameter(SOL_SNOW)*smoothstep(.6,.98,normal.y)*smoothstep(.7,1.0,surfaceLight.y);
    lit=mix(lit,vec3(.72,.78,.83)*illumination,clamp(snow,0.0,.7));
    float emission=pow(surfaceLight.x,9.0)*max(0.0,solLuma(base)-.35)*marineParameter(SOL_EMISSION);
    lit+=base*emission;
    if(water) {
        vec3 absorptionColor=vec3(.012,.095,.14)*(.3+.7*solDay());
        vec2 refracted=clamp(uv+normal.xz*.007*marineParameter(SOL_REFRACTION),vec2(.001),vec2(.999));
        float opaqueDepth=texture(MarineOpaqueDepth,refracted).r;
        float thickness=18.0;
        if(opaqueDepth>1e-6) {
            vec3 behind=marineRelativeWorldPosition(refracted,opaqueDepth);
            if(length(behind)<length(surfacePosition)-.15) {
                refracted=uv;opaqueDepth=texture(MarineOpaqueDepth,uv).r;
                if(opaqueDepth>1e-6) behind=marineRelativeWorldPosition(uv,opaqueDepth);
            }
            if(opaqueDepth>1e-6) thickness=clamp(length(behind)-length(surfacePosition),0.0,30.0);
        }
        vec3 transmission=exp(-vec3(.38,.10,.052)*max(.15,thickness)*marineParameter(SOL_ABSORPTION));
        vec3 underwater=solLinear(texture(MarineOpaqueColor,refracted).rgb);
        lit=mix(absorptionColor,underwater,transmission);
        vec3 reflection=solSky(reflected,true);
        if(SkyColor.w>.5 && marineSurfaceRayReady()) {
            vec4 captured=texture(MarineSurfaceNormalDepth,uv);
            vec4 capturedMaterial=texture(MarineSurfaceAlbedo,uv);
            bool sameWater=floor(capturedMaterial.a+.0001)==4.0 && dot(captured.xyz,normal)>.92
                && abs(captured.w-gl_FragCoord.z)<max(.00001,gl_FragCoord.z*.01);
            if(sameWater) {
                vec4 hit=texture(RayReflection,uv);
                float coverage=solRayCoverage(surfacePosition)*marineParameter(SOL_RAYS);
                reflection=mix(reflection,max(hit.rgb,vec3(0)),clamp(hit.a*coverage,0.0,1.0));
            }
        }
        float fresnel=.02+.98*pow(1.0-max(dot(normal,view),0.0),5.0);
        lit=mix(lit,reflection,clamp(fresnel*marineParameter(SOL_REFLECTION),0.0,1.0));
    } else if(puddle>.01) {
        float fresnel=.035+.965*pow(1.0-max(dot(normal,view),0.0),5.0);
        lit=mix(lit,solSky(reflected,true),puddle*fresnel*marineParameter(SOL_REFLECTION));
    }
    color.rgb=solDisplay(lit);
    if(water && marineParameter(SOL_WAVE_DEBUG)>.5) color.rgb=vec3(clamp(.5+surfaceWave/.16,0.0,1.0),0,0);
    color=mix(FogColor*vec4(1,1,1,color.a),color,chunkVisibility);
#ifdef OIT_ACCUMULATE
    color=sampleColorForAccumulation(color);
    vec4 fogColor=vec4(FogColor.rgb*color.a,FogColor.a);
#else
    vec4 fogColor=FogColor;
#endif
    fragColor=apply_fog(color,sphericalVertexDistance,cylindricalVertexDistance,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,fogColor);
#endif
}
