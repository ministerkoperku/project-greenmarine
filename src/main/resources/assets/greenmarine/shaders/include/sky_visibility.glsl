#ifndef GREENMARINE_SKY_VISIBILITY
#define GREENMARINE_SKY_VISIBILITY
// A conservative fallback for unavailable geometry rays, not directional sky occlusion.
float marineSkyVisibility(float skylight) {
    float sky=skylight<0.0?0.0:(skylight>1.0?1.0:skylight);
    return sky*sky*(3.0-2.0*sky);
}
#endif
