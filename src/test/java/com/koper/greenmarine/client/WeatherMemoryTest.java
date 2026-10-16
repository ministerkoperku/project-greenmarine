package com.koper.greenmarine.client;

import com.koper.greenmarine.client.world.WeatherMemory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeatherMemoryTest {
    @Test void wetnessDependsOnTicksAndPersistsAfterRain() {
        var memory=new WeatherMemory();assertEquals(0,memory.sample(0,0,false));
        float wet=memory.sample(20,1,false);assertTrue(wet>.4 && wet<1);
        assertEquals(wet,memory.sample(20,1,false));assertEquals(wet,memory.sample(20,0,true));
        assertTrue(memory.sample(40,0,false)>wet*.9);
        memory.reset();assertEquals(0,memory.sample(40,0,false));
    }
    @Test void rejectsNonFiniteWeatherInsteadOfPoisoningHistory() {
        assertThrows(IllegalArgumentException.class,()->new WeatherMemory().sample(0,Float.NaN,false));
    }
}
