package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackTest {
    private ShaderPack parse(String text) { return ShaderPack.parse(JsonParser.parseString(text).getAsJsonObject()); }

    @Test void acceptsPortableLightmapAndScenePackWithParameters() {
        var pack = parse("""
            {"schema":1,"id":"demo:warm","name":"Warm","script":"demo:scripts/warm.lua",
             "parameters":{"exposure":{"default":1.2,"min":0.1,"max":4}},
             "passes":[{"name":"light","stage":"lightmap","fragment":"demo:core/light"},
                       {"name":"grade","fragment":"demo:core/grade","scale":0.5}]}
            """);
        assertEquals("demo:warm", pack.id());
        assertEquals(2, pack.passes().size());
        assertEquals(PackPass.Stage.LIGHTMAP, pack.passes().getFirst().stage());
        assertEquals(1.2f, pack.parameters().getFirst().defaultValue());
        assertThrows(UnsupportedOperationException.class, () -> pack.passes().clear());
    }

    @Test void materialAddonDeclarationsAreBoundedNamespacedAndImmutable() {
        var pack=parse("{\"schema\":1,\"id\":\"demo:p\",\"passes\":[],\"materialAddons\":[\"demo:greenmarine/materials/robot.json\"]}");
        assertEquals(java.util.List.of("demo:greenmarine/materials/robot.json"),pack.materialAddons());
        assertThrows(UnsupportedOperationException.class,()->pack.materialAddons().clear());
        for(String declaration:new String[]{"[1]","[\"../robot.json\"]","[\"demo:a.json\",\"demo:a.json\"]"})
            assertThrows(IllegalArgumentException.class,()->parse("{\"schema\":1,\"id\":\"demo:p\",\"passes\":[],\"materialAddons\":"+declaration+"}"));
        String many=java.util.stream.IntStream.range(0,17).mapToObj(i->"\"demo:m"+i+".json\"").collect(java.util.stream.Collectors.joining(","));
        assertThrows(IllegalArgumentException.class,()->parse("{\"schema\":1,\"id\":\"demo:p\",\"passes\":[],\"materialAddons\":["+many+"]}"));
    }

    @Test void rejectsBrokenContractsBeforeRendering() {
        for (String value : new String[]{
            "{\"schema\":2,\"id\":\"demo:p\",\"passes\":[]}",
            "{\"schema\":1.5,\"id\":\"demo:p\",\"passes\":[]}",
            "{\"schema\":\"1\",\"id\":\"demo:p\",\"passes\":[]}",
            "{\"schema\":1,\"id\":\"unnamespaced\",\"passes\":[]}",
            "{\"schema\":1,\"id\":\"demo:p\",\"passes\":[{\"name\":\"p\",\"fragment\":\"demo:s\",\"scale\":0.3}]}",
            "{\"schema\":1,\"id\":\"demo:p\",\"passes\":[{\"name\":\"p\",\"fragment\":\"../shader\"}]}",
            "{\"schema\":1,\"id\":\"demo:p\",\"parameters\":{\"gain\":{\"default\":2,\"min\":0,\"max\":1}},\"passes\":[]}",
            "{\"schema\":1,\"id\":\"demo:p\",\"passes\":[{\"name\":\"a\",\"fragment\":\"demo:s\"},{\"name\":\"a\",\"fragment\":\"demo:s\"}]}",
            "{\"schema\":1,\"id\":\"demo:p\",\"passes\":[{\"name\":\"a\",\"stage\":\"lightmap\",\"fragment\":\"demo:s\"},{\"name\":\"b\",\"stage\":\"lightmap\",\"fragment\":\"demo:s\"}]}"
        }) assertThrows(IllegalArgumentException.class, () -> parse(value), value);
    }

    @Test void boundsPassesAndParameters() {
        String passes = java.util.stream.IntStream.range(0, 9).mapToObj(i -> "{\"name\":\"p"+i+"\",\"fragment\":\"demo:s\"}").collect(java.util.stream.Collectors.joining(","));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"schema\":1,\"id\":\"demo:p\",\"passes\":["+passes+"]}"));
        String parameters = java.util.stream.IntStream.range(0, 33).mapToObj(i -> "\"p"+i+"\":{\"default\":1,\"min\":0,\"max\":2}").collect(java.util.stream.Collectors.joining(","));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"schema\":1,\"id\":\"demo:p\",\"parameters\":{"+parameters+"},\"passes\":[]}"));
    }
}
