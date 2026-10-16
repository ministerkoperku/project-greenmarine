#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:solstice.glsl>
#include <greenmarine:surface_rays.glsl>
#include <greenmarine:rays.glsl>
#include <greenmarine:history.glsl>
uniform sampler2D ColorSampler;
uniform sampler2D SolsticeScene;
uniform sampler2D DepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 cached=texture(SolsticeScene,texCoord);
    vec3 color=cached.rgb;
    if(Context.y>.5) color+=texture(ColorSampler,texCoord).rgb*marineParameter(SOL_BLOOM);
    color=solTone(color);
    vec4 current=vec4(0),material=vec4(0);float materialClass=0.0;
    if(SkyColor.w>.5) {
        current=texture(MarineSurfaceNormalDepth,texCoord);
        material=texelFetch(MarineSurfaceAlbedo,ivec2(texCoord*vec2(textureSize(MarineSurfaceAlbedo,0))),0);
        materialClass=floor(material.a+.0001);
    }
    if(HistoryInfo.x>.5 && HistoryInfo.y>.5 && dot(current.xyz,current.xyz)>.5
        && current.w>1e-6 && abs(texture(DepthSampler,texCoord).r-current.w)<max(.000002,abs(current.w)*.002) && materialClass<2.0 && marineParameter(SOL_SURFACE_DEBUG)<.5) {
        vec3 position=marineRelativeWorldPosition(texCoord,current.w);
        vec4 previousClip=HistoryProjection*HistoryView*vec4(position+HistoryCameraDelta.xyz,1.0);
        if(previousClip.w>0.0) {
            vec3 projected=previousClip.xyz/previousClip.w;
            vec2 uv=projected.xy*.5+.5;
            if(all(greaterThan(uv,vec2(.002))) && all(lessThan(uv,vec2(.998)))) {
                vec4 previous=texture(HistoryNormalDepth,uv);
                float expected=Context.x>.5?projected.z:projected.z*.5+.5;
                bool consistent=dot(current.xyz,previous.xyz)>.93 && abs(previous.w-expected)<max(.00001,abs(expected)*.012);
                vec4 previousMaterial=texture(HistoryAlbedoMaterial,uv);
                vec3 albedoDelta=abs(material.rgb-previousMaterial.rgb);
                consistent=consistent && abs(texture(HistorySceneDepth,uv).r-previous.w)<max(.000002,abs(previous.w)*.002)
                    && max(albedoDelta.r,max(albedoDelta.g,albedoDelta.b))<.035 && abs(material.a-previousMaterial.a)<.015;
                if(consistent) {
                    // Match the validated nearest surface texel at entity/material boundaries.
                    vec3 old=texelFetch(HistorySampler,ivec2(uv*vec2(textureSize(HistorySampler,0))),0).rgb;
                    // Reproject only compatible land; settle new lighting within a short time window.
                    float settling=RayOptions.w>.5?1.0-smoothstep(.045,.09,RayOptions.z):0.0;
                    float limit=mix(.045,.5,settling)*(1.0-Climate.z);
                    old=clamp(old,color-vec3(limit),color+vec3(limit));
                    float temporal=exp(-max(HistoryInfo.w,.001)/.022);
                    float stability=min(.78,temporal)*min(1.0,HistoryInfo.z/(HistoryInfo.z+1.0))*(1.0-Climate.z);
                    float response=temporal*settling*(1.0-Climate.z);
                    color=mix(color,old,clamp(max(stability,response),0.0,.96));
                }
            }
        }
    }
    fragColor=vec4(color,cached.a);
}
