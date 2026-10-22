#ifndef GREENMARINE_SOLSTICE
#define GREENMARINE_SOLSTICE
#include <greenmarine:surface.glsl>
#include <greenmarine:environment.glsl>
#include <greenmarine:reflection_policy.glsl>
#include <greenmarine:cloud_noise.glsl>
#include <greenmarine:output_quality.glsl>
#define SOL_EXPOSURE 0
#define SOL_SATURATION 1
#define SOL_WAVES 2
#define SOL_REFRACTION 3
#define SOL_ABSORPTION 4
#define SOL_WATER_ALPHA 5
#define SOL_REFLECTION 6
#define SOL_SUN 7
#define SOL_AMBIENT 8
#define SOL_GI 9
#define SOL_RAYS 10
#define SOL_FOG 11
#define SOL_FOG_HEIGHT 12
#define SOL_CLOUDS 13
#define SOL_CLOUD_DETAIL 14
#define SOL_WIND 15
#define SOL_BLOOM 16
#define SOL_THRESHOLD 17
#define SOL_WETNESS 18
#define SOL_PUDDLES 19
#define SOL_EMISSION 20
#define SOL_NIGHT 21
#define SOL_TONE 22
#define SOL_WAVE_DEBUG 23
#define SOL_SURFACE_DEBUG 24
#define SOL_SKY 25
#define SOL_ROUGHNESS 26
#define SOL_SNOW 27
float solLuma(vec3 c) { return dot(c,vec3(.2126,.7152,.0722)); }
vec3 solMaterialFresnel(vec3 albedo,float surfaceClass,float cosine) {
    return vec3(marineFresnel(marineMaterialF0(albedo.r,surfaceClass),cosine),
        marineFresnel(marineMaterialF0(albedo.g,surfaceClass),cosine),
        marineFresnel(marineMaterialF0(albedo.b,surfaceClass),cosine));
}
vec3 solSpecularWeight(vec3 fresnel,float roughness,float surfaceClass) {
    float strength=marineParameter(SOL_REFLECTION);
    return vec3(marineSpecularWeight(fresnel.r,roughness,surfaceClass,strength),
        marineSpecularWeight(fresnel.g,roughness,surfaceClass,strength),
        marineSpecularWeight(fresnel.b,roughness,surfaceClass,strength));
}
vec3 solLinear(vec3 c) { return pow(max(c,vec3(0)),vec3(2.2)); }
vec3 solDisplay(vec3 c) { return pow(max(c,vec3(0)),vec3(1.0/2.2)); }
float solHash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453123); }
float solNoise(vec2 p) {
    vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);
    return mix(mix(solHash(i),solHash(i+vec2(1,0)),f.x),mix(solHash(i+vec2(0,1)),solHash(i+vec2(1)),f.x),f.y);
}
// Octave periods are integer harmonics of the floating-origin period.
float solCloudLayer(vec2 p,int period,float footprint) {
    float detail=clamp(marineParameter(SOL_CLOUD_DETAIL),.1,2.0);
    float result=0.0,total=0.0;
    int frequency=1;
    for(int octave=0;octave<4;octave++) {
        if(octave==2 && Context.y<1.5) break;
        if(octave==3 && Context.y<2.5) break;
        float weight=octave==0?.55:(octave==1?.28:(octave==2?.12:.05))*detail;
        float detailWeight=marineCloudDetailWeight(footprint*float(frequency));
        float noise=.5;
        if(detailWeight>0.0) noise=marineCloudNoise(p.x*float(frequency),p.y*float(frequency),period*frequency);
        result+=mix(.5,noise,detailWeight)*weight;
        total+=weight;frequency*=2;
    }
    return result/max(total,.001);
}
vec3 solOutputDither(vec3 color,vec2 pixel) {
    return clamp(color+vec3(marineOutputDither(int(pixel.x),int(pixel.y))),0.0,1.0);
}
float solDay() { return smoothstep(-.12,.15,SurfaceSun.y); }
vec3 solSunColor() { return mix(vec3(1.0,.56,.28),vec3(1.0,.91,.73),smoothstep(.02,.5,SurfaceSun.y)); }
vec3 solAmbient(vec3 n) {
    float day=solDay();
    vec3 ambient=mix(vec3(.023,.039,.075)*marineParameter(SOL_NIGHT),vec3(.19,.27,.38),day);
    ambient*=mix(.65,1.0,smoothstep(-.5,.7,n.y));
    return ambient*marineParameter(SOL_AMBIENT)*(1.0-Environment.y*.28);
}
vec3 solIrradiance(vec3 n,float skylight,float blocklight,float sunVisibility) {
    vec3 ambient=solAmbient(n);
    float sunlight=max(dot(n,SurfaceSun.xyz),0.0)*SurfaceSun.w*sunVisibility;
    float moonlight=max(dot(n,Moon.xyz),0.0)*Moon.w*.06*marineParameter(SOL_NIGHT);
    return (ambient+solSunColor()*sunlight+vec3(.38,.53,.86)*moonlight)*skylight
        +vec3(1.25,.74,.32)*pow(blocklight,3.0)+vec3(.035)*blocklight;
}
vec3 solIrradiance(vec3 n,float skylight,float blocklight) {return solIrradiance(n,skylight,blocklight,1.0);}
float solPuddle(vec3 p,vec3 n,float skylight) {
    if(Environment.z<=0.0 || marineParameter(SOL_WETNESS)<=0.0 || marineParameter(SOL_PUDDLES)<=0.0
        || n.y<=.72 || skylight<=.65) return 0.0;
    float puddleNoise=solNoise((p.xz+SurfaceOrigin.xz)*.38);
    return smoothstep(.54,.76,puddleNoise)*smoothstep(.72,.99,n.y)*smoothstep(.65,.98,skylight)
        *Environment.z*marineParameter(SOL_WETNESS)*marineParameter(SOL_PUDDLES);
}
vec3 solSky(vec3 direction,bool clouds) {
    vec3 d=normalize(direction);float day=solDay(),up=max(d.y,0.0);
    vec3 horizon=mix(vec3(.016,.022,.049),vec3(.48,.66,.8),day);
    vec3 zenith=mix(vec3(.004,.009,.03),vec3(.055,.22,.48),day);
    vec3 color=mix(horizon,zenith,pow(up,.48));
    float sunset=(1.0-smoothstep(.02,.36,abs(SurfaceSun.y)))*day;
    color+=vec3(.42,.17,.045)*pow(max(dot(d,SurfaceSun.xyz),0.0),6.0)*sunset;
    color=mix(color,solLinear(SkyColor.rgb)*.65,Environment.y*.65);
    float sun=pow(max(dot(d,SurfaceSun.xyz),0.0),1800.0);
    color+=solSunColor()*sun*SurfaceSun.w*5.0;
    float moonDot=dot(d,Moon.xyz),phase=(cos(Celestial.x*6.28318530718/8.0)+1.0)*.5;
    float moonDisc=smoothstep(.99972,.9999,moonDot);
    color+=vec3(.49,.63,.86)*moonDisc*Moon.w*phase*1.8;
    vec2 stars=d.xz/(abs(d.y)+.22)*230.0;
    float star=step(.998,solHash(floor(stars)))*pow(max(0.0,1.0-length(fract(stars)-.5)*2.0),5.0);
    color+=vec3(.62,.72,1)*star*(1.0-day)*smoothstep(0.0,.3,d.y)*(1.0-Environment.y)*marineParameter(SOL_NIGHT);
    if(clouds && d.y>.015 && Dimension.w>.5) {
        float altitude=Environment.x;
        vec2 wind=vec2(EnvironmentTime.x*.018,EnvironmentTime.x*.007)*marineParameter(SOL_WIND);
        float elevation=sqrt(d.y*d.y+.04*.04);
        float lowHeight=max(60.0,240.0-altitude),highHeight=max(100.0,390.0-altitude);
        float angularPixel=max(abs(InverseProjection[0][0])/max(Frame.x,1.0),abs(InverseProjection[1][1])/max(Frame.y,1.0))*2.0;
        vec2 layer=(SurfaceOrigin.xz+d.xz*lowHeight/elevation)*(16.0/4096.0)+wind;
        float cover=marineParameter(SOL_CLOUDS)+Environment.y*.18;
        float footprint=lowHeight*angularPixel*(16.0/4096.0)/(elevation*elevation);
        float low=smoothstep(.67-cover*.36,.83-cover*.36,solCloudLayer(layer,16,footprint));
        vec2 upper=(SurfaceOrigin.xz+d.xz*highHeight/elevation)*(8.0/4096.0)-wind*.36;
        float highFootprint=highHeight*angularPixel*(8.0/4096.0)/(elevation*elevation);
        float high=smoothstep(.58,.8,solCloudLayer(upper+vec2(91,37),8,highFootprint))*.35;
        float opacity=clamp(low+high,0.0,.96)*smoothstep(.015,.09,d.y);
        vec3 cloud=mix(vec3(.034,.045,.075),vec3(.86,.87,.86),day);
        cloud*=1.0-Environment.y*.58;
        cloud+=solSunColor()*pow(max(dot(d,SurfaceSun.xyz),0.0),8.0)*SurfaceSun.w*.26;
        color=mix(color,cloud,opacity);
    }
    color+=vec3(.5,.58,.7)*Climate.z;
    if(Dimension.y>.5) color=solLinear(EnvironmentFogColor.rgb)*.65;
    if(Dimension.z>.5) color=mix(color,vec3(.035,.018,.069),.78);
    return max(color,vec3(0))*marineParameter(SOL_SKY);
}
vec3 solTone(vec3 c) {
    c=max(c*marineParameter(SOL_EXPOSURE),vec3(0));
    vec3 soft=c/(1.0+c);
    vec3 film=clamp((c*(2.51*c+.03))/(c*(2.43*c+.59)+.14),0.0,1.0);
    c=mix(soft,film,marineParameter(SOL_TONE));
    c=mix(vec3(solLuma(c)),c,marineParameter(SOL_SATURATION));
    return solDisplay(clamp(c,0.0,1.0));
}
#endif
