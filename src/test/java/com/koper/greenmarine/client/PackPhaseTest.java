package com.koper.greenmarine.client;

import com.google.gson.JsonParser;
import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PackPhaseTest {
    private static ShaderPack pack(String passes) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:phases\",\"passes\":["+passes+"]}").getAsJsonObject());
    }
    @Test void schedulesHostPhasesInOrderAndPreservesDeclarationOrderWithinEachPhase() {
        var pack=pack("""
            {"name":"final","fragment":"test:f","phase":"final"},
            {"name":"legacy","fragment":"test:f"},
            {"name":"deferred","fragment":"test:f","phase":"deferred"},
            {"name":"begin_b","fragment":"test:f","phase":"begin"},
            {"name":"prepare","fragment":"test:f","phase":"prepare"},
            {"name":"setup","fragment":"test:f","phase":"setup"},
            {"name":"begin_a","fragment":"test:f","phase":"begin"},
            {"name":"shadowcomp","fragment":"test:f","phase":"shadowcomp"}
            """);
        var schedule=RenderSchedule.scene(pack,Quality.HIGH,new RenderCapabilities("Vulkan",false,false,false,true),RayMode.OFF,80,60);
        assertEquals(List.of("setup","begin_b","begin_a","shadowcomp","prepare","deferred","legacy","final"),
            schedule.stream().map(p->p.resolved().definition().name()).toList());
    }
    @Test void rejectsInputFromLaterHostPhaseEvenWhenDeclaredEarlier() {
        var failure=assertThrows(IllegalArgumentException.class,()->pack("""
            {"name":"late","fragment":"test:f","phase":"final"},
            {"name":"early","fragment":"test:f","phase":"begin","inputs":{"Future":"late"}}
            """));
        assertTrue(failure.getMessage().contains("late"));
    }
    @Test void selectedGraphEqualityDetectsNewSetupWorkWithoutReactingToUnusedCapabilities() {
        var pack=pack("""
            {"name":"setup","stage":"compute","compute":"test:setup","groups":[1,1,1],"phase":"setup"},
            {"name":"compose","fragment":"test:f"}
            """);
        var noCompute=new RenderCapabilities("Vulkan",false,false,false,true,false);
        var unusedCapability=new RenderCapabilities("Vulkan",true,false,false,true,false);
        var compute=new RenderCapabilities("Vulkan",false,false,false,true,true);
        var first=RenderSchedule.scene(pack,Quality.HIGH,noCompute,RayMode.OFF,80,60);
        assertEquals(first,RenderSchedule.scene(pack,Quality.HIGH,unusedCapability,RayMode.OFF,80,60));
        var selected=RenderSchedule.scene(pack,Quality.HIGH,compute,RayMode.OFF,80,60);
        assertNotEquals(first,selected);
        assertEquals(List.of("setup","compose"),selected.stream().map(p->p.resolved().definition().name()).toList());
    }
    @Test void legacyConstructorAndManifestDefaultToPostWorldComposite() {
        var pass=new PackPass("legacy",PackPass.Stage.SCENE,"test:f",null,PackPass.Requirement.NONE,Quality.LOW,1,null);
        assertEquals(PackPass.Phase.COMPOSITE,pass.phase());
        assertEquals(PackPass.Phase.COMPOSITE,pack("{\"name\":\"legacy\",\"fragment\":\"test:f\"}").passes().getFirst().phase());
    }
    @Test void rejectsUnknownPhaseAndWorldPhaseOnLightmap() {
        assertThrows(IllegalArgumentException.class,()->pack("{\"name\":\"a\",\"fragment\":\"test:f\",\"phase\":\"imagined\"}"));
        assertThrows(IllegalArgumentException.class,()->pack("{\"name\":\"a\",\"fragment\":\"test:f\",\"stage\":\"lightmap\",\"phase\":\"prepare\"}"));
    }
}
