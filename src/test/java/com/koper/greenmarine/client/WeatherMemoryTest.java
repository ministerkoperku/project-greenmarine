package com.koper.greenmarine.client;

import com.koper.greenmarine.client.world.WeatherMemory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeatherMemoryTest {
    @Test void wetnessDependsOnTicksAndPersistsAfterRain() {
        var memory=new WeatherMemory();assertEquals(0,memory.sample(0,0,false));
        // A short shower only dampens; wetness keeps building while the rain lasts.
        float shower=memory.sample(20,1,false);assertTrue(shower>0 && shower<.15);
        float wet=shower;for(int tick=40;tick<=420;tick+=20)wet=memory.sample(tick,1,false);
        assertTrue(wet>.75 && wet<1);
        assertEquals(wet,memory.sample(420,1,false));assertEquals(wet,memory.sample(420,0,true));
        assertTrue(memory.sample(440,0,false)>wet*.98);
        memory.reset();assertEquals(0,memory.sample(40,0,false));
    }
    @Test void rejectsNonFiniteWeatherInsteadOfPoisoningHistory() {
        assertThrows(IllegalArgumentException.class,()->new WeatherMemory().sample(0,Float.NaN,false));
    }
}
