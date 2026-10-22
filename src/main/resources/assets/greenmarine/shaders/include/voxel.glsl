#ifndef GREENMARINE_VOXEL_V1
#define GREENMARINE_VOXEL_V1
layout(std140) uniform VoxelFrame {
    vec4 VoxelOriginSize;
    vec4 VoxelTracing;
    uvec4 VoxelOccupancy[256];
};
uniform sampler2D VoxelMaterials;
struct MarineVoxelHit { bool hit; vec4 material; vec3 position; float distance; };
bool marineVoxelInside(ivec3 cell) { return all(greaterThanEqual(cell,ivec3(0))) && all(lessThan(cell,ivec3(32))); }
int marineVoxelIndex(ivec3 cell) { return (cell.z*32+cell.y)*32+cell.x; }
vec4 marineVoxelMaterial(ivec3 cell) {
    if(VoxelTracing.x<0.5 || !marineVoxelInside(cell)) return vec4(0.0);
    int index=marineVoxelIndex(cell);
    return texelFetch(VoxelMaterials,ivec2(index%256,index/256),0);
}
bool marineVoxelOccupied(ivec3 cell) {
    if(!marineVoxelInside(cell)) return false;
    int index=marineVoxelIndex(cell),word=index/32;
    return (VoxelOccupancy[word/4][word%4] & (1u<<uint(index%32)))!=0u;
}
MarineVoxelHit marineVoxelTrace(vec3 origin,vec3 direction,float maxDistance,bool occupancyOnly) {
    MarineVoxelHit result=MarineVoxelHit(false,vec4(0.0),vec3(0.0),0.0);
    if(VoxelTracing.x<0.5 || maxDistance<=0.0 || dot(direction,direction)<0.000001) return result;
    direction=normalize(direction);vec3 local=origin-VoxelOriginSize.xyz;
    float enter=0.0,exit=maxDistance;
    for(int axis=0;axis<3;axis++) {
        if(abs(direction[axis])<0.000001) {
            if(local[axis]<0.0 || local[axis]>=32.0) return result;
        } else {
            float a=-local[axis]/direction[axis],b=(32.0-local[axis])/direction[axis];
            enter=max(enter,min(a,b));exit=min(exit,max(a,b));
        }
    }
    if(enter>=exit) return result;
    ivec3 stepDirection=ivec3(sign(direction));
    ivec3 cell=ivec3(floor(local+direction*enter+vec3(stepDirection)*0.0001));
    vec3 next=vec3(1e20),advance=vec3(1e20);
    for(int axis=0;axis<3;axis++) if(abs(direction[axis])>=0.000001) {
        next[axis]=(float(cell[axis])+(stepDirection[axis]>0?1.0:0.0)-local[axis])/direction[axis];
        advance[axis]=abs(1.0/direction[axis]);
    }
    float distance=enter;
    for(int iteration=0;iteration<96;iteration++) {
        if(!marineVoxelInside(cell)) break;
        vec4 material=marineVoxelMaterial(cell);
        if(marineVoxelOccupied(cell) || (!occupancyOnly && material.a>0.0)) {
            result=MarineVoxelHit(true,material,VoxelOriginSize.xyz+vec3(cell)+0.5,distance);break;
        }
        float crossing=min(next.x,min(next.y,next.z));
        if(crossing>=exit) break;
        for(int axis=0;axis<3;axis++) if(next[axis]<=crossing+0.00001) {cell[axis]+=stepDirection[axis];next[axis]+=advance[axis];}
        distance=crossing;
    }
    return result;
}
MarineVoxelHit marineVoxelTrace(vec3 origin,vec3 direction,float maxDistance) {
    return marineVoxelTrace(origin,direction,maxDistance,false);
}
#endif
