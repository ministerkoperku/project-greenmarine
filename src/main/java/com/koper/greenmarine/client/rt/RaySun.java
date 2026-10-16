package com.koper.greenmarine.client.rt;

import org.joml.Vector4f;

/** Matches the vanilla sky's world-space sun transform. */
public final class RaySun {
    private RaySun() {}
    public static float ambient(float angle,float rainBrightness,boolean overworld) {
        return overworld?.035f+.415f*Math.clamp((float)Math.cos(angle),0,1)*Math.clamp(rainBrightness,0,1):.12f;
    }
    public static Vector4f sample(float angle,float rainBrightness,boolean overworld,float strength) {
        float elevation=(float)Math.cos(angle);
        float daylight=Math.clamp(elevation/.15f,0,1);
        return new Vector4f(-(float)Math.sin(angle),elevation,0,
            overworld?strength*daylight*Math.clamp(rainBrightness,0,1):0);
    }
}
