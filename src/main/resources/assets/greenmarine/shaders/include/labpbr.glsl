#ifndef GREENMARINE_LABPBR_V1
#define GREENMARINE_LABPBR_V1
// LabPBR maps laid out like the block atlas (PbrAtlas.java); 1x1 placeholders when no pack has them.
// Specular: R perceptual smoothness, G F0 (>=230 metal), B porosity (0..64), A emission (255 = none).
// Normal: RG tangent XY (DirectX, +Y down the texture), B ambient occlusion.
uniform sampler2D MarinePbrSpecular;
uniform sampler2D MarinePbrNormal;
struct MarinePbr { bool present; float roughness; float f0; bool metal; float porosity; float emission; };
MarinePbr marinePbrMaterial(vec2 uv) {
    MarinePbr pbr=MarinePbr(false,1.0,.04,false,0.0,0.0);
    ivec2 size=textureSize(MarinePbrSpecular,0);
    if(size.x<=1) return pbr;
    vec4 texel=texelFetch(MarinePbrSpecular,min(ivec2(uv*vec2(size)),size-1),0);
    if(dot(texel,texel)<1e-6) return pbr;
    pbr.present=true;
    pbr.roughness=(1.0-texel.r)*(1.0-texel.r);
    int f0=int(texel.g*255.0+.5),porosity=int(texel.b*255.0+.5),emission=int(texel.a*255.0+.5);
    pbr.metal=f0>=230;
    pbr.f0=pbr.metal?1.0:float(f0)/255.0;
    pbr.porosity=porosity<65?float(porosity)/64.0:0.0;
    pbr.emission=emission<255?float(emission)/254.0:0.0;
    return pbr;
}
bool marinePbrNormalsPresent() { return textureSize(MarinePbrNormal,0).x>1; }
// xyz tangent-space normal, w ambient occlusion.
vec4 marinePbrNormal(vec2 uv) {
    ivec2 size=textureSize(MarinePbrNormal,0);
    vec4 texel=texelFetch(MarinePbrNormal,min(ivec2(uv*vec2(size)),size-1),0);
    vec2 xy=texel.rg*2.0-1.0;
    return vec4(xy,sqrt(max(0.0,1.0-dot(xy,xy))),texel.b);
}
#endif
