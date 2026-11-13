package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackPassVertexTest {
    private static String manifest(String stage) {
        return """
            {"schema":1,"id":"test:custom_screen","passes":[
            {"name":"custom","%s":"test:screen","stage":"%s","vertex":"test:quad","vertices":6%s}]}
            """.formatted(stage.equals("compute")?"compute":"fragment",stage,stage.equals("compute")?",\"groups\":[1,1,1]":"");
    }
    @Test void customVertexSourceIsRequiredBeforeTransactionalPublication() {
        var catalog=new PackCatalog();
        assertFalse(catalog.reload(Map.of("test:manifest",manifest("scene")),id->id.endsWith("screen.fsh")?"#version 450\nvoid main(){}":null));
        assertTrue(catalog.error().contains("quad.vsh"));
        assertTrue(catalog.reload(Map.of("test:manifest",manifest("scene")),id->id.endsWith("screen.fsh")?"#version 450\nvoid main(){}":id.endsWith("quad.vsh")?"#version 450\nvoid main(){gl_Position=vec4(0);}":null));
        assertTrue(catalog.get("test:custom_screen").orElseThrow().sources().containsKey("vertex|test:quad"));
    }
    @Test void proceduralVertexDeclarationCannotBeSilentlyIgnoredOnComputeOrLightmap() {
        for(String stage:new String[]{"compute","lightmap"})
            assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(JsonParser.parseString(manifest(stage)).getAsJsonObject()));
    }
    @Test void vertexCountCannotChangeLegacyTriangleWithoutACustomVertexProgram() {
        var json=JsonParser.parseString(manifest("scene")).getAsJsonObject();
        json.getAsJsonArray("passes").get(0).getAsJsonObject().remove("vertex");
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
    }
}
