#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:frame.glsl>
#include <greenmarine:rays.glsl>
uniform sampler2D ColorSampler;
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec3 globalIllumination(vec3 normal) {
    return vec3(RayOptions.x+max(dot(normal,RaySun.xyz),0.0)*RaySun.w)
        +vec3(.10,.15,.22)*RayOptions.y*(RayOptions.x/.45);
}
void main() {
    vec4 scene=texture(SceneSampler,texCoord);
    vec4 linearScene=vec4(pow(max(scene.rgb,vec3(0)),vec3(2.2)),scene.a);
    float depth=texture(DepthSampler,texCoord).r;
    if(!marineHardwareRayReady()) {fragColor=vec4(1,1,1,0);return;}
    // Derivatives must run before the per-pixel sky branch; clamp sky to a finite reconstruction.
    vec3 pos=marineRelativeWorldPosition(texCoord,max(depth,1e-6));
    vec3 normal=normalize(cross(dFdx(pos),dFdy(pos)));
    if(dot(normal,pos)>0.0) normal=-normal;
    if(depth<=0.0 || depth>=1.0) {fragColor=vec4(1,1,1,0);return;}
    // Find the half-resolution surface on the same world plane, rather than blending silhouette depths.
    vec2 pixel=floor(texCoord*RayInfo.yz-.5);
    vec2 selected=vec2(0);vec4 surface=vec4(0);float best=1e20;
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        vec2 uv=(pixel+vec2(x,y)+.5)/RayInfo.yz;
        vec4 candidate=texture(RayNormalDepth,uv);
        if(length(candidate.xyz)<.5) continue;
        vec3 candidatePos=marineRelativeWorldPosition(uv,candidate.w);
        vec3 delta=candidatePos-pos;
        float score=abs(dot(delta,candidate.xyz))+length(delta)*.01;
        if(score<best) {best=score;selected=uv;surface=candidate;}
    }
    float footprint=max(.05,length(pos)*3.0/min(RayInfo.y,RayInfo.z));
    vec3 hitPos=marineRelativeWorldPosition(selected,surface.w);
    bool matched=length(surface.xyz)>.5 && abs(dot(hitPos-pos,surface.xyz))<.03+length(pos)*.001
        && distance(hitPos,pos)<footprint*2.0;
    vec3 illumination=globalIllumination(normal);
    if(matched) {
        vec3 light=vec3(0);float weight=0;
        for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
            vec2 uv=selected+vec2(x,y)/RayInfo.yz;
            vec4 neighbor=texture(RayNormalDepth,uv);
            vec3 neighborPos=marineRelativeWorldPosition(uv,neighbor.w);
            if(dot(neighbor.xyz,surface.xyz)>.95 && abs(dot(neighborPos-hitPos,surface.xyz))<.04) {
                // Irradiance uses the same nearest texel as its normal/depth; no albedo division.
                light+=texelFetch(RayLighting,clamp(ivec2(uv*RayInfo.yz),ivec2(0),ivec2(RayInfo.yz)-1),0).rgb;weight+=1.0;
            }
        }
        vec3 edge=min(pos-RayBoundsMin.xyz,RayBoundsMax.xyz-pos);
        float coverage=smoothstep(0.0,8.0,min(edge.x,min(edge.y,edge.z)));
        illumination=mix(globalIllumination(surface.xyz),light/max(weight,1.0),coverage);
    }
    // Keep full-resolution raster color in compose; this half-resolution image stores lighting only.
    fragColor=vec4(mix(vec3(1),max(illumination,vec3(0)),marineParameter(5)),0);
}
