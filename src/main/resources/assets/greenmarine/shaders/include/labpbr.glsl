#ifndef GREENMARINE_LABPBR_V1
#define GREENMARINE_LABPBR_V1
// LabPBR maps laid out like the block atlas (PbrAtlas.java); 1x1 placeholders when no pack has them.
// Specular: R perceptual smoothness, G F0 (>=230 metal), B porosity (0..64), A emission (255 = none).
// Normal: RG tangent XY (DirectX, +Y down the texture), B ambient occlusion.
uniform sampler2D MarinePbrSpecular;
uniform sampler2D MarinePbrNormal;
// Bit 0: specular map, bit 1: normal map, bit 2: inferred properties. Valid black texels are not missing maps.
uniform sampler2D MarinePbrPresence;
ivec2 marinePbrTexel(vec2 uv,ivec2 size) { return clamp(ivec2(floor(uv*vec2(size))),ivec2(0),size-1); }
int marinePbrMaps(vec2 uv) {
    ivec2 size=textureSize(MarinePbrPresence,0);
    return int(texelFetch(MarinePbrPresence,marinePbrTexel(uv,size),0).r*255.0+.5);
}
// Only unknown builtin surfaces use inference. Explicit specular maps and all existing profiles win.
vec4 marineInferredMaterial(vec2 uv,vec4 builtin,vec3 albedo) {
    int maps=marinePbrMaps(uv);
    if((maps&4)==0 || (maps&1)!=0 || builtin.w!=1.0) return builtin;
    ivec2 size=textureSize(MarinePbrSpecular,0);
    vec4 value=texelFetch(MarinePbrSpecular,marinePbrTexel(uv,size),0);
    float surfaceClass=floor(value.a*255.0+.5);
    if(surfaceClass<1.0 || surfaceClass>9.0) return builtin;
    // Small local roughness variation adds texture without guessing metalness from brightness.
    if(surfaceClass==1.0) value.r=clamp(value.r+.04*(1.0-2.0*dot(albedo,vec3(.2126,.7152,.0722))),.75,.98);
    return vec4(value.rgb,surfaceClass);
}
// Addon RGBA is raw roughness/reflection/emission/class; class zero retains fallback.
vec4 marineAddonProperties(vec2 uv) {
    int maps=marinePbrMaps(uv);
    if((maps&8)==0 || (maps&1)!=0) return vec4(0);
    ivec2 size=textureSize(MarinePbrSpecular,0);
    vec4 value=texelFetch(MarinePbrSpecular,marinePbrTexel(uv,size),0);
    float surfaceClass=floor(value.a*255.0+.5);
    return surfaceClass>=1.0 && surfaceClass<=9.0?vec4(value.rgb,surfaceClass):vec4(0);
}
struct MarinePbr { bool present; float roughness; float f0; bool metal; float porosity; float emission; };
MarinePbr marinePbrMaterial(vec2 uv) {
    MarinePbr pbr=MarinePbr(false,1.0,.04,false,0.0,0.0);
    ivec2 size=textureSize(MarinePbrSpecular,0);
    if(size.x<=1 || (marinePbrMaps(uv)&1)==0) return pbr;
    vec4 texel=texelFetch(MarinePbrSpecular,marinePbrTexel(uv,size),0);
    pbr.present=true;
    pbr.roughness=(1.0-texel.r)*(1.0-texel.r);
    int f0=int(texel.g*255.0+.5),porosity=int(texel.b*255.0+.5),emission=int(texel.a*255.0+.5);
    pbr.metal=f0>=230;
    pbr.f0=pbr.metal?1.0:float(f0)/255.0;
    pbr.porosity=porosity<65?float(porosity)/64.0:0.0;
    pbr.emission=emission<255?float(emission)/254.0:0.0;
    return pbr;
}
vec3 marinePbrF0(MarinePbr pbr,vec3 albedo) { return pbr.metal?albedo:vec3(pbr.f0); }
vec3 marinePbrFresnel(MarinePbr pbr,vec3 albedo,float cosine) {
    return mix(marinePbrF0(pbr,albedo),vec3(1),pow(1.0-clamp(cosine,0.0,1.0),5.0));
}
bool marinePbrNormalsPresent() { return textureSize(MarinePbrNormal,0).x>1; }
bool marinePbrNormalsPresent(vec2 uv) { return (marinePbrMaps(uv)&2)!=0; }
// xyz tangent-space normal, w ambient occlusion.
vec4 marinePbrNormal(vec2 uv) {
    if(!marinePbrNormalsPresent(uv)) return vec4(0,0,1,1);
    ivec2 size=textureSize(MarinePbrNormal,0);
    vec4 texel=texelFetch(MarinePbrNormal,marinePbrTexel(uv,size),0);
    vec2 xy=texel.rg*2.0-1.0;
    return vec4(xy,sqrt(max(0.0,1.0-dot(xy,xy))),texel.b);
}
#endif
