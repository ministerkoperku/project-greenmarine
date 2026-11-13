package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldProgramTargetsTest {
    private static ShaderPack pack(String targets,String bindings) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:world\",\"passes\":[],\"targets\":"+targets+",\"programs\":[{\"target\":\"terrain\",\"fragment\":\"test:world\","+bindings+"}]}").getAsJsonObject());
    }
    @Test void exactPipelineSelectorsRejectOverlapAndAllowDistinctProgramsWithinFamily() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:selectors","passes":[],"programs":[
            {"target":"terrain","fragment":"test:solid","pipelines":["minecraft:terrain_solid"]},
            {"target":"terrain","fragment":"test:water","pipelines":["minecraft:terrain_translucent"]}]}
            """).getAsJsonObject();
        assertDoesNotThrow(()->ShaderPack.parse(json));
        json.getAsJsonArray("programs").get(1).getAsJsonObject().getAsJsonArray("pipelines").set(0,new com.google.gson.JsonPrimitive("minecraft:terrain_solid"));
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
    }
    @Test void exactPipelineSelectorOverridesFamilyFallbackRegardlessOfDeclarationOrder() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:selectors","passes":[],"programs":[
            {"target":"terrain","fragment":"test:fallback"},
            {"target":"terrain","fragment":"test:solid","pipelines":["minecraft:terrain_solid"]}]}
            """).getAsJsonObject();
        var pack=ShaderPack.parse(json);
        assertEquals("test:solid",pack.worldProgram(PackProgram.Target.TERRAIN,"minecraft:terrain_solid").fragment());
        assertEquals("test:fallback",pack.worldProgram(PackProgram.Target.TERRAIN,"minecraft:terrain_translucent").fragment());
        assertNull(pack.worldProgram(PackProgram.Target.ENTITY,"minecraft:terrain_solid"));
        json.getAsJsonArray("programs").remove(0);
        assertNull(ShaderPack.parse(json).worldProgram(PackProgram.Target.TERRAIN,"minecraft:terrain_translucent"));
    }
    @Test void ownedMainColorMustBeDeclaredFloatingAndHaveExplicitPresentation() {
        for(String change:new String[]{"\"worldColor\":\"Missing\",", "\"worldColor\":\"A\","}) {
            var json=JsonParser.parseString("{\"schema\":1,\"id\":\"test:world\","+change+"\"targets\":{\"A\":{\"scale\":1}},\"passes\":[]}").getAsJsonObject();
            assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
        }
    }
    @Test void ownedBackgroundCannotSilentlyUseAnIncompatibleNamedWorldPass() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:background_mrt","targets":{"A":{"scale":1}},"passes":[],"programs":[
              {"target":"terrain","fragment":"test:terrain","outputs":{"0":"A"}},
              {"target":"background","vertex":"test:background","fragment":"test:background"}]}
            """).getAsJsonObject();
        var error=assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
        assertTrue(error.getMessage().contains("Background"));
        json.getAsJsonArray("programs").remove(0);
        assertDoesNotThrow(()->ShaderPack.parse(json));
    }
    @Test void worldOutputsMustExistAndHaveHostDepthCompatibleSizes() {
        assertThrows(IllegalArgumentException.class,()->pack("{}","\"outputs\":{\"0\":\"Missing\"}"));
        assertThrows(IllegalArgumentException.class,()->pack("{\"A\":{\"scale\":0.5}}","\"outputs\":{\"0\":\"A\"}"));
        assertThrows(IllegalArgumentException.class,()->pack("{\"A\":{\"size\":[32,32]}}","\"outputs\":{\"0\":\"A\"}"));
    }
    @Test void worldInputsAndUniformsCannotSilentlyResolveToMissingResources() {
        assertThrows(IllegalArgumentException.class,()->pack("{}","\"inputs\":{\"Previous\":\"Missing\"}"));
        assertThrows(IllegalArgumentException.class,()->pack("{}","\"uniforms\":{\"IrisFrame\":\"Missing\"}"));
    }
    @Test void worldOutputsRejectSparseLocationsAndDuplicateTargets() {
        for(String outputs:new String[]{"{\"1\":\"A\"}","{\"0\":\"A\",\"1\":\"A\"}"})
            assertThrows(IllegalArgumentException.class,()->pack("{\"A\":{\"scale\":1}}","\"outputs\":"+outputs));
    }
}
