package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldProgramTest {
    @Test void earlySurfaceTimingWorksForRenamedSolsticeAndExplicitCustomPrograms() {
        String custom=JSON.replace("\"worldRays\":true","\"worldRays\":true,\"surface\":\"test:surface\",\"surfaceBeforeTerrain\":true");
        assertTrue(ShaderPack.parse(com.google.gson.JsonParser.parseString(custom).getAsJsonObject()).programs().getFirst().surfaceBeforeTerrain());
        var legacy=new PackProgram(PackProgram.Target.TERRAIN,null,"test:terrain",true,"test:surface",true,false);
        assertFalse(legacy.surfaceBeforeTerrain());
        String reused=JSON.replace("\"fragment\":\"test:terrain\"","\"fragment\":\"greenmarine:core/solstice_terrain\"")
            .replace("\"worldRays\":true","\"worldRays\":true,\"surface\":\"greenmarine:core/solstice_surface\"");
        var renamed=ShaderPack.parse(com.google.gson.JsonParser.parseString(reused).getAsJsonObject());
        assertEquals("test:world",renamed.id());assertTrue(renamed.programs().getFirst().surfaceBeforeTerrain());
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(com.google.gson.JsonParser.parseString(JSON.replace("\"worldRays\":true","\"worldRays\":true,\"surfaceBeforeTerrain\":true")).getAsJsonObject()));
    }
    @Test void voxelShadowOptInAllowsFallbackAndKeepsLegacyProgramsUnchanged() {
        var caps=new RenderCapabilities("Vulkan",false,false,true,true);
        var legacy=ShaderPack.parse(com.google.gson.JsonParser.parseString(JSON).getAsJsonObject());
        assertEquals(RayMode.OFF,RenderPolicy.rays(RayMode.HARDWARE,Quality.HIGH,caps,legacy).effective());
        var opted=ShaderPack.parse(com.google.gson.JsonParser.parseString(JSON.replace("\"worldRays\":true", "\"worldRays\":true,\"voxelShadows\":true")).getAsJsonObject());
        assertTrue(opted.programs().getFirst().voxelShadows());
        assertEquals(RayMode.VOXEL,RenderPolicy.rays(RayMode.HARDWARE,Quality.HIGH,caps,opted).effective());
        assertEquals(RayMode.OFF,RenderPolicy.rays(RayMode.OFF,Quality.HIGH,caps,opted).effective());
    }
    @Test void snapshotsOptInSurfaceAndRejectsIncompleteReload() {
        String json=JSON.replace("\"worldRays\":true","\"worldRays\":true,\"surface\":\"test:surface\"");
        var resources=new HashMap<>(Map.of("test:shaders/terrain.vsh","vertex","test:shaders/terrain.fsh","fragment",
            "test:shaders/surface.fsh","surface"));
        var catalog=new PackCatalog();assertTrue(catalog.reload(Map.of("world",json),resources::get));
        assertEquals("surface",catalog.get("test:world").orElseThrow().sources().get("test:surface"));
        resources.remove("test:shaders/surface.fsh");
        assertFalse(catalog.reload(Map.of("world",json),resources::get));
        assertEquals("surface",catalog.get("test:world").orElseThrow().sources().get("test:surface"));
    }
    @Test void refusesSurfaceCaptureForUnsupportedFamilies() {
        String json=JSON.replace("\"target\":\"terrain\"","\"target\":\"entity\"")
            .replace("\"worldRays\":true","\"worldRays\":true,\"surface\":\"test:surface\"");
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
    }
    @Test void surfaceOnlyTracingIsExplicitAndRequiresASurfaceProgram() {
        var old=new PackProgram(PackProgram.Target.TERRAIN,null,"test:terrain",true);
        assertFalse(old.surfaceOnly());
        String json=JSON.replace("\"worldRays\":true","\"worldRays\":true,\"surface\":\"test:surface\",\"surfaceOnly\":true");
        assertTrue(ShaderPack.parse(com.google.gson.JsonParser.parseString(json).getAsJsonObject()).programs().getFirst().surfaceOnly());
        assertThrows(IllegalArgumentException.class,()->ShaderPack.parse(com.google.gson.JsonParser.parseString(JSON.replace("\"worldRays\":true","\"worldRays\":true,\"surfaceOnly\":true")).getAsJsonObject()));
    }
    private static final String JSON="""
        {"schema":1,"id":"test:world","passes":[],"programs":[
          {"target":"terrain","vertex":"test:terrain","fragment":"test:terrain","worldRays":true}]}
        """;
    @Test void snapshotsBothWorldShaderStagesAndTheirIncludes() {
        var resources=Map.of("test:shaders/terrain.vsh","#include <test:common.glsl>\nvertex",
            "test:shaders/terrain.fsh","fragment","test:shaders/include/common.glsl","included");
        var catalog=new PackCatalog();assertTrue(catalog.reload(Map.of("world",JSON),resources::get),catalog.error());
        var loaded=catalog.get("test:world").orElseThrow();
        assertEquals("fragment",loaded.sources().get("test:terrain"));
        assertEquals("#include <test:common.glsl>\nvertex",loaded.sources().get("vertex|test:terrain"));
        assertEquals("included",loaded.sources().get("include|test:common.glsl"));
    }
    @Test void worldRayProgramsSelectHardwareAndReportRasterFallbackTruthfully() {
        var pack=ShaderPack.parse(com.google.gson.JsonParser.parseString(JSON).getAsJsonObject());
        var vk=new RenderCapabilities("Vulkan",true,true,true,true);
        assertEquals(RayMode.HARDWARE,RenderPolicy.rays(RayMode.HARDWARE,Quality.ULTRA,vk,pack).effective());
        var gl=new RenderCapabilities("OpenGL",false,false,true,false);
        var fallback=RenderPolicy.rays(RayMode.HARDWARE,Quality.ULTRA,gl,pack);
        assertEquals(RayMode.OFF,fallback.effective());assertTrue(fallback.reason().contains("raster"));
    }
    @Test void rejectsMissingWorldStageWithoutReplacingGoodCatalog() {
        var catalog=new PackCatalog();assertTrue(catalog.reload(Map.of("p","{\"schema\":1,\"id\":\"test:good\",\"passes\":[]}"),id->null));
        assertFalse(catalog.reload(Map.of("world",JSON),id->null));
        assertTrue(catalog.get("test:good").isPresent());
    }
}
