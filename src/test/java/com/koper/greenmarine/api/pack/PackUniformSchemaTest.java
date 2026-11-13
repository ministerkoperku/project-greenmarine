package com.koper.greenmarine.api.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackUniformSchemaTest {
    private static JsonObject manifest() {
        return JsonParser.parseString("""
            {"schema":1,"id":"demo:uniforms",
             "resources":{"uniforms":{"CompositeFrame":[
                {"name":"frameTime","type":"float","source":"frameTime"},
                {"name":"projection","type":"mat4","source":"gbufferProjection"}]}},
             "passes":[{"name":"composite","fragment":"demo:composite",
                "uniforms":{"IrisUniforms":"CompositeFrame"}}]}
            """).getAsJsonObject();
    }
    private static ShaderPack parse(JsonObject json) { return ShaderPack.parse(json); }

    @Test void orderedUniformResourcesAreNonemptyPackResources() {
        var pack=assertDoesNotThrow(() -> parse(manifest()));
        assertFalse(pack.resources().isEmpty());
        var block=pack.resources().uniforms().get("CompositeFrame");
        assertEquals(java.util.List.of("frameTime","projection"),block.members().stream().map(PackUniformBlock.Member::name).toList());
        assertEquals(80,block.layout().byteSize());
        assertEquals("gbufferProjection",block.sources().get("projection"));
        assertEquals(java.util.Map.of("IrisUniforms","CompositeFrame"),pack.passes().getFirst().uniforms());
    }

    @Test void theSameShaderBlockCanSelectDifferentResourcesPerPass() {
        var json=manifest();
        json.getAsJsonObject("resources").getAsJsonObject("uniforms").add("DeferredFrame",
            JsonParser.parseString("[{\"name\":\"camera\",\"type\":\"vec3\",\"source\":\"cameraPosition\"}]"));
        json.getAsJsonArray("passes").add(JsonParser.parseString("""
            {"name":"deferred","fragment":"demo:deferred","uniforms":{"IrisUniforms":"DeferredFrame"}}
            """));
        var pack=assertDoesNotThrow(() -> parse(json));
        assertEquals("CompositeFrame",pack.passes().get(0).uniforms().get("IrisUniforms"));
        assertEquals("DeferredFrame",pack.passes().get(1).uniforms().get("IrisUniforms"));
        assertEquals(16,pack.resources().uniforms().get("DeferredFrame").layout().byteSize());
    }

    @Test void uniformBlocksAreAllowedOnComputePasses() {
        var json=manifest();var pass=json.getAsJsonArray("passes").get(0).getAsJsonObject();
        pass.remove("fragment");pass.addProperty("compute","demo:compute");pass.addProperty("stage","compute");pass.addProperty("groups","screen");
        assertDoesNotThrow(() -> parse(json));
    }

    @Test void blockAndPackBudgetsHaveExactBoundaries() {
        var json=manifest();var uniforms=json.getAsJsonObject("resources").getAsJsonObject("uniforms");uniforms.remove("CompositeFrame");
        var block=JsonParser.parseString("[{\"name\":\"values\",\"type\":\"float\",\"arrayLength\":4096,\"source\":\"values\"}]");
        for(int i=0;i<64;i++)uniforms.add("Frame"+i,block.deepCopy());
        json.getAsJsonArray("passes").get(0).getAsJsonObject().getAsJsonObject("uniforms").addProperty("IrisUniforms","Frame0");
        assertDoesNotThrow(() -> parse(json));
        uniforms.add("Overflow",block.deepCopy());
        assertThrows(IllegalArgumentException.class,() -> parse(json));
        uniforms.remove("Overflow");
        uniforms.getAsJsonArray("Frame0").get(0).getAsJsonObject().addProperty("arrayLength",4097);
        assertThrows(IllegalArgumentException.class,() -> parse(json));
    }

    @Test void rejectsMissingResourcesAndLightmapBindings() {
        var missing=manifest();missing.getAsJsonArray("passes").get(0).getAsJsonObject().getAsJsonObject("uniforms").addProperty("IrisUniforms","Missing");
        assertThrows(IllegalArgumentException.class,() -> parse(missing));
        var lightmap=manifest();lightmap.getAsJsonArray("passes").get(0).getAsJsonObject().addProperty("stage","lightmap");
        assertThrows(IllegalArgumentException.class,() -> parse(lightmap));
    }

    @Test void passBindingBudgetRejectsOverflowAndNonstringReferences() {
        var json=manifest();var pass=json.getAsJsonArray("passes").get(0).getAsJsonObject();
        var bindings=new JsonObject();
        for(int i=0;i<64;i++)bindings.addProperty("Block"+i,"CompositeFrame");
        pass.add("uniforms",bindings);
        assertEquals(64,assertDoesNotThrow(() -> parse(json)).passes().getFirst().uniforms().size());
        bindings.addProperty("Overflow","CompositeFrame");
        assertThrows(IllegalArgumentException.class,() -> parse(json));
        bindings.remove("Overflow");bindings.addProperty("Block0",true);
        assertThrows(IllegalArgumentException.class,() -> parse(json));
    }

    @Test void rejectsAliasesAcrossResourcesTargetsPassesAndBindings() {
        for(String replacement:new String[]{"MarineFrame","composite"}) {
            var json=manifest();var uniforms=json.getAsJsonObject("resources").getAsJsonObject("uniforms");
            uniforms.add(replacement,uniforms.remove("CompositeFrame"));
            json.getAsJsonArray("passes").get(0).getAsJsonObject().getAsJsonObject("uniforms").addProperty("IrisUniforms",replacement);
            assertThrows(IllegalArgumentException.class,() -> parse(json),replacement);
        }
        for(String kind:new String[]{"images","buffers"}) {
            var json=manifest();json.getAsJsonObject("resources").add(kind,JsonParser.parseString(kind.equals("images")
                ? "{\"CompositeFrame\":{\"scale\":1}}" : "{\"CompositeFrame\":{\"size\":16}}"));
            assertThrows(IllegalArgumentException.class,() -> parse(json),kind);
        }
        var target=manifest();target.add("targets",JsonParser.parseString("{\"CompositeFrame\":{\"scale\":1}}"));
        assertThrows(IllegalArgumentException.class,() -> parse(target));
        for(String binding:new String[]{"MarineFrame","composite","OtherFrame","Storage","SceneTarget","SharedSampler"}) {
            var json=manifest();var pass=json.getAsJsonArray("passes").get(0).getAsJsonObject();
            json.getAsJsonObject("resources").getAsJsonObject("uniforms").add("OtherFrame",
                JsonParser.parseString("[{\"name\":\"time\",\"type\":\"float\",\"source\":\"frameTime\"}]"));
            json.getAsJsonObject("resources").add("buffers",JsonParser.parseString("{\"Storage\":{\"size\":16}}"));
            json.add("targets",JsonParser.parseString("{\"SceneTarget\":{\"scale\":1}}"));
            pass.add("inputs",JsonParser.parseString("{\"SharedSampler\":\"SceneTarget\"}"));
            pass.add("uniforms",JsonParser.parseString("{\""+binding+"\":\"CompositeFrame\"}"));
            assertThrows(IllegalArgumentException.class,() -> parse(json),binding);
        }
        var sampler=manifest();sampler.add("targets",JsonParser.parseString("{\"SceneTarget\":{\"scale\":1}}"));
        sampler.getAsJsonArray("passes").get(0).getAsJsonObject().add("inputs",JsonParser.parseString("{\"CompositeFrame\":\"SceneTarget\"}"));
        assertThrows(IllegalArgumentException.class,() -> parse(sampler));
    }

    @Test void rejectsUnsupportedLayoutsAndMalformedMemberDeclarations() {
        for(String members:new String[]{"[]",
            "[{\"name\":\"a\",\"type\":\"float\"}]",
            "[{\"name\":\"a\",\"type\":\"double\",\"source\":\"time\"}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":\"\"}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":true}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":\"time\",\"offset\":16}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":\"time\",\"arrayLength\":0}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":\"time\",\"arrayLength\":1.5}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":\"time\",\"arrayLength\":\"2\"}]",
            "[{\"name\":\"a\",\"type\":\"float\",\"source\":\"time\"},{\"name\":\"a\",\"type\":\"int\",\"source\":\"count\"}]"}) {
            var json=manifest();json.getAsJsonObject("resources").getAsJsonObject("uniforms").add("CompositeFrame",JsonParser.parseString(members));
            assertThrows(IllegalArgumentException.class,() -> parse(json),members);
        }
    }
}
