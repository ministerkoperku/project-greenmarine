#version 330
#extension GL_ARB_separate_shader_objects : require
#include <greenmarine:solstice.glsl>
uniform sampler2D CachedColorSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec2 pixel=1.0/vec2(textureSize(CachedColorSampler,0));
    vec3 bright=vec3(0);float total=0.0;
    int radius=Context.y>2.5?2:1;
    for(int y=-2;y<=2;y++) for(int x=-2;x<=2;x++) {
        if(abs(x)>radius || abs(y)>radius) continue;
        float weight=1.0/(1.0+float(x*x+y*y));
        vec3 color=texture(CachedColorSampler,clamp(texCoord+vec2(x,y)*pixel*3.0,vec2(.001),vec2(.999))).rgb;
        float luminance=solLuma(color);
        float excess=max(0.0,luminance-marineParameter(SOL_THRESHOLD));
        bright+=color*(excess/max(luminance,.001))*weight;
        total+=weight;
    }
    fragColor=vec4(bright/max(total,1.0),1.0);
}
