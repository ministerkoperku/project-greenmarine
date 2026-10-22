#ifndef GREENMARINE_DIFFUSE_SAMPLES
#define GREENMARINE_DIFFUSE_SAMPLES
// Fixed cosine-weighted quadrature. A surface crossing a world cell must not
// rotate all its secondary directions; the reduced producer grid moves with P.
vec3 marineDiffuseDirection(vec3 normal,int index) {
    vec3 tangent=normalize(abs(normal.y)<.9?cross(normal,vec3(0,1,0)):cross(normal,vec3(1,0,0)));
    vec3 bitangent=cross(normal,tangent);
    float angle=.785398163+2.39996323*float(index);
    float radius=sqrt((float(index)+.5)/4.0);
    return normalize(tangent*cos(angle)*radius+bitangent*sin(angle)*radius+normal*sqrt(1.0-radius*radius));
}
#endif
