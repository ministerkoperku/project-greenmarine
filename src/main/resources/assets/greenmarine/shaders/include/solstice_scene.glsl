#ifndef GREENMARINE_SOLSTICE_SCENE
#define GREENMARINE_SOLSTICE_SCENE
#include <greenmarine:solstice.glsl>
#include <greenmarine:surface_rays.glsl>
#include <greenmarine:solstice_rays.glsl>
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
    if(sceneDepth<=1e-6 && Dimension.w>.5) {
        vec3 direction=normalize(marineRelativeWorldPosition(uv,.001)-SurfaceEye.xyz);
        // The host may leave its clock-colored clear sky outside the sky mesh.
        float fill=1.0-smoothstep(.002,.08,solLuma(color));
        color=mix(color,solSky(direction,true),fill);
    }
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
        float roughness=clamp(fract(material.a)*2.0,.04,1.0);
        // Opaque entities retain their original shading when they occlude captured terrain.
        bool visible=materialClass==4.0 || abs(sceneDepth-surface.w)<max(.00002,surface.w*.02);
        if(visible && marineSurfaceRayReady()) {
            vec4 light=texture(RaySurfaceLighting,uv);
            float diffuse=max(dot(normal,SurfaceSun.xyz),0.0)*SurfaceSun.w;
            vec3 indirect=max(light.rgb-vec3(RayOptions.x+diffuse*light.a),vec3(0));
            vec3 baseline=solAmbient(normal)+solSunColor()*diffuse;
            vec3 irradiance=solAmbient(normal)+solSunColor()*diffuse*clamp(light.a,0.0,1.0)
                +indirect;
            float coverage=solRayCoverage(position)*marineParameter(SOL_RAYS);
            if(materialClass!=4.0) {
                // Correct irradiance ratios, never divide shaded radiance by dark atlas texels.
                vec3 ratio=clamp(irradiance/max(baseline,vec3(.035)),vec3(.12),vec3(4));
                color*=mix(vec3(1),ratio,coverage);
            }
            if(materialClass==3.0 || materialClass==2.0) {
                vec3 view=normalize(SurfaceEye.xyz-position),reflected=reflect(-view,normal);
                float fresnel=.035+.965*pow(1.0-max(dot(normal,view),0.0),5.0);
                if(materialClass==2.0) fresnel=.65+.35*fresnel;
                vec4 hit=texture(RayReflection,uv);
                vec3 reflection=mix(solSky(reflected,true),hit.rgb,clamp(hit.a,0.0,1.0));
                color=mix(color,reflection,clamp(fresnel*(1.0-roughness)*marineParameter(SOL_REFLECTION)*coverage,0.0,1.0));
            }
        }
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
