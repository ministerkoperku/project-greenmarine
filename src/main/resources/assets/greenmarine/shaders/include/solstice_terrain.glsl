#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef GREENMARINE_SURFACE_CAPTURE
#include <minecraft:oit.glsl>
#include <greenmarine:surface_rays.glsl>
#include <greenmarine:solstice_rays.glsl>
#include <greenmarine:heightfield.glsl>
#endif
#include <greenmarine:solstice.glsl>
#include <greenmarine:materials.glsl>
#include <greenmarine:labpbr.glsl>
#include <greenmarine:voxel.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif
float solLocalSunVisibility(vec3 position,vec3 normal) {
    if(VoxelTracing.x<.5 || SurfaceSun.w<=0.0 || dot(normal,SurfaceSun.xyz)<=0.0) return 1.0;
    vec3 origin=position+normal*.08;
    vec3 local=origin-VoxelOriginSize.xyz;
    float edge=min(min(local.x,local.y),min(local.z,min(32.0-local.x,min(32.0-local.y,32.0-local.z))));
    if(edge<=0.0) return 1.0;
    MarineVoxelHit hit=marineVoxelTrace(origin,SurfaceSun.xyz,24.0,true);
    return hit.hit?1.0-smoothstep(0.0,2.0,edge):1.0;
}
uniform sampler2D Sampler0;
// Texture relief: atlas luminance as a height field, differenced inside 16-texel tiles
// so neighbouring sprites never leak in. Faded once a texel shrinks below a pixel.
float solTexelHeight(ivec2 texel) {
    vec4 color=texelFetch(Sampler0,texel,0);
    return color.a<.1?-1.0:sqrt(solLuma(color.rgb));
}
float solPbrOcclusion=1.0;
vec3 solReliefNormal(vec3 normal,vec3 position,vec2 uv,float strength) {
    vec2 size=vec2(textureSize(Sampler0,0));
    vec2 duvx=dFdx(uv)*size,duvy=dFdy(uv)*size;
    vec3 dpx=dFdx(position),dpy=dFdy(position);
    float footprint=1.0-smoothstep(.35,.9,max(length(duvx),length(duvy)));
    if(marinePbrNormalsPresent()) {
        // Resource pack normal map replaces the procedural relief; fades out like it once texels
        // shrink below a pixel, while its ambient occlusion stays.
        vec4 mapped=marinePbrNormal(uv);
        solPbrOcclusion=mapped.w;
        vec3 dpyPerp=cross(dpy,normal),dpxPerp=cross(normal,dpx);
        vec3 tangent=dpyPerp*duvx.x+dpxPerp*duvy.x,bitangent=dpyPerp*duvx.y+dpxPerp*duvy.y;
        if(dot(tangent,tangent)<1e-36 || dot(bitangent,bitangent)<1e-36 || footprint<=0.0) return normal;
        vec3 perturbed=normalize(normalize(tangent)*mapped.x+normalize(bitangent)*mapped.y+normal*mapped.z);
        return normalize(mix(normal,perturbed,footprint));
    }
    strength*=footprint;
    if(strength<=0.0) return normal;
    ivec2 texel=ivec2(floor(uv*size)),local=texel&15;
    int dx=local.x<15?1:-1,dy=local.y<15?1:-1;
    float height=solTexelHeight(texel);
    if(height<0.0) return normal;
    float hx=solTexelHeight(texel+ivec2(dx,0)),hy=solTexelHeight(texel+ivec2(0,dy));
    vec2 gradient=vec2((hx<0.0?0.0:hx-height)*float(dx),(hy<0.0?0.0:hy-height)*float(dy));
    vec3 dpyPerp=cross(dpy,normal),dpxPerp=cross(normal,dpx);
    vec3 tangent=dpyPerp*duvx.x+dpxPerp*duvy.x,bitangent=dpyPerp*duvx.y+dpxPerp*duvy.y;
    float scale=inversesqrt(max(max(dot(tangent,tangent),dot(bitangent,bitangent)),1e-36));
    return normalize(normal-(tangent*gradient.x+bitangent*gradient.y)*scale*strength*2.0);
}
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
    // Normalise each derivative first: near the camera the raw cross product is ~1e-7 long and a
    // 1e-12 floor on its squared length left a non-unit normal that every capture match rejected.
    vec3 dx=dFdx(surfacePosition),dy=dFdy(surfacePosition);
    vec3 derivativeNormal=cross(dx*inversesqrt(max(dot(dx,dx),1e-30)),dy*inversesqrt(max(dot(dy,dy),1e-30)));
    vec3 normal=derivativeNormal*inversesqrt(max(dot(derivativeNormal,derivativeNormal),1e-30));
    if(dot(normal,surfacePosition-SurfaceEye.xyz)>0.0) normal=-normal;
    bool water=SurfaceInfo.x>.5 && marineWater(texCoord0);
    vec3 shading=normal;
