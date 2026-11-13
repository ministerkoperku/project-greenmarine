package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamedSceneTargetsTest {
    private static ShaderPack parse(String targets,String pass) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:mrt\",\"targets\":"+targets+",\"passes\":["+pass+"]}").getAsJsonObject());
    }
    @Test void initializedNamedTargetMayFeedItsOwnFirstWrite() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:feedback","targets":{
                "colortex0":{"format":"rgba16f","scale":1,"clear":[0.25,0,0,1]},
                "colortex1":{"format":"rgba32f","scale":1}},
             "passes":[{"name":"accumulate","fragment":"test:accumulate",
                "inputs":{"PreviousColor":"colortex0"},
                "outputs":{"0":"colortex0","1":"colortex1"}}]}
            """).getAsJsonObject();
        assertDoesNotThrow(()->ShaderPack.parse(json));
    }
    @Test void heavyPackCanDeclareMoreThanEightRasterPasses() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:heavy","passes":[]}
            """).getAsJsonObject();
        for(int i=0;i<20;i++) {
            var pass=new com.google.gson.JsonObject();
            pass.addProperty("name","composite"+i);pass.addProperty("fragment","test:composite"+i);
            json.getAsJsonArray("passes").add(pass);
        }
        assertEquals(20,assertDoesNotThrow(()->ShaderPack.parse(json)).passes().size());
    }
    @Test void rejectsUndeclaredRepeatedAndSparseAttachments() {
        String targets="{\"A\":{\"format\":\"rgba16f\",\"scale\":1}}";
        for(String outputs:new String[]{"{\"0\":\"Missing\"}","{\"0\":\"A\",\"1\":\"A\"}","{\"1\":\"A\"}"})
            assertThrows(IllegalArgumentException.class,()->parse(targets,"{\"name\":\"scene\",\"fragment\":\"test:scene\",\"outputs\":"+outputs+"}"));
    }
    @Test void rejectsAmbiguousTargetSizesAndInvalidUnsignedClears() {
        for(String target:new String[]{"{\"format\":\"rgba16f\",\"size\":[2,2,2]}",
            "{\"format\":\"rgba16f\",\"size\":[2,2],\"scale\":1}",
            "{\"format\":\"r32ui\",\"scale\":1,\"clear\":[-1,0,0,0]}",
            "{\"format\":\"r32ui\",\"scale\":1,\"clear\":[1.5,0,0,0]}"})
            assertThrows(IllegalArgumentException.class,()->parse("{\"A\":"+target+"}","{\"name\":\"scene\",\"fragment\":\"test:scene\"}"));
    }
    @Test void rejectsStorageResourceAliasesAndNamedHistoryAmbiguity() {
        assertThrows(IllegalArgumentException.class,()->parse("{\"A\":{\"scale\":1}}",
            "{\"name\":\"scene\",\"fragment\":\"test:scene\",\"history\":true,\"outputs\":{\"0\":\"A\"}}"));
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:aliases","targets":{"A":{"scale":1}},
             "resources":{"images":{"A":{"format":"rgba16f","size":[2,2,2]}}},
             "passes":[{"name":"scene","fragment":"test:scene"}]}
            """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
    }
}
