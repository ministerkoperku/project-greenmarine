package com.koper.greenmarine.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PulseSamplerTest {
    @Test void portableFallbackMatchesNativePulseContractWithoutLoadingANativeLibrary() {
        assertEquals(1.5f,PulseSampler.sample(null,0,2));
        assertEquals(2,PulseSampler.sample(null,(float)(Math.PI/2),2),0.0001);
        assertEquals(0,PulseSampler.sample(null,Float.NaN,1));
        assertEquals(0,PulseSampler.sample(null,0,17));
    }
}
