package com.koper.greenmarine.client;

import java.util.Arrays;

/** A bounded recent-sample window; reporting allocates, recording does not. */
public final class PerformanceSamples {
    public record Snapshot(int count,double meanMs,double minMs,double maxMs,double p95Ms) {}
    private final double[] values=new double[256];
    private int count,cursor;
    public void add(double millis) {
        if (!Double.isFinite(millis) || millis<0) return;
        values[cursor]=millis; cursor=(cursor+1)%values.length; count=Math.min(values.length,count+1);
    }
    public Snapshot snapshot() {
        if (count==0) return new Snapshot(0,0,0,0,0);
        var sorted=Arrays.copyOf(values,count); Arrays.sort(sorted);
        double sum=0; for(double value:sorted) sum+=value;
        return new Snapshot(count,sum/count,sorted[0],sorted[count-1],sorted[Math.max(0,(int)Math.ceil(count*0.95)-1)]);
    }
    public void clear() { count=cursor=0; }
}
