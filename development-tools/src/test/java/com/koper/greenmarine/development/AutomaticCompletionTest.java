package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticCompletionTest {
    @Test void suggestsSdkBindingsAndJsonAndLuaWhileTyping() {
        var bindings=List.of(new ShaderCompletions.Choice("MarineFrame","MarineFrame","SDK frame block"),new ShaderCompletions.Choice("greenmarine:frame.glsl","greenmarine:frame.glsl","SDK include"));
        assertTrue(ShaderCompletions.suggest("Mari",4,"shader.fsh",bindings).stream().anyMatch(c->c.label().equals("MarineFrame")));
        String include="#include <greenmarine:fra";
        var choice=ShaderCompletions.suggest(include,include.length(),"shader.fsh",bindings).stream().filter(c->c.label().equals("greenmarine:frame.glsl")).findFirst().orElseThrow();
        assertEquals("#include <greenmarine:frame.glsl",ShaderCompletions.accept(include,include.length(),choice).text());
        assertTrue(ShaderCompletions.suggest("{\"res",5,"main.json",List.of()).stream().anyMatch(c->c.label().equals("resources")));
        assertTrue(ShaderCompletions.suggest("marine.uni",10,"main.lua",List.of()).stream().anyMatch(c->c.label().equals("marine.uniform")));
    }
    @Test void includesMatrixAtomicsAndStorageLanguageHelpers(){
        for(String name:List.of("inverse","atomicAdd","textureSize","memoryBarrierImage","packHalf2x16"))
            assertTrue(ShaderCompletions.suggest(name.substring(0,name.length()-1),name.length()-1,"main.csh",List.of()).stream().anyMatch(c->c.label().equals(name)),name);
    }
    @Test void popupFitsShortNarrowScreensAndStaysAtTheCaretWhenPossible() {
        var popup=CompletionPopup.layout(300,166,320,180,8);
        assertTrue(popup.x()>=4&&popup.y()>=4);assertTrue(popup.x()+popup.width()<=316);assertTrue(popup.y()+popup.height()<=176);
        var room=CompletionPopup.layout(50,40,800,600,3);assertEquals(52,room.x());assertEquals(53,room.y());assertEquals(3,room.rows());
    }
}
