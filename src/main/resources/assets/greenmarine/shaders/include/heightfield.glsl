#ifndef GREENMARINE_HEIGHT_FIELD_V1
#define GREENMARINE_HEIGHT_FIELD_V1
#include <greenmarine:surface.glsl>
#include <greenmarine:environment.glsl>
// 512x512 toroidal column heights (HeightField.java): texel (x mod 512, z mod 512), RG = top+2048,
// B = 512-block tile tag (3 bits per axis), A = loaded. Needs SurfaceOrigin (camera mod 4096) and
// Environment.x (camera Y); positions are camera-relative.
uniform sampler2D MarineHeightField;
// Sun visibility from columns along the sun ray, starting `start` blocks out. Coarse (no overhang
// holes), meant for occluders beyond the traced scene such as distant mountains.
float marineHeightFieldSun(vec3 position,vec3 sun,float start) {
    if(sun.y<=.03) return 1.0;
    vec3 origin=position+vec3(SurfaceOrigin.x,Environment.x,SurfaceOrigin.z);
    float t=max(start,1.0);
    for(int i=0;i<48;i++) {
        vec3 p=origin+sun*t;
        if(p.y>330.0) break;
        vec2 column=floor(p.xz);
        vec4 texel=texelFetch(MarineHeightField,ivec2(mod(column,512.0)),0);
        ivec2 tile=ivec2(floor(column/512.0))&7;
        int tag=int(texel.b*255.0+.5);
        if(texel.a>.5 && tag==(tile.x|(tile.y<<3))) {
            float top=texel.r*255.0+texel.g*255.0*256.0-2048.0;
            if(top>p.y+.25) return 0.0;
        }
        // ~700 blocks in 48 steps: 1-block steps near the surface, then 12% growth.
        t+=max(1.0,t*.12);
    }
    return 1.0;
}
#endif
