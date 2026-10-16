package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RenderScheduleTest {
    @Test void retainsOnlyReferencedSelectedOutputsAcrossInterveningPasses() {
        var pack=ShaderPack.parse(com.google.gson.JsonParser.parseString("""
            {"schema":1,"id":"test:inputs","passes":[
              {"name":"source","fragment":"test:source"},
              {"name":"middle","fragment":"test:middle"},
              {"name":"bloom","fragment":"test:bloom","minQuality":"medium","inputs":{"CachedScene":"source"}},
              {"name":"compose","fragment":"test:compose","inputs":{"CachedScene":"source"}}]}
            """).getAsJsonObject());
        var caps=new RenderCapabilities("Vulkan",false,false,false,true);
        var schedule=RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.OFF,800,600);
        assertEquals(java.util.Set.of("source"),RenderSchedule.retainedOutputs(schedule));
        assertEquals(java.util.Set.of("source"),RenderSchedule.retainedOutputs(RenderSchedule.scene(pack,Quality.LOW,caps,RayMode.OFF,800,600)));
    }
    @Test void lightingHistoryAddsMaterialAndSceneDepthWithoutChangingLegacyLayouts() throws Exception {
        var format=com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT;
        var method=assertDoesNotThrow(()->PackRenderer.class.getDeclaredMethod("pipelineFor",String.class,com.mojang.renderpearl.api.GpuFormat.class,boolean.class,boolean.class,List.class));
        var pipeline=(com.mojang.renderpearl.api.pipeline.RenderPipeline)method.invoke(null,"test:compose",format,true,true,List.of());
        var names=com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream().map(u->u.name()).toList();
        assertTrue(names.containsAll(List.of("HistoryNormalDepth","HistorySampler","HistoryAlbedoMaterial","HistorySceneDepth")));
        var legacy=PackRenderer.pipelineFor("test:compose",format,true);
        var oldNames=com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(legacy.getBindGroupLayouts()).stream().map(u->u.name()).toList();
        assertFalse(oldNames.contains("HistorySceneDepth"));assertFalse(oldNames.contains("HistoryAlbedoMaterial"));
        assertNotEquals(legacy.getLocation(),pipeline.getLocation());
    }
    @Test void namedSamplersHaveIndependentPipelineLayouts() {
        var format=com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT;
        var pipeline=PackRenderer.pipelineFor("test:compose",format,false,List.of("CachedScene"));
        var uniforms=com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        assertTrue(uniforms.stream().anyMatch(u->u.name().equals("CachedScene") && u.type()==com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER));
        assertNotEquals(pipeline.getLocation(),PackRenderer.pipelineFor("test:compose",format).getLocation());
        assertNotEquals(pipeline.getLocation(),PackRenderer.pipelineFor("test:compose",format,false,List.of("OtherScene")).getLocation());
    }
    @Test void solsticeUsesFullResolutionLinearSceneOnEveryQuality() throws Exception {
        try(var input=getClass().getResourceAsStream("/assets/greenmarine/greenmarine/packs/solstice_rt.json")) {
            assertNotNull(input);
            var pack=ShaderPack.parse(com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
            var caps=new RenderCapabilities("Vulkan",false,false,false,true);
            var low=RenderSchedule.scene(pack,Quality.LOW,caps,RayMode.OFF,801,601);
            assertEquals(List.of("solstice_scene","solstice_compose"),low.stream().map(p->p.resolved().definition().name()).toList());
            assertEquals(PackPass.Format.RGBA16F,low.getFirst().resolved().definition().format());
            assertEquals(801,low.getFirst().width()); assertEquals(601,low.getFirst().height());
            assertEquals(java.util.Map.of("SolsticeScene","solstice_scene"),low.getLast().resolved().definition().inputs());
            var ultra=RenderSchedule.scene(pack,Quality.ULTRA,caps,RayMode.OFF,801,601);
            assertEquals(List.of("solstice_scene","solstice_bloom","solstice_compose"),ultra.stream().map(p->p.resolved().definition().name()).toList());
            assertEquals(java.util.Map.of("CachedColorSampler","solstice_scene"),ultra.get(1).resolved().definition().inputs());
            assertEquals(java.util.Set.of("solstice_scene"),RenderSchedule.retainedOutputs(ultra));
        }
    }
    @Test void selectedConsumerRejectsQualitySkippedNamedProducer() {
        var pack=ShaderPack.parse(com.google.gson.JsonParser.parseString("""
            {"schema":1,"id":"test:inputs","passes":[
              {"name":"source","fragment":"test:source","minQuality":"high"},
              {"name":"compose","fragment":"test:compose","inputs":{"CachedScene":"source"}}]}
            """).getAsJsonObject());
        var caps=new RenderCapabilities("Vulkan",false,false,false,true);
        var error=assertThrows(IllegalArgumentException.class,()->RenderSchedule.scene(pack,Quality.LOW,caps,RayMode.OFF,800,600));
        assertTrue(error.getMessage().contains("source"));
        assertEquals(2,RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.OFF,800,600).size());
    }
    @Test void selectedConsumerRejectsCapabilitySkippedNamedProducer() {
        var pack=ShaderPack.parse(com.google.gson.JsonParser.parseString("""
            {"schema":1,"id":"test:inputs","passes":[
              {"name":"source","fragment":"test:source","requires":"hardware_rt"},
              {"name":"compose","fragment":"test:compose","inputs":{"CachedScene":"source"}}]}
            """).getAsJsonObject());
        var caps=new RenderCapabilities("OpenGL",false,false,false,true);
        assertThrows(IllegalArgumentException.class,()->RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.OFF,800,600));
    }
    @Test void resolvesScaledPassesAndSkipsWorkBelowQuality() {
        var passes = List.of(new PackPass("grade", PackPass.Stage.SCENE,"demo:grade",null,PackPass.Requirement.NONE,Quality.LOW,1,null),
            new PackPass("bloom",PackPass.Stage.SCENE,"demo:bloom",null,PackPass.Requirement.NONE,Quality.HIGH,0.5f,null));
        var pack = new ShaderPack("demo:p","P",null,List.of(),passes);
        var caps = new RenderCapabilities("Vulkan",false,false,false,true);
        var low = RenderSchedule.scene(pack,Quality.LOW,caps,RayMode.OFF,801,601);
        assertEquals(1, low.size()); assertEquals(801,low.getFirst().width());
        var high = RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.OFF,801,601);
        assertEquals(2,high.size()); assertEquals(401,high.getLast().width()); assertEquals(301,high.getLast().height());
        assertTrue(RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.OFF,0,0).isEmpty());
    }

    @Test void createsPortablePipelineIdsWithoutVanillaNamespacePrefix() {
        var pipeline = PackRenderer.pipelineFor("demo:core/grade",com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM);
        assertEquals("demo:core/grade",pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT).toString());
    }
    @Test void floatPassPreservesHdrAndConvertsInsteadOfCopyingIncompatibleFormats() {
        var json=com.google.gson.JsonParser.parseString("""
            {"schema":1,"id":"demo:hdr","passes":[{"name":"radiance","fragment":"demo:ray","format":"rgba16f"}]}
            """).getAsJsonObject();
        var pass=ShaderPack.parse(json).passes().getFirst();
        var unorm=com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM;
        var hdr=com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT;
        assertEquals(hdr,RenderSchedule.format(pass,unorm));
        assertFalse(RenderSchedule.canCopy(800,600,hdr,800,600,unorm));
        assertFalse(RenderSchedule.canCopy(400,300,unorm,800,600,unorm));
        assertTrue(RenderSchedule.canCopy(800,600,unorm,800,600,unorm));
        json.getAsJsonArray("passes").get(0).getAsJsonObject().addProperty("format","invalid");
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(json));
    }
}