#ifndef GREENMARINE_SURFACE_CAPTURE
    // Derivatives must run before any discard.
    shading=solReliefNormal(normal,surfacePosition,texCoord0,water?0.0:marineParameter(SOL_RELIEF));
#endif
    vec4 sampled=UseRgss==1?sampleRGSS(Sampler0,texCoord0,1.0/TextureSize):sampleNearest(Sampler0,texCoord0,1.0/TextureSize);
    vec4 color=sampled*vertexColor;
    if(water) color.a=marineParameter(SOL_SURFACE_DEBUG)>6.5?1.0:marineParameter(SOL_WATER_ALPHA);
#ifdef ALPHA_CUTOUT
    if(color.a<ALPHA_CUTOUT) discard;
#endif
    if(color.a<.01) discard;
    vec3 base=solLinear(sampled.rgb*surfaceColor.rgb);
    vec4 material=marineBlockMaterial(texCoord0);
    float puddle=solPuddle(surfacePosition,normal,surfaceLight.y)*marinePuddleRetention(material.w);
    float wet=clamp(Environment.z*marineParameter(SOL_WETNESS)*surfaceLight.y,0.0,1.0);
    float roughness=clamp(material.x*marineParameter(SOL_ROUGHNESS)*(1.0-wet*.65),.06,.98);
    if(water) roughness=.09;
    // LabPBR texels override the builtin profile in capture and shading alike, so native rays trace
    // reflections for metal and smooth texels of any block and reconstruction still matches classes.
    MarinePbr pbr=marinePbrMaterial(texCoord0);
    if(pbr.present && !water && puddle<=.12) {
        roughness=clamp(pbr.roughness*(1.0-wet*.65),.04,.98);
        if(pbr.metal) material=vec4(roughness,1.0,0.0,2.0);
        else if(pbr.roughness<.55 && pbr.f0>.015) material=vec4(roughness,clamp(pbr.f0*12.0,.2,1.0),0.0,9.0);
        else if(material.w==2.0 || material.w==8.0 || material.w==9.0) material=vec4(roughness,0.0,0.0,1.0);
    }
#ifdef GREENMARINE_SURFACE_CAPTURE
    // Translucent phase (SurfaceInfo.x set): keep water only. Capturing glass/ice hid the opaque
    // surface behind it from native rays, so everything seen through a pane lost RT shadows,
    // GI and reflections (falling back to the sky).
    if(SurfaceInfo.x>.5 && !water) discard;
    captureNormalDepth=vec4(normal,gl_FragCoord.z);
    captureAlbedo=vec4(base,(water?4.0:(puddle>.12?3.0:material.w))+roughness*.5);
#elif defined(OIT_ALPHA_ONLY)
    executeAlphaOnlyPhase(gl_FragCoord.z,color.a);
