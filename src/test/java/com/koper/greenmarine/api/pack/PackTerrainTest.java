package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackTerrainTest {
    @Test void orderedSelectorsRetainSignedIdsStateFiltersAndDefaultUnmapped() {
        var terrain=PackTerrain.parse(JsonParser.parseString("""
            {"profile":"iris_separate_ao","blocks":{
              "minecraft:oak_log:axis=x":-7,"minecraft:oak_log":12,"minecraft:water:level=0":500}}
            """).getAsJsonObject());
        assertTrue(terrain.enabled());
        assertEquals(-7,terrain.materialId("minecraft:oak_log",Map.of("axis","x")));
        assertEquals(12,terrain.materialId("minecraft:oak_log",Map.of("axis","y")));
        assertEquals(500,terrain.materialId("minecraft:water",Map.of("level","0")));
        assertEquals(-1,terrain.materialId("minecraft:water",Map.of("level","4")));
        assertEquals(-1,terrain.materialId("minecraft:stone",Map.of()));
        assertFalse(PackTerrain.parse(null).enabled());
    }

    @Test void invalidOrAmbiguousCaptureConfigurationFailsBeforePublication() {
        for(String json:List.of("{\"profile\":\"fake\"}","{\"profile\":\"iris_separate_ao\",\"blocks\":{\"minecraft:stone\":1.5}}",
            "{\"profile\":\"iris_separate_ao\",\"blocks\":{\"minecraft:oak_log:axis=\":1}}",
            "{\"profile\":\"iris_separate_ao\",\"blocks\":{\"minecraft:oak_log:axis=x:axis=y\":1}}"))
            assertThrows(IllegalArgumentException.class,()->PackTerrain.parse(JsonParser.parseString(json).getAsJsonObject()),json);
    }

    @Test void manifestCaptureRequiresAnExplicitVanillaTerrainProgram() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:capture","name":"Capture","passes":[],
              "terrain":{"profile":"iris_separate_ao","blocks":{}}}
            """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
        json.add("programs",JsonParser.parseString("""
            [{"target":"terrain","vertex":"test:terrain","fragment":"test:terrain",
              "pipelines":["minecraft:pipeline/solid_terrain"]}]
            """));
        assertTrue(ShaderPack.parse(json).terrain().enabled());
        json.getAsJsonArray("programs").get(0).getAsJsonObject().remove("pipelines");
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
        json.getAsJsonArray("programs").get(0).getAsJsonObject().add("pipelines",JsonParser.parseString("[\"sodium:terrain\"]"));
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
    }
}
