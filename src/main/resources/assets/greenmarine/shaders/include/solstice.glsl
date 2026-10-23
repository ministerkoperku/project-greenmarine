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
#define SOL_WEATHER 23
#define SOL_SURFACE_DEBUG 24
#define SOL_SKY 25
#define SOL_ROUGHNESS 26
#define SOL_SNOW 27
#define SOL_VOLUMETRIC 28
#define SOL_OCCLUSION 29
#define SOL_SPECULAR 30
#define SOL_RELIEF 31
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
// Single-scattering atmosphere. Zenith optical depths of air (Rayleigh, wavelength dependent)
// and haze (Mie, grey, thickened by rain); air mass grows towards the horizon, so a low sun
// and everything it lights turn orange and red on their own.
const vec3 SOL_RAYLEIGH=vec3(.058,.135,.331);
float solMoodHash(float n) { return fract(sin(n*12.9898)*43758.5453); }
// Sky mood: 0 clear .. 1 overcast. Weather 0 (auto) gives each in-game day its own mood and
// drifts into the next one over the day; 1..4 pin clear, hazy, cloudy and overcast.
float solMood() {
    float mode=floor(marineParameter(SOL_WEATHER)+.5);
    if(mode>=1.0) return (min(mode,4.0)-1.0)/3.0;
    float day=Celestial.z+EnvironmentTime.y;
    float mood=mix(solMoodHash(floor(day)),solMoodHash(floor(day)+1.0),smoothstep(0.0,1.0,fract(day)));
    return mood*mood*.95;
}
float solHaze() { return .03*(1.0+Environment.y*4.0)*(1.0+solMood()*2.5); }
float solAirMass(float cosine) {
    float c=max(cosine,-.02)+.02;
    return 1.0/(c+.025*exp(-11.0*c));
}
vec3 solTransmittance(float cosine,float massScale) {
    return exp(-(SOL_RAYLEIGH+vec3(solHaze()))*solAirMass(cosine)*massScale);
}
// Direct sunlight colour and strength relative to a clear noon sky.
vec3 solSunColor() { return solTransmittance(SurfaceSun.y,1.0)*1.55*smoothstep(-.04,.02,SurfaceSun.y)*(1.0-solMood()*.55); }
// Daylight sky radiance along d, without sun disc, stars or clouds.
vec3 solAtmosphere(vec3 d) {
    float mu=dot(d,SurfaceSun.xyz),up=max(d.y,0.0);
    vec3 haze=vec3(solHaze()),extinction=SOL_RAYLEIGH+haze;
    // Light scattered high above, or away from the sun, crossed less air than the sun's own
    // path: the solar side glows orange at sunset while the opposite sky stays blue-pink.
    vec3 sunLight=solTransmittance(SurfaceSun.y,mix(.25,1.0,smoothstep(-.2,.8,mu))*mix(1.0,.45,up));
    float g=.82,mie=(1.0-g*g)/(12.5663706*pow(1.0+g*g-2.0*g*mu,1.5));
    float rayleigh=.0596831*(1.0+mu*mu);
    vec3 scatter=(SOL_RAYLEIGH*rayleigh+haze*min(mie,2.0)*.6)/extinction*12.5663706;
    // Half the geometric air mass along the view keeps the horizon pale blue instead of white.
    vec3 inscatter=sunLight*scatter*(1.0-exp(-extinction*solAirMass(up)*.45));
    // Twilight keeps a faint glow for a while after sunset.
    return inscatter*1.5*smoothstep(-.14,.04,SurfaceSun.y);
}
vec3 solAmbient(vec3 n) {
    float day=solDay();
    vec3 sky=solAtmosphere(vec3(0,1,0))*.65+solAtmosphere(normalize(vec3(-SurfaceSun.x,.35,-SurfaceSun.z)))*.35;
    vec3 ambient=vec3(.010,.016,.032)*marineParameter(SOL_NIGHT)*(1.0-day)+sky*1.1;
    ambient*=mix(.65,1.0,smoothstep(-.5,.7,n.y));
    return ambient*marineParameter(SOL_AMBIENT)*(1.0-Environment.y*.28);
}
// Occlusion darkens sky ambient fully and block light partially; direct light keeps its shadow ray.
vec3 solIrradiance(vec3 n,float skylight,float blocklight,float sunVisibility,float occlusion) {
    vec3 ambient=solAmbient(n)*occlusion;
    float sunlight=max(dot(n,SurfaceSun.xyz),0.0)*SurfaceSun.w*sunVisibility;
    // Hardware rays trace the moon at night, so its visibility shadows moonlight too.
    float moonlight=max(dot(n,Moon.xyz),0.0)*Moon.w*.06*marineParameter(SOL_NIGHT)*sunVisibility;
    return (ambient+solSunColor()*sunlight+vec3(.38,.53,.86)*moonlight)*skylight
        +(vec3(1.25,.74,.32)*pow(blocklight,3.0)+vec3(.035)*blocklight)*mix(1.0,occlusion,.6);
}
vec3 solIrradiance(vec3 n,float skylight,float blocklight,float sunVisibility) {return solIrradiance(n,skylight,blocklight,sunVisibility,1.0);}
vec3 solIrradiance(vec3 n,float skylight,float blocklight) {return solIrradiance(n,skylight,blocklight,1.0);}
// GGX sun highlight with Schlick Fresnel; f0 is per channel so metals tint it.
vec3 solSunSpecular(vec3 n,vec3 view,float roughness,vec3 f0,float visibility) {
    float nl=dot(n,SurfaceSun.xyz);
    if(nl<=0.0 || visibility<=0.0 || SurfaceSun.w<=0.0) return vec3(0);
    vec3 h=normalize(view+SurfaceSun.xyz);
    float a=max(roughness*roughness,.003),a2=a*a;
    float nh=max(dot(n,h),0.0),d=nh*nh*(a2-1.0)+1.0;
    float distribution=a2/(3.14159265*d*d);
    vec3 fresnel=f0+(1.0-f0)*pow(1.0-max(dot(view,h),0.0),5.0);
    float nv=max(dot(n,view),.02),k=a*.5;
    float geometry=nl/(nl*(1.0-k)+k)*nv/(nv*(1.0-k)+k);
    return solSunColor()*SurfaceSun.w*visibility*min(distribution*geometry/(4.0*nv),16.0)*fresnel;
}
float solPuddle(vec3 p,vec3 n,float skylight) {
    if(Environment.z<=0.0 || marineParameter(SOL_WETNESS)<=0.0 || marineParameter(SOL_PUDDLES)<=0.0
        || n.y<=.72 || skylight<=.65) return 0.0;
    // Puddles start in the noise hollows and spread as wetness accumulates, then shrink as it dries.
    float puddleNoise=solNoise((p.xz+SurfaceOrigin.xz)*.38)*.7+solNoise((p.xz+SurfaceOrigin.xz)*1.7)*.3;
    float wetness=Environment.z*marineParameter(SOL_WETNESS);
    return smoothstep(.74-.28*wetness,.8-.22*wetness,puddleNoise)*smoothstep(.72,.99,n.y)*smoothstep(.65,.98,skylight)
        *smoothstep(.05,.35,wetness)*marineParameter(SOL_PUDDLES);
}
vec3 solSky(vec3 direction,bool clouds) {
    vec3 d=normalize(direction);float day=solDay(),up=max(d.y,0.0);
    vec3 night=mix(vec3(.006,.009,.020),vec3(.0015,.003,.010),pow(up,.48))*marineParameter(SOL_NIGHT);
    vec3 color=solAtmosphere(vec3(d.x,max(d.y,.0),d.z))+night*(1.0-day);
    // Overcast skies lose their blue towards a flat grey of similar brightness.
    color=mix(color,vec3(solLuma(color))*vec3(.95,.97,1.0)*.85,smoothstep(.55,1.0,solMood())*.8);
    color=mix(color,solLinear(SkyColor.rgb)*.65*max(day,.08),Environment.y*.65);
    // Sun disc about 1.5 degrees across, limb darkened; clouds below still cover it.
    float sunDot=dot(d,SurfaceSun.xyz);
    float disc=smoothstep(.99955,.99975,sunDot)*(.6+.4*smoothstep(.99955,1.0,sunDot));
    color+=solSunColor()*disc*SurfaceSun.w*40.0*smoothstep(-.02,.02,d.y);
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
        float cover=marineParameter(SOL_CLOUDS)+Environment.y*.18+(solMood()-.3)*.75;
        float footprint=lowHeight*angularPixel*(16.0/4096.0)/(elevation*elevation);
        float low=smoothstep(.67-cover*.36,.83-cover*.36,solCloudLayer(layer,16,footprint));
        vec2 upper=(SurfaceOrigin.xz+d.xz*highHeight/elevation)*(8.0/4096.0)-wind*.36;
        float highFootprint=highHeight*angularPixel*(8.0/4096.0)/(elevation*elevation);
        float high=smoothstep(.58,.8,solCloudLayer(upper+vec2(91,37),8,highFootprint))*.35;
        float opacity=clamp(low+high,0.0,.96)*smoothstep(.015,.09,d.y);
        // Lit by the (possibly red) sun plus sky light from above.
        vec3 cloud=solSunColor()*.42+solAtmosphere(vec3(0,1,0))*.9+vec3(.012,.016,.028)*marineParameter(SOL_NIGHT)*(1.0-day);
        cloud*=1.0-Environment.y*.58;
        cloud+=solSunColor()*pow(max(dot(d,SurfaceSun.xyz),0.0),8.0)*SurfaceSun.w*.35;
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
