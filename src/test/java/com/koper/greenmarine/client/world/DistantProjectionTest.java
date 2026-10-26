package com.koper.greenmarine.client.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistantProjectionTest {
    @Test void reconstructsDistanceForEveryDepthConvention() {
        // Near=10, far=1000: a point at 100 has these independently derived depths.
        check(false,false,10f/11f);
        check(true,false,10f/11f);
        check(false,true,1f/11f);
        check(true,true,1f/11f);
    }

    private void check(boolean zeroToOne,boolean reverse,float depth) {
        float[] clip=DistantProjection.clip(10,1000,zeroToOne,reverse);
        float ndc=zeroToOne?depth:depth*2-1;
        assertEquals(100,clip[1]/(ndc+clip[0]),.001,
            "zeroToOne="+zeroToOne+", reverse="+reverse);
        assertEquals(zeroToOne?1:0,clip[2]);
        assertEquals(reverse?1:0,clip[3]);
        float nearDepth=reverse?1:0,farDepth=reverse?0:1;
        assertEquals(10,clip[1]/((zeroToOne?nearDepth:nearDepth*2-1)+clip[0]),.001);
        assertEquals(1000,clip[1]/((zeroToOne?farDepth:farDepth*2-1)+clip[0]),.02);
    }

    @Test void refusesInvalidClipPlanesBeforeTheyReachTheShader() {
        for(float[] planes:new float[][]{{0,100},{10,10},{100,10},{Float.NaN,100},{10,Float.POSITIVE_INFINITY}})
            assertThrows(IllegalArgumentException.class,()->DistantProjection.clip(planes[0],planes[1],true,true));
    }

    @Test void acceptsDhApiEnumNamesAndRejectsUnknownConventions() {
        float[] reverse=DistantProjection.clip(10,1000,"ZERO_TO_POS_ONE","REVERSE_Z");
        assertEquals(100,reverse[1]/(1f/11f+reverse[0]),.001);
        assertEquals(1,reverse[2]);
        float[] forward=DistantProjection.clip(10,1000,"NEG_ONE_TO_POS_ONE","FORWARD_Z");
        assertEquals(100,forward[1]/(9f/11f+forward[0]),.001);
        assertEquals(0,forward[2]);
        assertThrows(IllegalArgumentException.class,()->DistantProjection.clip(10,1000,"UNKNOWN","REVERSE_Z"));
        assertThrows(IllegalArgumentException.class,()->DistantProjection.clip(10,1000,"ZERO_TO_POS_ONE","UNKNOWN"));
    }
}
