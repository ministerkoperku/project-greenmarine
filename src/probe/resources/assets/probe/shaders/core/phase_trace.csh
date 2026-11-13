#version 450
#define PHASE 0
layout(local_size_x=1) in;
layout(std140) uniform IrisUniforms {
    float width; float height; int counter; int day;
    mat4 projection; mat4 inverseProjection;
    vec3 sun; float altitude;
    int brightness[2];
};
layout(std430) buffer PhaseTrace { uint trace[16]; };
void main() {
#if PHASE == 0
    if(trace[0]!=0u)trace[3]++;
    trace[0]++;
#elif PHASE == 1
    if(trace[2]!=(trace[1]==0u?0u:6u))trace[3]++;
    trace[1]++;
    trace[4]=uint(counter);
#else
    if(trace[2]!=uint(PHASE-1) || trace[4]!=uint(counter))trace[3]++;
#endif
    trace[2]=uint(PHASE);
    trace[8+PHASE]++;
}
