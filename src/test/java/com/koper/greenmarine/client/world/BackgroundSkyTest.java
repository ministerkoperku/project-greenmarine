package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundSkyTest {
    @Test void backgroundDrawOnlyTouchesClearDepthAndUsesActualColorFormat() {
        var program=new PackProgram(PackProgram.Target.BACKGROUND,"test:background","test:background",false);
        var pipeline=BackgroundSky.pipeline(program,GpuFormat.RGBA16_FLOAT);
        assertEquals(CompareOp.EQUAL,pipeline.getDepthStencilState().depthTest());
        assertFalse(pipeline.getDepthStencilState().writeDepth());
        assertEquals(GpuFormat.RGBA16_FLOAT,pipeline.getColorTargetStates().getFirst().format());
        assertFalse(pipeline.isCull());
        assertEquals(PrimitiveTopology.TRIANGLES,pipeline.getPrimitiveTopology());
    }
    @Test void backgroundStageRequiresAnExplicitVertexAndNoRayCaptureFlags() {
        assertThrows(IllegalArgumentException.class,()->new PackProgram(PackProgram.Target.BACKGROUND,null,"test:background",false));
        assertThrows(IllegalArgumentException.class,()->new PackProgram(PackProgram.Target.BACKGROUND,"test:background","test:background",true));
        var json=com.google.gson.JsonParser.parseString("""
            {"schema":1,"id":"test:sky","passes":[],"programs":[
            {"target":"background","vertex":"test:bg","fragment":"test:bg"}]}
            """).getAsJsonObject();
        assertEquals(PackProgram.Target.BACKGROUND,ShaderPack.parse(json).programs().getFirst().target());
    }
}
