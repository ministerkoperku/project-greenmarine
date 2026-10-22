#ifndef GREENMARINE_OUTPUT_QUALITY
#define GREENMARINE_OUTPUT_QUALITY
int marineBayerPair(int x,int y) {return 2*((x&1)^(y&1))+(y&1);}
float marineOutputDither(int x,int y) {
    int rank=4*marineBayerPair(x,y)+marineBayerPair(x>>1,y>>1);
    return ((float(rank)+.5)/16.0-.5)/255.0;
}
float marinePuddleRetention(float surfaceClass) {
    return surfaceClass>6.5 && surfaceClass<7.5?0.0:1.0;
}
#endif
