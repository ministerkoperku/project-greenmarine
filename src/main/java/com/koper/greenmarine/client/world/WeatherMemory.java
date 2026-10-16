package com.koper.greenmarine.client.world;

/** Wet surfaces dry gradually, with paused frames leaving weather memory unchanged. */
public final class WeatherMemory {
    private long tick=Long.MIN_VALUE;
    private float wetness;
    public float sample(long currentTick,float rain,boolean paused) {
        if(!Float.isFinite(rain))throw new IllegalArgumentException("Non-finite rainfall");
        rain=Math.clamp(rain,0,1);
        if(tick==Long.MIN_VALUE) {tick=currentTick;wetness=rain;return wetness;}
        if(!paused && currentTick!=tick) {
            long elapsed=Math.clamp(currentTick-tick,0,200);
            float rate=rain>wetness?.04f:.002f;
            wetness+=(rain-wetness)*(1-(float)Math.pow(1-rate,elapsed));tick=currentTick;
        }
        return wetness;
    }
    public void reset() {tick=Long.MIN_VALUE;wetness=0;}
}
