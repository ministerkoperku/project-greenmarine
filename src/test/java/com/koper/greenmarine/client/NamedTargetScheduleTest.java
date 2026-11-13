package com.koper.greenmarine.client;

import com.google.gson.JsonParser;
import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NamedTargetScheduleTest {
    private ShaderPack pack(String targets,String passes) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:mrt\",\"targets\":"+targets+",\"passes\":"+passes+"}").getAsJsonObject());
    }
    @Test void schedulesTargetSizeAndInitializedFeedbackEvenWithoutASelectedWriter() {
        var pack=pack("{\"A\":{\"format\":\"rgba16f\",\"scale\":0.5},\"B\":{\"format\":\"rgba32f\",\"scale\":0.5}}","""
            [{"name":"writer","fragment":"test:writer","minQuality":"ultra","outputs":{"0":"A","1":"B"}},
             {"name":"present","fragment":"test:present","inputs":{"Previous":"A"}}]
            """);
        var caps=new RenderCapabilities("Vulkan",false,false,false,true);
        var low=assertDoesNotThrow(()->RenderSchedule.scene(pack,Quality.LOW,caps,RayMode.OFF,801,601));
        assertEquals(1,low.size());
        var ultra=assertDoesNotThrow(()->RenderSchedule.scene(pack,Quality.ULTRA,caps,RayMode.OFF,801,601));
        assertEquals(401,ultra.getFirst().width());assertEquals(301,ultra.getFirst().height());
    }
    @Test void rejectsAttachmentsWithDifferentResolvedDimensions() {
        var pack=pack("{\"A\":{\"scale\":0.5},\"B\":{\"scale\":0.25}}",
            "[{\"name\":\"writer\",\"fragment\":\"test:writer\",\"outputs\":{\"0\":\"A\",\"1\":\"B\"}}]");
        assertThrows(IllegalArgumentException.class,()->RenderSchedule.scene(pack,Quality.HIGH,new RenderCapabilities("Vulkan",false,false,false,true),RayMode.OFF,800,600));
    }
    @Test void integerClearPreservesUnsignedBitsRatherThanFloatRepresentation() {
        var spec=new PackTarget(PackResources.Format.RGBA32UI,1,1,0,List.of(4294967295d,2147483648d,1d,0d),false);
        assertArrayEquals(new int[]{-1,Integer.MIN_VALUE,1,0},spec.clearBits());
    }
}
