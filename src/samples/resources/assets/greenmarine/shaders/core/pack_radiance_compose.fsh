#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_GOOGLE_include_directive : require
#include <greenmarine:frame.glsl>
uniform sampler2D ColorSampler;
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 scene=texture(SceneSampler,texCoord);
    if(Context.y<2.0 || Context.z!=2.0) {fragColor=scene;return;}
    float depth=texture(DepthSampler,texCoord).r;
    if(depth<=0.0) {fragColor=scene;return;}
    float distance=-marineViewPosition(texCoord,depth).z;
    vec2 size=vec2(textureSize(ColorSampler,0));
    vec2 grid=texCoord*size-0.5;
    vec2 base=floor(grid),fraction=fract(grid);
    vec3 indirect=vec3(0.0);float weights=0.0;
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        vec2 uv=(base+vec2(x,y)+0.5)/size;
        vec4 sampleValue=texture(ColorSampler,uv);
        float spatial=(x==0?1.0-fraction.x:fraction.x)*(y==0?1.0-fraction.y:fraction.y);
        float weight=sampleValue.a>0.0?spatial*exp(-abs(sampleValue.a-distance)/max(0.08,distance*0.02)):0.0;
        indirect+=sampleValue.rgb*weight;weights+=weight;
    }
    indirect/=max(weights,0.00001);
    vec3 linear=pow(max(scene.rgb,vec3(0.0)),vec3(2.2));
    fragColor=vec4(clamp(pow(linear+indirect,vec3(1.0/2.2)),0.0,1.0),scene.a);
}
