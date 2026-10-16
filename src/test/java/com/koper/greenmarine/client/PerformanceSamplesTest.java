package com.koper.greenmarine.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceSamplesTest {
    @Test void reportsMeasuredMeanAndPercentilesWithoutCountingBadOrMissingSamples() {
        var samples=new PerformanceSamples();
        samples.add(Double.NaN); samples.add(-1);
        assertEquals(0,samples.snapshot().count());
        for(int i=1;i<=100;i++) samples.add(i);
        var result=samples.snapshot();
        assertEquals(100,result.count()); assertEquals(50.5,result.meanMs());
        assertEquals(95,result.p95Ms()); assertEquals(1,result.minMs()); assertEquals(100,result.maxMs());
    }
}
