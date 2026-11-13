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
uniform sampler2D Latest;
uniform sampler2D Earliest;
uniform usampler2D MaterialData;
uniform sampler2D ClearedData;
layout(location=0) out vec4 color;
void main() {
    ivec2 p=ivec2(1,0);
    float latest=texelFetch(Latest,p,0).r;
    float earliest=texelFetch(Earliest,p,0).r;
    uint material=texelFetch(MaterialData,p,0).r;
    bool valid=validUniforms() && uniformCheck.w==1.0 && abs(latest-earliest-2.0)<.001 && material+3u==uint(latest-.25)
        && texelFetch(ClearedData,p,0).r==10.0;
    color=valid?vec4(0,1,0,1):vec4(1,0,0,1);
}
