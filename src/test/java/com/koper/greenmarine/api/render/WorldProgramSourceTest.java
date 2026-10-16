package com.koper.greenmarine.api.render;

import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.ProgramSources;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldProgramSourceTest {
    @Test void publicProgramKeepsDifferentStagesWithTheSameResourceId() {
        var id=Identifier.parse("test:terrain");
        var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("test:program")).withVertexShader(id).withFragmentShader(id).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
        var definition=new ShaderPack("test:world","World",null,List.of(),List.of(),List.of(new PackProgram(PackProgram.Target.TERRAIN,id.toString(),id.toString(),false)));
        var snapshot=new PackCatalog.LoadedPack(definition,"function update(frame) end",Map.of(id.toString(),"fragment source","vertex|"+id,"vertex source"));
        try(var sources=new ProgramSources(ShaderProgram.fromPack(pipeline,snapshot,Map.of()),false)) {
            assertEquals("vertex source",sources.getShader(id,ShaderType.VERTEX));
            assertEquals("fragment source",sources.getShader(id,ShaderType.FRAGMENT));
        }
    }
}
