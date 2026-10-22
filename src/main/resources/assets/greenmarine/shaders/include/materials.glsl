#ifndef GREENMARINE_MATERIALS
#define GREENMARINE_MATERIALS
layout(std140) uniform MarineMaterialFrame {
    vec4 MaterialRects[32];
    vec4 MaterialProperties[32]; // Roughness, reflectance, sunlight transmission, surface class.
    vec4 MaterialInfo;
};
layout(std140) uniform MarineMaterialExtension {
    vec4 AdditionalMaterialRects[8];
    vec4 AdditionalMaterialProperties[8];
    vec4 AdditionalMaterialInfo;
};
vec4 marineBlockMaterial(vec2 uv) {
    for(int i=0;i<min(32,int(MaterialInfo.x));i++) {
        vec4 rect=MaterialRects[i];
        if(all(greaterThanEqual(uv,rect.xy)) && all(lessThan(uv,rect.zw))) return MaterialProperties[i];
    }
    for(int i=0;i<clamp(int(AdditionalMaterialInfo.x),0,8);i++) {
        vec4 rect=AdditionalMaterialRects[i];
        if(all(greaterThanEqual(uv,rect.xy)) && all(lessThan(uv,rect.zw))) return AdditionalMaterialProperties[i];
    }
    return vec4(.96,0,0,1);
}
#endif
