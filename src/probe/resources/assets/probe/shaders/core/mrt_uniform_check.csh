#version 450
layout(local_size_x=1) in;
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
void main(){uniformCheck=vec4(width,height,float(counter),validUniforms()?1.0:0.0);}
