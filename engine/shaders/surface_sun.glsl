// Primary sun rays integrate cutout coverage over the reduced receiver pixel.
// Opaque casters, GI/volumetric rays and detailed reflection alpha stay unchanged.
vec3 marineShadowPosition(vec2 uv,float depth) {
    float z=Viewport.z>.5?depth:depth*2.0-1.0;
    vec4 local=InverseProjection*vec4(uv*2.0-1.0,z,1.0);
    return Camera.xyz+(InverseView*vec4(local.xyz/local.w,1.0)).xyz;
}
vec3 marineShadowFootprint(vec2 uv,float depth,vec3 pos,vec3 normal) {
    vec3 eye=Camera.xyz+InverseView[3].xyz;
    vec3 ray=marineShadowPosition(uv,depth)-eye;
    float denominator=dot(normal,ray);
    return abs(denominator)>1e-6?eye+ray*(dot(normal,pos-eye)/denominator)-pos:vec3(0);
}
float marineShadowAlphaMip(vec2 uv,vec2 lower,vec2 upper,int level) {
    ivec2 size=textureSize(Atlas,level);
    vec2 midpoint=(lower+upper)*.5,halfTexel=.5/vec2(size);
    uv=clamp(uv,min(lower+halfTexel,midpoint),max(upper-halfTexel,midpoint));
    vec2 pixel=uv*vec2(size)-.5;ivec2 base=ivec2(floor(pixel));vec2 f=fract(pixel);
    ivec2 a=clamp(base,ivec2(0),size-1),b=clamp(base+1,ivec2(0),size-1);
    return mix(mix(texelFetch(Atlas,a,level).a,texelFetch(Atlas,ivec2(b.x,a.y),level).a,f.x),
        mix(texelFetch(Atlas,ivec2(a.x,b.y),level).a,texelFetch(Atlas,b,level).a,f.x),f.y);
}
float marineShadowAlpha(Triangle t,vec2 bary,mat3 objectToWorld,vec3 sun,vec3 dx,vec3 dy) {
    vec3 e1=objectToWorld*(t.b.xyz-t.a.xyz),e2=objectToWorld*(t.c.xyz-t.a.xyz);
    float aa=dot(e1,e1),bb=dot(e2,e2),ab=dot(e1,e2),determinant=aa*bb-ab*ab;
    vec3 n=cross(e1,e2);float incidence=dot(n,sun);
    if(determinant<1e-12 || abs(incidence)<1e-8) return step(.100001,triangleSurface(t,bary).a);
    // Project the receiver footprint onto the caster along the shadow ray, then
    // convert world deltas into triangle UV gradients (including external transforms).
    dx-=sun*(dot(n,dx)/incidence);dy-=sun*(dot(n,dy)/incidence);
    vec3 u=(e1*bb-e2*ab)/determinant,v=(e2*aa-e1*ab)/determinant;
    vec2 du=t.uv01.zw-t.uv01.xy,dv=t.uv2.xy-t.uv01.xy;
    vec2 size=vec2(textureSize(Atlas,0));
    vec2 uvx=(du*dot(u,dx)+dv*dot(v,dx))*size,uvy=(du*dot(u,dy)+dv*dot(v,dy))*size;
    float span=max(length(uvx),length(uvy));
    if(span<1e-5) return step(.100001,triangleSurface(t,bary).a);
    float lod=clamp(log2(max(span,1.0)),0.0,float(textureQueryLevels(Atlas)-1));
    int first=int(floor(lod)),next=min(first+1,textureQueryLevels(Atlas)-1);
    vec2 lower=min(t.uv01.xy,min(t.uv01.zw,t.uv2.xy)),upper=max(t.uv01.xy,max(t.uv01.zw,t.uv2.xy));
    vec2 uv=triangleUV(t,bary);
    return clamp(mix(marineShadowAlphaMip(uv,lower,upper,first),marineShadowAlphaMip(uv,lower,upper,next),fract(lod)),0.0,1.0);
}
float traceSurfaceSunTransmission(vec3 pos,vec3 normal,vec3 sun,vec2 uv,float depth) {
    rayQueryEXT ray;
    rayQueryInitializeEXT(ray,World,gl_RayFlagsNoneEXT,255,pos+normal*.035,.015,sun,96.0);
    float transmission=1.0;bool footprintReady=false;vec3 dx=vec3(0),dy=vec3(0);
    while(rayQueryProceedEXT(ray)) {
        if(rayQueryGetIntersectionTypeEXT(ray,false)!=gl_RayQueryCandidateIntersectionTriangleEXT)continue;
        Triangle t=marineFetchTriangle(rayQueryGetIntersectionInstanceCustomIndexEXT(ray,false),rayQueryGetIntersectionPrimitiveIndexEXT(ray,false));
        float opacity=1.0-clamp(t.tint.w,0.0,1.0);
        if(opacity<=0.0)continue;
        if(t.uv2.z>=.5 && t.uv2.z<1.5) {
            // Translucent atlas alpha (e.g. glass) is not cutout coverage. Preserve
            // its threshold and material transmission; filter only tagged cutouts.
            if(t.uv2.z<1.125 || t.uv2.z>1.375) {
                if(triangleSurface(t,rayQueryGetIntersectionBarycentricsEXT(ray,false)).a<=.1)continue;
                transmission*=1.0-opacity;
                if(transmission<.01) {rayQueryTerminateEXT(ray);return 0.0;}
                continue;
            }
            if(!footprintReady) {
                dx=marineShadowFootprint(uv+vec2(1.0/Viewport.x,0),depth,pos,normal);
                dy=marineShadowFootprint(uv+vec2(0,1.0/Viewport.y),depth,pos,normal);footprintReady=true;
            }
            opacity*=marineShadowAlpha(t,rayQueryGetIntersectionBarycentricsEXT(ray,false),
                mat3(rayQueryGetIntersectionObjectToWorldEXT(ray,false)),sun,dx,dy);
        }
        transmission*=1.0-opacity;
        if(transmission<.01) {rayQueryTerminateEXT(ray);return 0.0;}
    }
    return transmission;
}
