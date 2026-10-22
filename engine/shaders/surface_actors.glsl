// Posed vanilla model cubes. Distances remain world-space ray parameters because
// transformed directions are deliberately not normalized.
struct ActorBox {
    mat4 inverseModel;
    vec4 lower;
    vec4 upper;
    vec4 u[6];
    vec4 v[6];
};
layout(std430,set=0,binding=7) readonly buffer SurfaceActors {
    vec4 ActorInfo;
    ActorBox ActorBoxes[64];
};
layout(set=0,binding=8) uniform sampler2D ActorSkin;
struct ActorHit { bool found; float distance; vec3 normal; vec3 albedo; };
ActorHit traceActors(vec3 origin,vec3 direction,float reach) {
    ActorHit closest;closest.found=false;closest.distance=reach;
    closest.normal=vec3(0);closest.albedo=vec3(0);
    for(int actor=0;actor<clamp(int(ActorInfo.x),0,64);actor++) {
        ActorBox box=ActorBoxes[actor];
        vec3 localOrigin=(box.inverseModel*vec4(origin,1)).xyz;
        vec3 localDirection=(box.inverseModel*vec4(direction,0)).xyz;
        if(any(isnan(localOrigin)) || any(isinf(localOrigin))
            || any(isnan(localDirection)) || any(isinf(localDirection))) continue;
        float nearDistance=-1e30,farDistance=1e30;
        int nearFace=0,farFace=0;bool intersects=true;
        for(int axis=0;axis<3;axis++) {
            if(abs(localDirection[axis])<1e-8) {
                if(localOrigin[axis]<box.lower[axis] || localOrigin[axis]>box.upper[axis]) intersects=false;
            } else {
                float low=(box.lower[axis]-localOrigin[axis])/localDirection[axis];
                float high=(box.upper[axis]-localOrigin[axis])/localDirection[axis];
                int lowFace=axis*2,highFace=axis*2+1;
                if(low>high) {float swapDistance=low;low=high;high=swapDistance;int swapFace=lowFace;lowFace=highFace;highFace=swapFace;}
                if(low>nearDistance) {nearDistance=low;nearFace=lowFace;}
                if(high<farDistance) {farDistance=high;farFace=highFace;}
            }
        }
        if(!intersects || nearDistance>farDistance || farDistance<=.015) continue;
        // Test both faces: a transparent outer-layer entry can expose its opaque
        // exit, while an origin already inside the box must test the exit only.
        for(int side=0;side<2;side++) {
            float distance=side==0?nearDistance:farDistance;
            int face=side==0?nearFace:farFace;
            if(distance<=.015 || distance>=closest.distance) continue;
            vec4 localHit=vec4(localOrigin+localDirection*distance,1);
            vec2 uv=vec2(dot(box.u[face],localHit),dot(box.v[face],localHit));
            vec4 skin=textureLod(ActorSkin,uv,0.0);
            if(skin.a<=.1) continue;
            vec3 localNormal=vec3(0);localNormal[face/2]=(face%2)==1?1.0:-1.0;
            vec3 normal=normalize(transpose(mat3(box.inverseModel))*localNormal);
            if(dot(normal,direction)>0)normal=-normal;
            closest.found=true;closest.distance=distance;closest.normal=normal;
            closest.albedo=pow(max(skin.rgb,vec3(0)),vec3(2.2));
        }
    }
    return closest;
}
// Local player sun occlusion. Captured boxes include first-person bodies, so the
// same payload casts the player's shadow in every camera mode.
float actorSunVisibility(vec3 origin,vec3 sun) {
    if(ActorInfo.x<.5) return 1.0;
    return traceActors(origin,sun,24.0).found?0.0:1.0;
}
