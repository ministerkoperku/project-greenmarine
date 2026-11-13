package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityLightPolicyTest {
    @Test void nearbyTorchRaisesOnlyBlockLight() {
        // Level 14 torch three blocks away gives block level 11; skylight stays 7.
        assertEquals(0x7000b0,EntityLightPolicy.apply(0x700020,true,9,14));
    }

    @Test void movingAwayDimsContinuouslyAndStopsAtTheSourceRadius() {
        assertEquals(0x7000b8,EntityLightPolicy.apply(0x700000,true,6.25,14));
        assertEquals(0x700010,EntityLightPolicy.apply(0x700000,true,169,14));
        assertEquals(0x700000,EntityLightPolicy.apply(0x700000,true,196,14));
    }

    @Test void disablingEffectsKeepsVanillaLightExactly() {
        assertEquals(0x700020,EntityLightPolicy.apply(0x700020,false,0,15));
    }

    @Test void existingEmissiveLightAndUnrelatedBitsSurvive() {
        assertEquals(0x807000f0,EntityLightPolicy.apply(0x807000f0,true,0,14));
        assertEquals(0x7000c0,EntityLightPolicy.apply(0x7000c0,true,9,14));
    }

    @Test void invalidOrOutOfRangeSourcesCannotCorruptLightCoordinates() {
        assertEquals(0x700020,EntityLightPolicy.apply(0x700020,true,Double.NaN,15));
        assertEquals(0x700020,EntityLightPolicy.apply(0x700020,true,-1,15));
        assertEquals(0x700020,EntityLightPolicy.apply(0x700020,true,0,-1));
        assertEquals(0x7000f0,EntityLightPolicy.apply(0x700020,true,0,100));
    }
}
