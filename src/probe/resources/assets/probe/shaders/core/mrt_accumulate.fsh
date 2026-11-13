#version 450
layout(std140) uniform IrisUniforms {
    float width; float height; int counter; int day;
    mat4 projection; mat4 inverseProjection;
    vec3 sun; float altitude;
    int brightness[2];
};
layout(std430) buffer UniformCheck { vec4 uniformCheck; };
bool validUniforms() {
    mat4 product=projection*inverseProjection;
    float error=0.0;
    for(int c=0;c<4;c++)for(int r=0;r<4;r++)error=max(error,abs(product[c][r]-(c==r?1.0:0.0)));
    return width>0.0 && height>0.0 && counter>=0 && day>=0 && error<0.002
        && length(sun)>50.0 && !isnan(altitude)
        && brightness[0]>=0 && brightness[0]<=240 && brightness[1]>=0 && brightness[1]<=240;
}
uniform sampler2D PreviousRadiance;
uniform usampler2D PreviousMaterial;
uniform sampler2D PreviousFrameClear;
layout(location=0) out vec4 radiance;
layout(location=1) out uint material;
layout(location=2) out float frameClear;
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy);
    if(p.x==0)discard;
    radiance=texelFetch(PreviousRadiance,p,0)+vec4(validUniforms() && uniformCheck.w==1.0?1.0:1000.0,0,0,0);
    material=texelFetch(PreviousMaterial,p,0).r+1u;
    frameClear=texelFetch(PreviousFrameClear,p,0).r+1.0;
}
