#ifndef GREENMARINE_SOLSTICE_SCENE
#define GREENMARINE_SOLSTICE_SCENE
#include <greenmarine:solstice.glsl>
#include <greenmarine:surface_rays.glsl>
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
vec3 solHeightFog(vec3 color,vec3 position) {
    float distance=length(position-SurfaceEye.xyz);
    float altitude=Environment.x+position.y;
    float ceiling=marineParameter(SOL_FOG_HEIGHT);
    float localDensity=exp(-max(-32.0,altitude-ceiling)*.035);
    float eyeDensity=exp(-max(-32.0,Environment.x-ceiling)*.035);
    float density=min(2.4,(localDensity+eyeDensity)*.5);
    float weather=1.0+Environment.y*2.4+Climate.w*.8;
    float fog=1.0-exp(-distance*.0018*density*marineParameter(SOL_FOG)*weather);
    vec3 haze=mix(vec3(.22,.36,.48),solLinear(EnvironmentFogColor.rgb),.65);
    haze*=mix(.07*marineParameter(SOL_NIGHT),1.0,solDay());
    haze+=solSunColor()*pow(max(dot(normalize(position-SurfaceEye.xyz),SurfaceSun.xyz),0.0),12.0)*SurfaceSun.w*.2;
    if(Environment.w>.5) {
        fog=1.0-exp(-distance*.095);
        haze=vec3(.012,.08,.115);
    }
    if(Dimension.y>.5 || Dimension.z>.5) haze=solLinear(EnvironmentFogColor.rgb);
    return mix(color,haze,clamp(fog,0.0,.96));
}
vec3 solScene(vec2 uv) {
    vec3 color=solLinear(texture(SceneSampler,uv).rgb);
    float sceneDepth=texture(DepthSampler,uv).r;
    vec4 surface=vec4(0),material=vec4(0);
    if(SkyColor.w>.5) {
        surface=texture(MarineSurfaceNormalDepth,uv);
        material=texture(MarineSurfaceAlbedo,uv);
    }
    bool captured=dot(surface.xyz,surface.xyz)>.5 && surface.w>1e-6;
    vec3 position=vec3(0);
    if(captured) {
        position=marineRelativeWorldPosition(uv,surface.w);
        vec3 normal=normalize(surface.xyz);
        float materialClass=floor(material.a+.0001);
        // Native light and reflection are already composed on terrain before entities.
        if(marineParameter(SOL_SURFACE_DEBUG)>.5) {
            if(marineParameter(SOL_SURFACE_DEBUG)>1.5) return material.rgb;
            return normal*.5+.5;
        }
    }
    if(sceneDepth>1e-6) {
        if(!captured || sceneDepth>surface.w) position=marineRelativeWorldPosition(uv,sceneDepth);
        color=solHeightFog(color,position);
    }
    return max(color,vec3(0));
}
#endif
