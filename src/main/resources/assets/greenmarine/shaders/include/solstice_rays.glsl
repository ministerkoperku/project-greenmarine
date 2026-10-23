#ifndef GREENMARINE_SOLSTICE_RAYS
#define GREENMARINE_SOLSTICE_RAYS
#include <greenmarine:surface.glsl>
#include <greenmarine:reflection_policy.glsl>
#include <greenmarine:rays.glsl>
#include <greenmarine:surface_rays.glsl>
#include <greenmarine:surface_extra.glsl>
float solRayCoverage(vec3 position) {
    vec3 edge=min(position-RayBoundsMin.xyz,RayBoundsMax.xyz-position);
    return smoothstep(0.0,8.0,min(edge.x,min(edge.y,edge.z)));
}
// Match each reduced ray texel to the full-resolution capture texel that produced it.
bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection,out float support,out float occlusion) {
    lighting=vec4(0);reflection=vec4(0);support=0.0;occlusion=1.0;float occlusionSum=0.0;
    if(!marineSurfaceRayReady()) return false;
    ivec2 outputSize=textureSize(RayReflection,0),captureSize=textureSize(MarineSurfaceNormalDepth,0);
    ivec2 first=ivec2(marineReflectionBase(uv.x,outputSize.x,captureSize.x),marineReflectionBase(uv.y,outputSize.y,captureSize.y));
    vec2 fraction=vec2(marineReflectionFraction(uv.x,first.x,outputSize.x,captureSize.x),
        marineReflectionFraction(uv.y,first.y,outputSize.y,captureSize.y));
    vec3 position=marineRelativeWorldPosition(uv,surface.w);
    vec3 eyeDelta=position-SurfaceEye.xyz;
    float eyeDistance=length(eyeDelta);
    float incidence=abs(dot(surface.xyz,eyeDelta))/max(eyeDistance,.00001);
    float total=0.0,lightingTotal=0.0,footprint=0.0,lightingFootprint=0.0;vec4 reflectedSum=vec4(0);
    float surfaceClass=floor(material.a+.0001);
    bool needReflection=marineNeedsSurfaceReflection(surfaceClass);
    float radius=marineQualityReflectionRadius(fract(material.a)*2.0,surfaceClass,Context.y);
    int start=radius>1.001?-1:0,end=radius>1.001?3:2;
    for(int y=start;y<end;y++) for(int x=start;x<end;x++) {
        float weight=marineReflectionTapWeight(float(x)-fraction.x,radius)*marineReflectionTapWeight(float(y)-fraction.y,radius);
        if(weight<=.00001) continue;
        float lightWeight=marineReflectionTapWeight(float(x)-fraction.x,1.0)*marineReflectionTapWeight(float(y)-fraction.y,1.0);
        footprint+=weight;lightingFootprint+=lightWeight;
        ivec2 texel=clamp(first+ivec2(x,y),ivec2(0),outputSize-1);
        ivec2 captured=ivec2(marineCaptureCoordinate(texel.x,outputSize.x,captureSize.x),
            marineCaptureCoordinate(texel.y,outputSize.y,captureSize.y));
        vec4 source=texelFetch(MarineSurfaceNormalDepth,captured,0);
        if(source.w<=1e-6 || dot(source.xyz,surface.xyz)<.95) continue;
        vec4 sourceMaterial=texelFetch(MarineSurfaceAlbedo,captured,0);
        if(floor(sourceMaterial.a+.0001)!=floor(material.a+.0001)
            || abs(sourceMaterial.a-material.a)>.08) continue;
        vec2 sourceUV=(vec2(captured)+.5)/vec2(captureSize);
        vec3 sourcePosition=marineRelativeWorldPosition(sourceUV,source.w);
        vec3 delta=sourcePosition-position;
        if(!marineReflectionFootprintMatches(dot(delta,surface.xyz),length(delta),eyeDistance,float(min(captureSize.x,captureSize.y)),incidence)) continue;
        if(needLighting) {
            // Roughness broadens reflections only; preserve the original lighting footprint.
            if(lightWeight>0.0) {
                lighting+=texelFetch(RaySurfaceLighting,texel,0)*lightWeight;
                occlusionSum+=marineSurfaceOcclusion(texelFetch(RaySurfaceExtra,texel,0))*lightWeight;
            }
            lightingTotal+=lightWeight;
        }
        if(needReflection) {
            vec4 hit=texelFetch(RayReflection,texel,0);
            reflectedSum+=vec4(hit.rgb*hit.a,hit.a)*weight;
        }
        total+=weight;
    }
    if(total<.00001 || (needLighting && lightingTotal<.00001)) return false;
    if(needLighting) {lighting/=lightingTotal;occlusion=occlusionSum/lightingTotal;}
    support=marineSampleCoverage(total,footprint);
    if(needLighting) support=min(support,marineSampleCoverage(lightingTotal,lightingFootprint));
    reflection=vec4(reflectedSum.a>.00001?reflectedSum.rgb/reflectedSum.a:vec3(0),reflectedSum.a/total);
    return true;
}
bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection,out float support) {
    float occlusion;
    return solSurfaceRays(uv,surface,material,needLighting,lighting,reflection,support,occlusion);
}
// Preserve the original include API; legacy callers receive faded hit validity.
bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection) {
    float support;
    bool ready=solSurfaceRays(uv,surface,material,needLighting,lighting,reflection,support);
    reflection.a*=support;
    return ready;
}
#endif
