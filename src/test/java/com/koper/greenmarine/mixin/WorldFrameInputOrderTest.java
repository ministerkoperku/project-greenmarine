package com.koper.greenmarine.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class WorldFrameInputOrderTest {
    @Test void refreshesWorldInputsBeforeRaysAndEarlyPackPasses() throws Exception {
        var mixin=new ClassNode();
        try(var stream=GameRendererMixin.class.getResourceAsStream("/com/koper/greenmarine/mixin/GameRendererMixin.class")) {
            assertNotNull(stream);new ClassReader(stream).accept(mixin,0);
        }
        var projection=mixin.methods.stream().filter(method->method.name.equals("greenmarine$projection")).findFirst().orElseThrow();
        var calls=new ArrayList<String>();
        for(var instruction:projection.instructions)if(instruction instanceof MethodInsnNode method)
            calls.add(method.owner+"."+method.name);
        int inputs=calls.indexOf("com/koper/greenmarine/client/world/WorldPrograms.beginFrameInputs");
        int rays=calls.indexOf("com/koper/greenmarine/client/rt/RayWorld.renderFrame");
        int packs=calls.indexOf("com/koper/greenmarine/client/PackRenderer.beginWorldFrame");
        int world=calls.indexOf("com/koper/greenmarine/client/world/WorldPrograms.prepare");
        assertTrue(inputs>=0 && inputs<rays && rays<packs && packs<world,
            "Current camera/environment and unavailable-surface flags must exist before rays and early phases, with world preparation after them");
    }
}
