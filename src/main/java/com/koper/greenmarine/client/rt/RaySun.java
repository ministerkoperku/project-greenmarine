package com.koper.greenmarine.client.rt;

import org.joml.Vector4f;

/** Matches the vanilla sky's world-space sun transform. */
public final class RaySun {
    private RaySun() {}
    static int volumetricSteps(com.koper.greenmarine.api.pack.Quality quality,float angle,float rainBrightness,boolean overworld,float amount) {
        // The native shadow light becomes the moon at night. Solstice's shafts only
        // consume sunlight, so marching toward that moon wastes the entire budget.
        if(amount<=0 || sample(angle,rainBrightness,overworld,1).w<=0)return 0;
        return switch(quality) {case LOW->8;case MEDIUM->12;case HIGH->16;case ULTRA->24;};
    }
    public static float ambient(float angle,float rainBrightness,boolean overworld) {
        return overworld?.035f+.415f*Math.clamp((float)Math.cos(angle),0,1)*Math.clamp(rainBrightness,0,1):.12f;
    }
    public static Vector4f sample(float angle,float rainBrightness,boolean overworld,float strength) {
        float elevation=(float)Math.cos(angle);
        float daylight=Math.clamp(elevation/.15f,0,1);
        return new Vector4f(-(float)Math.sin(angle),elevation,0,
            overworld?strength*daylight*Math.clamp(rainBrightness,0,1):0);
    }
    /** Ray-traced shadow light: the sun by day, the opposing moon at a matching low strength by night. */
    public static Vector4f shadowLight(float angle,float rainBrightness,boolean overworld,float strength) {
        float elevation=(float)Math.cos(angle);
        if(elevation>=0)return sample(angle,rainBrightness,overworld,strength);
        float night=Math.clamp(-elevation/.15f,0,1);
        return new Vector4f((float)Math.sin(angle),-elevation,0,
            overworld?strength*MOONLIGHT*night*Math.clamp(rainBrightness,0,1):0);
    }
    /** Solstice moonlight relative to sunlight (solIrradiance). */
    private static final float MOONLIGHT=.06f;
}
