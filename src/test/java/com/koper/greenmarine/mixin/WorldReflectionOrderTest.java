package com.koper.greenmarine.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldReflectionOrderTest {
    @Test void materialReplayHookRunsAfterSamplerInitializationAndBeforeMainRenderPass() throws Exception {
        var mixin=read(WorldTransparencyMixin.class);
        var hook=mixin.methods.stream().filter(m->m.name.equals("greenmarine$earlyMaterials")).findFirst()
            .orElseThrow(()->new AssertionError("Surface rays need a replay before terrain color rendering"));
        var injection=hook.visibleAnnotations.stream().filter(a->a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(1,value(injection,"require"));
        var at=(AnnotationNode)((List<?>)value(injection,"at")).getFirst();
        assertEquals("AFTER",((String[])value(at,"shift"))[1]);
        String target=(String)value(at,"target");
        var host=read(net.minecraft.client.renderer.LevelRenderer.class).methods.stream()
            .filter(m->m.name.equals("lambda$addMainPass$0")).findFirst().orElseThrow();
        int sampler=-1,anchor=-1,mainPass=-1,solid=-1,matches=0;
        for(int i=0;i<host.instructions.size();i++) {
            var instruction=host.instructions.get(i);
            if(instruction instanceof FieldInsnNode f && f.name.equals("chunkLayerSampler") && f.getOpcode()==181)sampler=i;
            if(instruction instanceof MethodInsnNode c) {
                if(target.equals("L"+c.owner+";"+c.name+c.desc)){anchor=i;matches++;}
                if(c.name.equals("createRenderPass") && mainPass<0)mainPass=i;
                if(c.name.equals("executeSolid"))solid=i;
            }
        }
        assertEquals(1,matches,"Mixin must anchor to an actual unambiguous host instruction");
        assertTrue(sampler>=0 && sampler<anchor && anchor<mainPass && mainPass<solid,
            "Capture needs the initialized sampler and must finish before opening the opaque pass");
    }
    private static Object value(AnnotationNode a,String key){return a.values.get(a.values.indexOf(key)+1);}
    private static ClassNode read(Class<?> type)throws Exception {
        var result=new ClassNode();
        try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            assertNotNull(stream);new ClassReader(stream).accept(result,0);
        }
        return result;
    }
}