#else
    vec2 uv=gl_FragCoord.xy/Frame.xy;
    vec3 view=normalize(SurfaceEye.xyz-surfacePosition);
    vec3 reflected=reflect(-view,normal);
    vec4 nativeLight=vec4(0),nativeReflection=vec4(0);
    float nativeCoverage=0.0,occlusion=1.0;
    if(!water && SkyColor.w>.5 && marineSurfaceRayReady() && marineParameter(SOL_RAYS)>0.0) {
        vec4 captured=texture(MarineSurfaceNormalDepth,uv);
        vec4 capturedMaterial=texture(MarineSurfaceAlbedo,uv);
        float expectedClass=puddle>.12?3.0:material.w;
        bool sameSurface=floor(capturedMaterial.a+.0001)==expectedClass
            && dot(captured.xyz,normal)>.95 && marineOpaqueSurfaceVisible(gl_FragCoord.z,captured.w);
        float support=0.0;
        bool gathered=sameSurface && solSurfaceRays(uv,captured,capturedMaterial,true,nativeLight,nativeReflection,support,occlusion);
        if(gathered) nativeCoverage=solRayCoverage(surfacePosition)*marineParameter(SOL_RAYS)*support;
        // Diagnostic 3: why native rays are (not) used. Red depth, yellow class, orange/white/cyan normal
        // mismatch against the capture; otherwise green=bounds coverage, blue=tap support.
        if(marineParameter(SOL_SURFACE_DEBUG)>2.5 && marineParameter(SOL_SURFACE_DEBUG)<3.5) {
            vec3 reason=vec3(0,solRayCoverage(surfacePosition),gathered?support:0.0);
            if(floor(capturedMaterial.a+.0001)!=expectedClass) reason=vec3(1,1,0);
            // Normal mismatch split: orange slightly off (precision), white a different face,
            // cyan the same plane facing the other way.
            else if(dot(captured.xyz,normal)<=.95) {
                float agreement=dot(captured.xyz,normal);
                reason=agreement>.5?vec3(1,.5,0):(agreement<-.5?vec3(0,1,1):vec3(1));
            }
            else if(!marineOpaqueSurfaceVisible(gl_FragCoord.z,captured.w)) reason=vec3(1,0,0);
            fragColor=vec4(reason,1);return;
        }
    } else if(!water && marineParameter(SOL_SURFACE_DEBUG)>2.5 && marineParameter(SOL_SURFACE_DEBUG)<3.5) {fragColor=vec4(1,0,1,1);return;}
    float localVisibility=solLocalSunVisibility(surfacePosition,normal);
    // SurfaceInfo.z: native rays trace block/dynamic lights with shadows within 16..24 blocks; the
    // unshadowed lightmap term hands over over the same range (surface_rt.comp).
    float tracedBlocks=SurfaceInfo.z*nativeCoverage*(1.0-smoothstep(16.0,24.0,length(surfacePosition-SurfaceEye.xyz)));
    float sunVisibility=mix(localVisibility,clamp(nativeLight.a,0.0,1.0),nativeCoverage);
    if(sunVisibility>0.0 && dot(normal,SurfaceSun.xyz)>0.0) {
        // Beyond the traced scene the height field carries distant occluders (mountains); inside it,
        // only the part of the sun ray after it leaves the scene, so windows and roofs stay exact.
        vec3 inverse=1.0/max(abs(SurfaceSun.xyz),vec3(1e-4))*sign(SurfaceSun.xyz+vec3(1e-6));
        vec3 exits=max((RayBoundsMin.xyz-surfacePosition)*inverse,(RayBoundsMax.xyz-surfacePosition)*inverse);
        float leave=nativeCoverage>0.0?max(min(exits.x,min(exits.y,exits.z)),1.0):1.0;
        float columns=marineHeightFieldSun(surfacePosition+normal*.1,SurfaceSun.xyz,leave);
        sunVisibility*=columns;
        // Diagnostic 8: height-field sun visibility (white lit, red shadowed by distant columns).
        if(abs(marineParameter(SOL_SURFACE_DEBUG)-8.0)<.5) {fragColor=vec4(1,columns,columns,1);return;}
    } else if(!water && abs(marineParameter(SOL_SURFACE_DEBUG)-8.0)<.5) {fragColor=vec4(.2,.2,.2,1);return;}
    float ambientOcclusion=mix(1.0,pow(clamp(occlusion,0.0,1.0),2.0),clamp(nativeCoverage*marineParameter(SOL_OCCLUSION),0.0,1.0))*solPbrOcclusion;
    vec3 illumination=solIrradiance(shading,surfaceLight.y,0.0,sunVisibility,ambientOcclusion)
        +solIrradiance(shading,0.0,surfaceLight.x,1.0,ambientOcclusion)*(1.0-tracedBlocks);
    // Native irradiance holds the traced light (sun by day, moon by night); strip exactly that.
    float tracedDirect=max(dot(normal,normalize(RaySun.xyz+vec3(0,1e-6,0))),0.0)*max(RaySun.w,0.0);
    vec3 indirect=max(nativeLight.rgb-vec3(RayOptions.x+tracedDirect*nativeLight.a),vec3(0));
    illumination+=indirect*nativeCoverage;
    vec3 lit=base*illumination*(1.0-wet*.16);
    // Snow is a climate overlay on exposed top faces, preserving atlas textures.
    float snow=Climate.y*marineParameter(SOL_SNOW)*smoothstep(.6,.98,normal.y)*smoothstep(.7,1.0,surfaceLight.y);
    lit=mix(lit,vec3(.72,.78,.83)*illumination,clamp(snow,0.0,.7));
    float emission=pow(surfaceLight.x,9.0)*max(0.0,solLuma(base)-.35)*marineParameter(SOL_EMISSION);
    if(pbr.present) emission=max(emission,pbr.emission*2.0*marineParameter(SOL_EMISSION)/.35);
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
        vec3 reflection=solSky(reflected,true)*marineSkyVisibility(surfaceLight.y);
        if(SkyColor.w>.5 && marineSurfaceRayReady()) {
            vec4 captured=texture(MarineSurfaceNormalDepth,uv);
            vec4 capturedMaterial=texture(MarineSurfaceAlbedo,uv);
            bool sameWater=floor(capturedMaterial.a+.0001)==4.0 && dot(captured.xyz,normal)>.92
                && abs(captured.w-gl_FragCoord.z)<max(.00001,gl_FragCoord.z*.01);
            if(sameWater) {
                vec4 unusedLighting,hit;
                if(solSurfaceRays(uv,captured,capturedMaterial,false,unusedLighting,hit)) {
                    float coverage=solRayCoverage(surfacePosition)*marineParameter(SOL_RAYS);
                    reflection=mix(reflection,max(hit.rgb,vec3(0)),clamp(hit.a*coverage,0.0,1.0));
                }
            }
        }
        float fresnel=.02+.98*pow(1.0-max(dot(normal,view),0.0),5.0);
        lit=mix(lit,reflection,clamp(fresnel*marineParameter(SOL_REFLECTION),0.0,1.0));
    } else if(puddle>.01) {
        // Raindrop ripples tilt the sky reflection; the traced reflection keeps the flat surface.
        vec2 ripplePosition=(surfacePosition.xz+SurfaceOrigin.xz)*3.0;
        float rippleTime=EnvironmentTime.x*2.5;
        vec2 ripple=vec2(solNoise(ripplePosition+vec2(rippleTime,0))-solNoise(ripplePosition-vec2(rippleTime,0)),
            solNoise(ripplePosition+vec2(0,rippleTime*1.3))-solNoise(ripplePosition-vec2(0,rippleTime*1.3)))*Environment.y;
        vec3 puddleNormal=normalize(normal+vec3(ripple.x,0,ripple.y)*.12);
        float fresnel=.035+.965*pow(1.0-max(dot(puddleNormal,view),0.0),5.0);
        lit*=1.0-puddle*.35;
        vec3 reflection=solSky(reflect(-view,puddleNormal),true)*marineSkyVisibility(surfaceLight.y);
        reflection=mix(reflection,max(nativeReflection.rgb,vec3(0)),clamp(nativeReflection.a*nativeCoverage,0.0,1.0));
        lit=mix(lit,reflection,puddle*fresnel*marineParameter(SOL_REFLECTION));
    } else if(material.w==2.0) {
        // Metal: no diffuse term at all. Colour is the traced reflection tinted by albedo Fresnel;
        // roughness blurs it towards the surface's own irradiance instead of towards matte paint.
        vec3 fresnel=solMaterialFresnel(base,2.0,max(dot(normal,view),0.0));
        vec3 reflection=solSky(reflected,true)*marineSkyVisibility(surfaceLight.y);
        reflection=mix(reflection,max(nativeReflection.rgb,vec3(0)),clamp(nativeReflection.a*nativeCoverage,0.0,1.0));
        vec3 environment=mix(reflection,illumination*(1.0-wet*.16),smoothstep(.25,.95,roughness)*.75);
        float metalness=clamp(marineParameter(SOL_REFLECTION)/.55,0.0,1.0);
        lit=mix(lit,environment*fresnel,metalness);
    } else if(material.y>0.0) {
        vec3 fresnel=solMaterialFresnel(base,material.w,max(dot(normal,view),0.0));
        vec3 reflection=solSky(reflected,true)*marineSkyVisibility(surfaceLight.y);
        reflection=mix(reflection,max(nativeReflection.rgb,vec3(0)),clamp(nativeReflection.a*nativeCoverage,0.0,1.0));
        vec3 weight=material.w==8.0?solSpecularWeight(fresnel,roughness,material.w)
            :clamp(fresnel*material.y*(1.0-roughness)*marineParameter(SOL_REFLECTION),vec3(0),vec3(1));
        lit=mix(lit,reflection,weight);
    }
    if(!water) {
        // Every surface gets a sun highlight; dielectrics use F0 0.04, metals their albedo.
        vec3 f0=material.w==2.0?base:vec3(material.w==8.0?.17:.04);
        lit+=solSunSpecular(shading,view,clamp(roughness*.78,.08,1.0),f0,sunVisibility*surfaceLight.y)*marineParameter(SOL_SPECULAR);
    }
    color.rgb=solDisplay(lit);
    // Diagnostic 9: LabPBR material read (red present, green smoothness, blue metal).
    if(marineParameter(SOL_SURFACE_DEBUG)>8.5) color.rgb=vec3(pbr.present?1.0:0.0,1.0-sqrt(pbr.roughness),pbr.metal?1.0:0.0);
    if(water && marineParameter(SOL_SURFACE_DEBUG)>6.5) color.rgb=vec3(clamp(.5+surfaceWave/.16,0.0,1.0),0,0);
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
