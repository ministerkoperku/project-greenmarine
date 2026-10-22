package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.GreenMarineClient;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldDepthCompatibilityTest {
    @Test void depthHookTargetsTheActualHostCallAndLastArgument() throws Exception {
        var mixin=readClass(GameRendererMixin.class);
        var hook=mixin.methods.stream().filter(m->m.name.equals("greenmarine$consistentDepth")).findFirst().orElseThrow();
        var annotation=hook.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")).findFirst().orElseThrow();
        assertEquals(6,value(annotation,"index"));assertEquals(1,value(annotation,"require"));
        assertEquals(java.util.List.of("renderLevel"),value(annotation,"method"));
        var at=(AnnotationNode)((java.util.List<?>)value(annotation,"at")).getFirst();
        String target=(String)value(at,"target");
        var host=readClass(net.minecraft.client.renderer.GameRenderer.class);
        long matches=host.methods.stream().filter(m->m.name.equals("renderLevel"))
            .flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
            .filter(i->i instanceof MethodInsnNode call && target.equals("L"+call.owner+";"+call.name+call.desc)).count();
        assertEquals(1,matches,"The consistent-depth argument must be changed before LevelRenderer constructs its frame graph");
    }
    private static Object value(AnnotationNode annotation,String key) {
        return annotation.values.get(annotation.values.indexOf(key)+1);
    }
    private static ClassNode readClass(Class<?> type) throws Exception {
        var node=new ClassNode();
        try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            assertNotNull(input);new ClassReader(input).accept(node,0);
        }
        return node;
    }

    @Test void connectorOverlayCannotDiscardDepthNeededByEnabledEffects() throws Exception {
        Method hook=GameRendererMixin.class.getDeclaredMethod("greenmarine$consistentDepth",boolean.class);
        hook.setAccessible(true);
        var mixin=new GameRendererMixin() {};
        boolean previous=GreenMarineClient.enabled();
        try {
            if(!GreenMarineClient.enabled()) GreenMarineClient.toggleEffect();
            assertTrue((boolean)hook.invoke(mixin,false),"No vanilla post effects: GreenMarine still needs world depth for gizmo frames");
            assertTrue((boolean)hook.invoke(mixin,true),"Keep the host depth requirement");
            GreenMarineClient.toggleEffect();
            assertFalse((boolean)hook.invoke(mixin,false),"Disabled effects keep vanilla behavior");
            assertTrue((boolean)hook.invoke(mixin,true),"Disabling GreenMarine must not disable another effect's requirement");
            GreenMarineClient.toggleEffect();
            assertTrue((boolean)hook.invoke(mixin,false),"Re-enabling must request consistent depth again");
        } finally {
            if(GreenMarineClient.enabled()!=previous) GreenMarineClient.toggleEffect();
        }
    }
}
