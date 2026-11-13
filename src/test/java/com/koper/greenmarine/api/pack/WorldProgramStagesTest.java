package com.koper.greenmarine.api.pack;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldProgramStagesTest {
    private static JsonObject manifest(String stages) {
        return JsonParser.parseString("""
            {"schema":1,"id":"test:stages","passes":[],"programs":[
            {"target":"terrain","vertex":"test:world","fragment":"test:world",%s}]}
            """.formatted(stages)).getAsJsonObject();
    }
    @Test void intermediateSourcesAreRequiredAndSnapshottedBeforeCatalogPublication() {
        var json=manifest("\"geometry\":\"test:world\"");
        var catalog=new PackCatalog();
        var resources=new HashMap<String,String>();
        resources.put("test:shaders/world.vsh","vertex");resources.put("test:shaders/world.fsh","fragment");
        assertFalse(catalog.reload(Map.of("stages",json.toString()),resources::get),"Missing geometry must not publish a partial program");
        resources.put("test:shaders/world.gsh","geometry");
        assertTrue(catalog.reload(Map.of("stages",json.toString()),resources::get),catalog.error());
        var loaded=catalog.get("test:stages").orElseThrow();
        assertEquals("geometry",loaded.sources().get("geometry|test:world"));
        resources.remove("test:shaders/world.gsh");
        assertFalse(catalog.reload(Map.of("stages",json.toString()),resources::get));
        assertSame(loaded,catalog.get("test:stages").orElseThrow());
        var missing=new HashMap<>(loaded.sources());missing.remove("geometry|test:world");
        assertThrows(IllegalArgumentException.class,()->new PackCatalog().register(new PackCatalog.LoadedPack(loaded.definition(),loaded.script(),missing)));
    }
    @Test void tessellationRequiresAnExplicitIntegralInputPatchAndBothStages() {
        for(String stages:List.of(
            "\"tessControl\":\"test:world\"",
            "\"tessEvaluation\":\"test:world\"",
            "\"patchVertices\":3",
            "\"tessControl\":\"test:world\",\"tessEvaluation\":\"test:world\"",
            "\"tessControl\":\"test:world\",\"tessEvaluation\":\"test:world\",\"patchVertices\":3.5",
            "\"tessControl\":\"test:world\",\"tessEvaluation\":\"test:world\",\"patchVertices\":33"))
            assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(manifest(stages)),stages);
        assertDoesNotThrow(()->ShaderPack.parse(manifest("\"tessControl\":\"test:world\",\"tessEvaluation\":\"test:world\",\"patchVertices\":3")));
    }
    @Test void intermediateStagesCannotBeIgnoredByBackgroundOrSurfaceReplay() {
        var json=manifest("\"geometry\":\"test:world\"");
        var program=json.getAsJsonArray("programs").get(0).getAsJsonObject();
        program.addProperty("target","background");
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
        program.addProperty("target","terrain");program.addProperty("surface","test:surface");
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
    }
}
