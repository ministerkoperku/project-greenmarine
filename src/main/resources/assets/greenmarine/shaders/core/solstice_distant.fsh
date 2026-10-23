#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:solstice_scene.glsl>
#include <greenmarine:heightfield.glsl>
// Distant Horizons LODs relit like Solstice terrain outside native ray bounds (no shadows).
layout(std140) uniform DistantFrame {
    vec4 DistantProjection; // m22, m23, zero-to-one range, reverse Z
};
uniform sampler2D ColorSampler;
uniform sampler2D DistantDepth;
uniform sampler2D LightmapSampler;
layout(location=0) out vec4 fragColor;
vec3 distantPosition(ivec2 pixel,out bool valid) {
    float depth=texelFetch(DistantDepth,pixel,0).r;
    bool reverse=DistantProjection.w>.5;
    valid=reverse?depth>1e-7:depth<.9999999;
    float ndc=DistantProjection.z>.5?depth:depth*2.0-1.0;
    float distance=DistantProjection.y/(ndc+DistantProjection.x);
    // Direction from two points on the pixel's ray: view bobbing shifts the projection, and scaling one
    // near point (~0.1 block from the eye) out to a LOD multiplied that shift into hundreds of blocks,
    // darkening LODs while walking and restoring them when standing still.
    vec2 uv=(vec2(pixel)+.5)/Frame.xy;
    vec3 ray=marineViewPosition(uv,.25)-marineViewPosition(uv,.5);
    if(ray.z>0.0) ray=-ray;
    if(dot(ray,ray)<1e-14) ray=marineViewPosition(uv,.5);
    return (InverseViewRotation*vec4(ray*(distance/max(-ray.z,1e-6)),0.0)).xyz;
}
// Of the two one-sided differences take the one staying on the same LOD face; screen derivatives
// straddled LOD steps and flipped the axis-snapped normal while walking.
vec3 sameFaceDelta(vec3 centre,vec3 before,bool beforeValid,vec3 after,bool afterValid) {
    vec3 forward=after-centre,backward=centre-before;
    if(!afterValid) return backward;
    if(!beforeValid) return forward;
    return dot(forward,forward)<dot(backward,backward)?forward:backward;
}
void main() {
    ivec2 pixel=ivec2(gl_FragCoord.xy);
    vec2 uv=gl_FragCoord.xy/Frame.xy;
    bool valid,l,r,d,u;
    vec3 position=distantPosition(pixel,valid);
    if(!valid) discard;
    // DH 3.3 also writes LOD depth into the host buffer, so host depth alone cannot tell LODs from
    // chunks. Host geometry clearly nearer than the LOD keeps its own (Solstice) shading.
    float hostDepth=texture(DepthSampler,uv).r;
    if(hostDepth>1e-6 && length(marineRelativeWorldPosition(uv,hostDepth))<length(position)*.97-1.0) discard;
    vec3 dx=sameFaceDelta(position,distantPosition(pixel-ivec2(1,0),l),l,distantPosition(pixel+ivec2(1,0),r),r);
    vec3 dy=sameFaceDelta(position,distantPosition(pixel-ivec2(0,1),d),d,distantPosition(pixel+ivec2(0,1),u),u);
    // LODs are axis-aligned boxes: snap to the dominant axis.
    vec3 n=cross(dx,dy),a=abs(n);
    n=a.y>=a.x && a.y>=a.z?vec3(0,sign(n.y),0):(a.x>=a.z?vec3(sign(n.x),0,0):vec3(0,0,sign(n.z)));
    vec3 view=normalize(position-SurfaceEye.xyz);
    if(dot(n,view)>0.0) n=-n;
    // DH multiplies the host lightmap (sky 15, block 0) into LOD colour; divide it back out.
    vec3 light=max(texelFetch(LightmapSampler,ivec2(0,15),0).rgb,vec3(.04));
    // Rescale instead of clamping: a dark night lightmap made blue water saturate to white.
    vec3 albedo=texture(ColorSampler,uv).rgb/light;
    albedo/=max(1.0,max(albedo.r,max(albedo.g,albedo.b)));
    vec3 base=solLinear(albedo);
    // Mountain shadows on LODs from the column height field (no traced data out here).
    float sunVisibility=dot(n,SurfaceSun.xyz)>0.0?marineHeightFieldSun(position+n*.5,SurfaceSun.xyz,1.0):1.0;
    vec3 lit=base*solIrradiance(n,1.0,0.0,sunVisibility);
    // DH carries no material ids: upward bluish faces (also greenish swamp water) are water.
    if(n.y>.5 && albedo.b>albedo.r*1.12 && albedo.b>albedo.g*.85) {
        // Mirror the near water of solstice_terrain.glsl: absorption body plus a floor seen through
        // a nominal 16 blocks (DH has no water depth), 0.9 opacity, then Fresnel sky. No biome hue
        // multiplier: near water does not use one, and it made LOD water brighter than real chunks.
        vec3 absorption=vec3(.012,.095,.14)*(.3+.7*solDay());
        vec3 floorColor=solLinear(vec3(.42,.40,.32))*solIrradiance(vec3(0,1,0),.5,0.0,1.0);
        vec3 transmission=exp(-vec3(.38,.10,.052)*16.0*marineParameter(SOL_ABSORPTION));
        vec3 body=mix(absorption,floorColor,transmission);
        body=mix(floorColor*.6,body,marineParameter(SOL_WATER_ALPHA));
        vec3 reflection=solSky(reflect(view,n),true);
        float fresnel=.02+.98*pow(1.0-max(dot(n,-view),0.0),5.0);
        lit=mix(body,reflection,clamp(fresnel*marineParameter(SOL_REFLECTION),0.0,1.0));
    }
    fragColor=vec4(solDisplay(solHeightFog(lit,position)),1.0);
}
