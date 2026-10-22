#ifndef GREENMARINE_REFLECTION_POLICY
#define GREENMARINE_REFLECTION_POLICY
// Scalar functions are also executed by offline regression tests.
float marineMaterialF0(float albedo,float surfaceClass) {
    if(surfaceClass>8.5 && surfaceClass<9.5) return .04;
    if(surfaceClass>7.5 && surfaceClass<8.5) return .17;
    if(surfaceClass>1.5 && surfaceClass<2.5) return albedo<.02?.02:(albedo>.98?.98:albedo);
    if(surfaceClass>5.5 && surfaceClass<6.5) return .02;
    if(surfaceClass>4.5 && surfaceClass<5.5) return .04;
    return .035;
}
float marineFresnel(float f0,float cosine) {
    float angle=cosine<0.0?0.0:(cosine>1.0?1.0:cosine);
    float grazing=1.0-angle;
    return f0+(1.0-f0)*grazing*grazing*grazing*grazing*grazing;
}
bool marineOpaqueSurfaceVisible(float sceneDepth,float surfaceDepth) {
    if(sceneDepth<=0.0 || surfaceDepth<=0.0) return false;
    float difference=sceneDepth-surfaceDepth;
    if(difference<0.0) difference=-difference;
    float tolerance=surfaceDepth*.002;
    if(tolerance<.000002) tolerance=.000002;
    return difference<=tolerance;
}
int marineCaptureCoordinate(int texel,int outputSize,int captureSize) {
    return ((texel*2+1)*captureSize)/(outputSize*2);
}
bool marineReflectionSurfaceMatches(float planeDelta,float tangentDistance,float eyeDistance,float capturePixels) {
    if(planeDelta<0.0) planeDelta=-planeDelta;
    float planeTolerance=eyeDistance*.001;
    if(planeTolerance<.015) planeTolerance=.015;
    float tangentTolerance=eyeDistance*8.0/capturePixels;
    if(tangentTolerance<.2) tangentTolerance=.2;
    return planeDelta<=planeTolerance && tangentDistance<=tangentTolerance;
}
float marineReflectionFilterRadius(float roughness,float surfaceClass) {
    if(!((surfaceClass>1.5 && surfaceClass<3.5) || (surfaceClass>5.5 && surfaceClass<6.5) || (surfaceClass>7.5 && surfaceClass<9.5))) return 1.0;
    float r=roughness<0.0?0.0:(roughness>1.0?1.0:roughness);
    return 1.0+.8*r*r;
}
float marineReflectionTapWeight(float distance,float radius) {
    if(distance<0.0) distance=-distance;
    float weight=1.0-distance/radius;
    return weight>0.0?weight:0.0;
}
float marineQualityReflectionRadius(float roughness,float surfaceClass,float quality) {
    return quality<1.5?1.0:marineReflectionFilterRadius(roughness,surfaceClass);
}
bool marineNeedsSurfaceReflection(float surfaceClass) {
    return (surfaceClass>=2.0 && surfaceClass<=6.0) || surfaceClass==8.0 || surfaceClass==9.0;
}
float marineSampleCoverage(float accepted,float footprint) {
    if(footprint<=0.0) return 0.0;
    float coverage=accepted/footprint;
    return coverage<0.0?0.0:(coverage>1.0?1.0:coverage);
}
// Producer locations are snapped to full-resolution capture texel centres.
int marineReflectionBase(float uv,int outputSize,int captureSize) {
    if(outputSize<=1) return 0;
    int first=int(floor(uv*float(outputSize)-.5));
    first=first<0?0:(first>outputSize-2?outputSize-2:first);
    float pixel=uv*float(captureSize);
    if(first>0 && float(marineCaptureCoordinate(first,outputSize,captureSize))+.5>pixel) first--;
    if(first<outputSize-2 && float(marineCaptureCoordinate(first+1,outputSize,captureSize))+.5<pixel) first++;
    return first;
}
float marineReflectionFraction(float uv,int first,int outputSize,int captureSize) {
    if(outputSize<=1) return 0.0;
    float left=float(marineCaptureCoordinate(first,outputSize,captureSize))+.5;
    float right=float(marineCaptureCoordinate(first+1,outputSize,captureSize))+.5;
    float fraction=(uv*float(captureSize)-left)/(right-left);
    return fraction<0.0?0.0:(fraction>1.0?1.0:fraction);
}
bool marineReflectionFootprintMatches(float planeDelta,float tangentDistance,float eyeDistance,float capturePixels,float incidence) {
    if(incidence<0.0) incidence=-incidence;
    incidence=incidence<.01?.01:(incidence>1.0?1.0:incidence);
    // Projected pixel footprints stretch along the plane at grazing angles.
    return marineReflectionSurfaceMatches(planeDelta,tangentDistance*incidence,eyeDistance,capturePixels);
}
bool marineTranslucentSurfaceVisible(float sceneDepth,float surfaceDepth) {
    if(surfaceDepth<=0.0) return false;
    float tolerance=surfaceDepth*.002;
    if(tolerance<.000002) tolerance=.000002;
    return sceneDepth<=surfaceDepth+tolerance;
}
float marineSpecularWeight(float fresnel,float roughness,float surfaceClass,float strength) {
    if(!marineNeedsSurfaceReflection(surfaceClass)) return 0.0;
    // Roughness broadens the metal/gem lobe, rather than deleting its energy.
    float attenuation=(surfaceClass==2.0 || surfaceClass==8.0)?1.0:1.0-roughness;
    if(surfaceClass==5.0) attenuation*=.25;
    if(surfaceClass==6.0) attenuation*=.28;
    float weight=fresnel*attenuation*strength;
    return weight<0.0?0.0:(weight>1.0?1.0:weight);
}
#endif
