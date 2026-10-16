package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PackPassInputsTest {
    private static ShaderPack parse(String passes) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:inputs\",\"passes\":["+passes+"]}").getAsJsonObject());
    }
    private static final String SOURCE="{\"name\":\"source\",\"fragment\":\"test:source\"}";
    private static String consumer(String inputs) {
        return "{\"name\":\"compose\",\"fragment\":\"test:compose\",\"inputs\":"+inputs+"}";
    }
    @Test void namedInputsUseAnImmutableSnapshotAndKeepOldConstructors() {
        var bindings=new java.util.LinkedHashMap<String,String>();
        bindings.put("CachedScene","source");
        var pass=new PackPass("compose",PackPass.Stage.SCENE,"test:compose",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE,false,bindings);
        bindings.clear();
        assertEquals(java.util.Map.of("CachedScene","source"),pass.inputs());
        assertThrows(UnsupportedOperationException.class,()->pass.inputs().clear());
        assertTrue(new PackPass("old",PackPass.Stage.SCENE,"test:old",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE,true).inputs().isEmpty());
    }
    @Test void namedInputsRemainPartOfThePassDefinition() {
        var named=parse(SOURCE+","+consumer("{\"CachedScene\":\"source\"}"));
        var plain=parse(SOURCE+",{\"name\":\"compose\",\"fragment\":\"test:compose\"}");
        assertNotEquals(plain.passes().getLast(),named.passes().getLast());
    }
    @Test void rejectsUnknownSelfAndForwardReferences() {
        for(String producer:List.of("absent","compose","later")) {
            assertThrows(IllegalArgumentException.class,()->parse(SOURCE+","+consumer("{\"CachedScene\":\""+producer+"\"}")+",{\"name\":\"later\",\"fragment\":\"test:later\"}"));
        }
    }
    @Test void rejectsLightmapProducersAndConsumers() {
        assertThrows(IllegalArgumentException.class,()->parse("{\"name\":\"source\",\"stage\":\"lightmap\",\"fragment\":\"test:source\"},"+consumer("{\"CachedScene\":\"source\"}")));
        assertThrows(IllegalArgumentException.class,()->parse(SOURCE+",{\"name\":\"light\",\"stage\":\"lightmap\",\"fragment\":\"test:light\",\"inputs\":{\"CachedScene\":\"source\"}}"));
    }
    @Test void rejectsInvalidReservedAndExcessiveSamplerBindings() {
        for(String sampler:List.of("9bad","bad-name","gl_Custom","ColorSampler","SceneSampler","DepthSampler","HistorySampler","MarineSurfaceAlbedo","MarineFrame","RayLighting","VoxelMaterials","HistoryNormalDepth","HistoryAlbedoMaterial","HistorySceneDepth")) {
            assertThrows(IllegalArgumentException.class,()->parse(SOURCE+","+consumer("{\""+sampler+"\":\"source\"}")),sampler);
        }
        var bindings=new java.util.ArrayList<String>();
        for(int i=0;i<9;i++)bindings.add("\"Cached"+i+"\":\"source\"");
        assertThrows(IllegalArgumentException.class,()->parse(SOURCE+","+consumer("{"+String.join(",",bindings)+"}")));
        assertThrows(IllegalArgumentException.class,()->parse(SOURCE+","+consumer("{\"Cached\":23}")));
    }
}
