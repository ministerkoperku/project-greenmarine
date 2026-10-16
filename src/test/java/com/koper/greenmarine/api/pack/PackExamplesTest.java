package com.koper.greenmarine.api.pack;

import com.koper.greenmarine.api.LightRegistry;
import com.koper.greenmarine.script.PackScript;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackExamplesTest {
    private String resource(String id) {
        int colon=id.indexOf(':');
        try (var stream=getClass().getResourceAsStream("/assets/"+id.substring(0,colon)+"/"+id.substring(colon+1))) {
            return stream == null ? null : new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new RuntimeException(error); }
    }
    @Test void shippedPortableAndAdvancedPacksResolveAndRunTheirScripts() {
        var portable=resource("greenmarine:greenmarine/packs/portable.json");
        var advanced=resource("greenmarine:greenmarine/packs/advanced.json");
        assertNotNull(portable); assertNotNull(advanced);
        var catalog=new PackCatalog();
        assertTrue(catalog.reload(Map.of("p",portable,"a",advanced),this::resource,id -> {
            int colon=id.indexOf(':');
            try(var stream=getClass().getResourceAsStream("/assets/"+id.substring(0,colon)+"/"+id.substring(colon+1))) {
                return stream==null?null:stream.readAllBytes();
            } catch(java.io.IOException error) { throw new RuntimeException(error); }
        }),catalog.error());
        for (var loaded:catalog.packs()) try(var script=new PackScript(loaded.definition(),loaded.script(),new LightRegistry())) {
            script.tick(new PackScript.Frame(10,0.5,0,64,0,new RenderCapabilities("OpenGL",false,false,false,false),Quality.LOW,RayMode.OFF));
            assertFalse(script.failed(),script.error()); assertTrue(script.value("exposure")>0);
        }
    }
    @Test void radiancePackHasAHdrWorldStageAndKeepsPortableQualityFallbacks() {
        var source=resource("greenmarine:greenmarine/packs/radiance.json");assertNotNull(source);
        var catalog=new PackCatalog();assertTrue(catalog.reload(Map.of("r",source),this::resource),catalog.error());
        var pack=catalog.get("greenmarine:radiance").orElseThrow().definition();
        var caps=new RenderCapabilities("Vulkan",false,false,true,true);
        var low=com.koper.greenmarine.client.RenderSchedule.scene(pack,Quality.LOW,caps,RayMode.OFF,800,600);
        assertEquals(1,low.size());assertEquals(800,low.getFirst().width());
        var high=com.koper.greenmarine.client.RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.VOXEL,800,600);
        assertEquals(2,high.size());assertEquals(400,high.getFirst().width());
        assertEquals(PackPass.Format.RGBA16F,high.getFirst().resolved().definition().format());
        assertFalse(high.getFirst().resolved().fallback());
        var off=com.koper.greenmarine.client.RenderSchedule.scene(pack,Quality.HIGH,caps,RayMode.OFF,800,600);
        assertTrue(off.getFirst().resolved().fallback());
    }
    @Test void auroraExamplePreservesParameterOrderAndHasAnExplicitHardwareFallback() {
        var catalog=new PackCatalog();
        assertTrue(catalog.reload(Map.of("aurora",resource("greenmarine:greenmarine/packs/aurora_rt.json")),this::resource),catalog.error());
        var loaded=catalog.get("greenmarine:aurora_rt").orElseThrow();
        assertEquals(java.util.List.of("exposure","sunStrength","giStrength","blend","diagnostic"),
            loaded.definition().parameters().stream().map(PackParameter::name).toList());
        var pass=loaded.definition().passes().getFirst();
        assertEquals(PackPass.Requirement.HARDWARE_RT,pass.requires());
        var gl=RenderPolicy.pass(pass,Quality.ULTRA,new RenderCapabilities("OpenGL",false,false,true,false),RayMode.VOXEL).orElseThrow();
        assertTrue(gl.fallback());assertEquals("greenmarine:core/aurora_fallback",gl.fragment());
        try(var script=new PackScript(loaded.definition(),loaded.script(),new LightRegistry())) {
            script.tick(new PackScript.Frame(0,0,0,0,0,new RenderCapabilities("Vulkan",true,true,true,true),Quality.ULTRA,RayMode.HARDWARE));
            assertFalse(script.failed(),script.error());
        }
    }
}
