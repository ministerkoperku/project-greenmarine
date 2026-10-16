package com.koper.greenmarine.client;

import com.koper.greenmarine.nativebridge.GreenNative;

public final class PulseSampler {
    private PulseSampler() {}
    public static float sample(GreenNative engine,float time,float intensity) {
        if (!Float.isFinite(time) || !Float.isFinite(intensity) || intensity<0 || intensity>16) return 0;
        if (engine != null && engine.available()) return engine.sample(time,intensity);
        return intensity*(0.75f+0.25f*(float)Math.sin(time));
    }
}
