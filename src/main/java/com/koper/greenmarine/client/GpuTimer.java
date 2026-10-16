package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.*;

public final class GpuTimer implements AutoCloseable {
    private final GpuQueryPool pool;
    private final boolean[] pending=new boolean[8];
    private final double period;
    private final PerformanceSamples samples=new PerformanceSamples();
    private int cursor;
    private boolean disabled;
    public GpuTimer() {
        var device=RenderSystem.getDevice();
        period=device.getDeviceInfo().timestampPeriod();
        pool=period>0?device.createTimestampQueryPool(16):null;
    }
    public int begin(CommandEncoder encoder) {
        if (disabled || pool==null) return -1;
        try {
            for(int i=0;i<pending.length;i++) if(pending[i]) {
                var values=pool.getValues(i*2,2);
                if(values[0].isPresent() && values[1].isPresent()) {
                    long elapsed=values[1].getAsLong()-values[0].getAsLong();
                    if(elapsed>=0) samples.add(elapsed*period/1_000_000.0);
                    pending[i]=false;
                }
            }
            for(int i=0;i<pending.length;i++) {
                int slot=(cursor+i)%pending.length;
                if(!pending[slot]) { encoder.writeTimestamp(pool,slot*2); cursor=(slot+1)%pending.length; return slot; }
            }
        } catch(RuntimeException exception) { disabled=true; GreenMarine.LOGGER.warn("GPU profiling unavailable: {}",exception.getMessage()); }
        return -1;
    }
    public void end(CommandEncoder encoder,int slot) {
        if(slot<0 || disabled) return;
        try { encoder.writeTimestamp(pool,slot*2+1); pending[slot]=true; }
        catch(RuntimeException exception) { disabled=true; GreenMarine.LOGGER.warn("GPU profiling disabled: {}",exception.getMessage()); }
    }
    public PerformanceSamples.Snapshot snapshot() { return samples.snapshot(); }
    @Override public void close() { if(pool!=null) pool.close(); disabled=true; }
}
