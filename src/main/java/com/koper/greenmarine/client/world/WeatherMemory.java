package com.koper.greenmarine.client.world;

/** Wetness builds up over the length of a rain and dries over a few minutes; paused frames leave it unchanged. */
public final class WeatherMemory {
    private long tick=Long.MIN_VALUE;
    private float wetness;
    public float sample(long currentTick,float rain,boolean paused) {
        if(!Float.isFinite(rain))throw new IllegalArgumentException("Non-finite rainfall");
        rain=Math.clamp(rain,0,1);
        if(tick==Long.MIN_VALUE) {tick=currentTick;wetness=rain;return wetness;}
        if(!paused && currentTick!=tick) {
            long elapsed=Math.clamp(currentTick-tick,0,200);
            // ~50 s of rain to soak, half dried after ~90 s, gone after a few minutes.
            float rate=rain>wetness?.004f:.0004f;
            wetness+=(rain-wetness)*(1-(float)Math.pow(1-rate,elapsed));tick=currentTick;
        }
        return wetness;
    }
    public void reset() {tick=Long.MIN_VALUE;wetness=0;}
}
