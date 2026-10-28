#ifndef GREENMARINE_SOLSTICE_SCENE
#define GREENMARINE_SOLSTICE_SCENE
#include <greenmarine:solstice.glsl>
#include <greenmarine:surface_rays.glsl>
#include <greenmarine:surface_extra.glsl>
#include <greenmarine:rays.glsl>
#include <greenmarine:heightfield.glsl>
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
vec3 solHeightFog(vec3 color,vec3 position) {
    float distance=length(position-SurfaceEye.xyz);
    float altitude=Environment.x+position.y;
    float ceiling=marineParameter(SOL_FOG_HEIGHT);
    float localDensity=exp(-max(-32.0,altitude-ceiling)*.035);
    float eyeDensity=exp(-max(-32.0,Environment.x-ceiling)*.035);
    float density=min(2.4,(localDensity+eyeDensity)*.5);
    // Long rain leaves fog that clears as surfaces dry (Environment.z); cloudy days are hazier.
    float weather=1.0+Environment.y*2.4+Climate.w*.8+Environment.z*1.6+solMood()*1.2;
    float fog=1.0-exp(-distance*.0005*density*marineParameter(SOL_FOG)*weather);
    // Aerial perspective: distant terrain fades into the sky colour of its own direction.
    vec3 view=normalize(position-SurfaceEye.xyz);
    vec3 haze=solAtmosphere(vec3(view.x,max(view.y,.02),view.z))*.7+vec3(.006,.009,.018)*marineParameter(SOL_NIGHT)*(1.0-solDay());
    haze=mix(haze,solLinear(EnvironmentFogColor.rgb)*max(solDay(),.1),Environment.y*.5);
    if(Environment.w>.5) {
        fog=1.0-exp(-distance*.095);
        haze=vec3(.012,.08,.115);
    }
    if(Dimension.y>.5 || Dimension.z>.5) haze=solLinear(EnvironmentFogColor.rgb);
    return mix(color,haze,clamp(fog,0.0,.96));
}
float solMedian9(float values[9]) {
    for(int i=1;i<9;i++) {
        float value=values[i];int j=i-1;
        for(;j>=0 && values[j]>value;j--) values[j+1]=values[j];
        values[j+1]=value;
    }
    return values[4];
}
// Ray-traced sun shafts: the native pass marches each reduced camera ray with shadow
// rays; a median filter rejects isolated fireflies and a tent filter hides step jitter.
vec3 solVolumetric(vec2 uv,vec3 direction,float distance,bool sky,float columns) {
    if(!marineSurfaceRayReady() || marineParameter(SOL_VOLUMETRIC)<=0.0 || SurfaceSun.w<=0.0
        || Dimension.x<.5 || Environment.w>.5) return vec3(0);
    vec2 texel=1.0/vec2(textureSize(RaySurfaceExtra,0));
    vec4 extra=vec4(0);float total=0.0;
    float values[9];bool valid[9];int slot=0;
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        vec4 sampled=texture(RaySurfaceExtra,uv+vec2(x,y)*texel);
        bool marched=sampled.z>.001;
        float weight=float((2-abs(x))*(2-abs(y)))*float(marched);
        extra+=sampled*weight;total+=weight;
        values[slot]=sampled.y;valid[slot++]=marched;
    }
    if(total<=0.0) return vec3(0);
    extra/=total;
    for(int i=0;i<9;i++) if(!valid[i]) values[i]=extra.y;
    extra.y=mix(extra.y,solMedian9(values),.7);
    float reach=min(extra.z,distance);
    float cosine=dot(direction,SurfaceSun.xyz),g=.72;
    float forward=(1.0-g*g)/(12.5663706*pow(1.0+g*g-2.0*g*cosine,1.5));
    float phase=mix(.0795775,forward,.7);
    // Open air uses a low extinction scale, consistent with height fog. The old
    // indoor-scale density outdoors painted a white layer over sunlit terrain.
    float indoor=clamp(extra.w-1.0,0.0,1.0);
    float density=mix(.001,.063,indoor)*marineParameter(SOL_VOLUMETRIC)*(1.0+Environment.y*2.0);
    // Scattering depends on the air path, not whether its endpoint is sky or a
    // cutout leaf. Different endpoint gains drew flat bright foliage silhouettes.
    // Preserve the existing dense indoor terrain-beam response at indoor=1.
    return solSunColor()*SurfaceSun.w*extra.y*columns*(1.0-exp(-reach*density))*phase*mix(1.0,10.0,indoor);
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
        // 3 is drawn by the terrain program (why native rays are or are not used).
        if(marineParameter(SOL_SURFACE_DEBUG)>.5 && (marineParameter(SOL_SURFACE_DEBUG)<2.5 || (marineParameter(SOL_SURFACE_DEBUG)>3.5 && marineParameter(SOL_SURFACE_DEBUG)<6.5))) {
            // 4: traced ambient occlusion, 5: sunlit fraction of the camera ray,
            // 6: raw reflection hit (red) and native sun visibility (green).
            if(marineParameter(SOL_SURFACE_DEBUG)>5.5) return vec3(texture(RayReflection,uv).a,texture(RaySurfaceLighting,uv).a,0);
            if(marineParameter(SOL_SURFACE_DEBUG)>4.5) return vec3(texture(RaySurfaceExtra,uv).y);
            if(marineParameter(SOL_SURFACE_DEBUG)>3.5) return vec3(marineSurfaceOcclusion(texture(RaySurfaceExtra,uv)));
            if(marineParameter(SOL_SURFACE_DEBUG)>1.5) return material.rgb;
            return normal*.5+.5;
        }
    }
    float distance=48.0;
    // Two points on the ray cancel the view-bobbing shift of the projection.
    vec3 rayView=marineViewPosition(uv,.25)-marineViewPosition(uv,.5);
    if(rayView.z>0.0) rayView=-rayView;
    vec3 direction=normalize((InverseViewRotation*vec4(rayView,0.0)).xyz);
    if(sceneDepth>1e-6) {
        if(!captured || sceneDepth>surface.w) position=marineRelativeWorldPosition(uv,sceneDepth);
        color=solHeightFog(color,position);
        distance=length(position-SurfaceEye.xyz);
        direction=(position-SurfaceEye.xyz)/max(distance,.0001);
    }
    // Shadow rays that leave the finite traced scene cannot see terrain beyond it.
    // Test the height field where the sun ray exits the scene, which keeps cave-mouth
    // beams while rejecting rays that escape into a mountain or tunnel wall.
    float columns=1.0;
    if(sceneDepth>1e-6 && SurfaceSun.w>0.0) {
        vec3 sun=normalize(SurfaceSun.xyz);
        if(sun.y>.03) {
            vec3 inverse=1.0/max(abs(sun),vec3(1e-4))*sign(sun+vec3(1e-6));
            vec3 exits=max((RayBoundsMin.xyz-position)*inverse,(RayBoundsMax.xyz-position)*inverse);
            float leave=max(min(exits.x,min(exits.y,exits.z)),1.0);
            columns=marineHeightFieldSun(position,sun,leave);
        }
    }
    color+=solVolumetric(uv,direction,distance,sceneDepth<=1e-6,columns);
    return max(color,vec3(0));
}
#endif
