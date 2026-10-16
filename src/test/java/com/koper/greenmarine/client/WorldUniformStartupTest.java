package com.koper.greenmarine.client;

import com.koper.greenmarine.client.world.WorldPrograms;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldUniformStartupTest {
    @Test void environmentUsesDefaultsBeforePackInitialization() throws Exception {
        assertNull(ShaderRuntime.active());
        var parameter=WorldPrograms.class.getDeclaredMethod("parameter",String.class,float.class);
        parameter.setAccessible(true);
        assertEquals(1f,parameter.invoke(null,"sunStrength",1f));
    }
}
