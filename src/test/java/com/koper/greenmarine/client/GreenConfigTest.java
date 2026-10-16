package com.koper.greenmarine.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreenConfigTest {
    @Test void defaultsProduceAVisibleButModerateDiagnosticEffect() {
        var config = GreenConfig.parse(JsonParser.parseString("{}").getAsJsonObject());
        assertTrue(config.enabled());
        assertEquals(1.5f, config.intensity());
        assertEquals(0x33ff99, config.color());
    }

    @Test void rejectsNonFiniteAndExcessiveShaderParameters() {
        for (String json : new String[]{"{\"intensity\":-1}", "{\"intensity\":17}",
                "{\"bloomStrength\":1e100}", "{\"bloomRadius\":0}", "{\"pulseSpeed\":-1}",
                "{\"color\":\"invalid\"}"}) {
            assertThrows(IllegalArgumentException.class,
                () -> GreenConfig.parse(JsonParser.parseString(json).getAsJsonObject()), json);
        }
    }

    @Test void zeroIntensityAndDisabledEffectAreValid() {
        var config = GreenConfig.parse(JsonParser.parseString("{\"enabled\":false,\"intensity\":0}").getAsJsonObject());
        assertFalse(config.enabled());
        assertEquals(0, config.intensity());
    }

    @Test void authorCanControlWorldLightRadius() {
        var config = GreenConfig.parse(JsonParser.parseString("{\"radius\":6}").getAsJsonObject());
        assertEquals(6, config.radius());
        assertThrows(IllegalArgumentException.class,
            () -> GreenConfig.parse(JsonParser.parseString("{\"radius\":0}").getAsJsonObject()));
    }
}
