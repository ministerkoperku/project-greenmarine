#ifndef GREENMARINE_TEXTURE_HISTORY
#define GREENMARINE_TEXTURE_HISTORY
// Capture albedo uses RGBA16F. Accept its rounding precision independently in
// each channel, so bright channels cannot conceal texture changes in dark ones.
bool marineHistoryChannelMatches(float current,float previous) {
    float tolerance=max(max(abs(current),abs(previous))*(1.0/1024.0),1.0/16777216.0);
    return abs(current-previous)<=tolerance;
}
#endif
