#ifndef GREENMARINE_CLOUD_NOISE
#define GREENMARINE_CLOUD_NOISE
// Periodic lattice noise keeps the 4096-block floating origin continuous.
float marineCloudHash(int x,int y) {
    uint value=uint(x)*0x8da6b343u ^ uint(y)*0xd8163841u;
    value^=value>>16;value*=0x7feb352du;value^=value>>15;
    return float(value & 0x00ffffffu)/16777216.0;
}
float marineCloudMix(float a,float b,float weight) {return a+(b-a)*weight;}
float marineCloudNoise(float x,float y,int period) {
    int ix=int(floor(x)),iy=int(floor(y));
    float fx=x-float(ix),fy=y-float(iy);
    fx=fx*fx*(3.0-2.0*fx);fy=fy*fy*(3.0-2.0*fy);
    ix=((ix%period)+period)%period;iy=((iy%period)+period)%period;
    int nx=(ix+1)%period,ny=(iy+1)%period;
    return marineCloudMix(marineCloudMix(marineCloudHash(ix,iy),marineCloudHash(nx,iy),fx),
        marineCloudMix(marineCloudHash(ix,ny),marineCloudHash(nx,ny),fx),fy);
}
float marineCloudDetailWeight(float footprint) {
    float t=(footprint-.2)/.6;t=t<0.0?0.0:(t>1.0?1.0:t);
    return 1.0-t*t*(3.0-2.0*t);
}
#endif
