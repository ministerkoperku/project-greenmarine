package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackDepthInputsTest {
    private static ShaderPack parse(String extra,String passes) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:depth\","+extra+"\"passes\":"+passes+"}").getAsJsonObject());
    }
    @Test void rasterAndComputeCanReadOwnedForwardDepthSnapshots() {
        var pack=assertDoesNotThrow(()->parse("","""
            [{"name":"opaque","phase":"deferred","fragment":"test:depth",
              "inputs":{"D0":"world_depth","D1":"opaque_depth","D2":"opaque_no_hand_depth"}},
             {"name":"compute","stage":"compute","compute":"test:depth","groups":"screen",
              "inputs":{"D0":"world_depth"}}]
            """));
        assertEquals("opaque_depth",pack.passes().getFirst().inputs().get("D1"));
    }
    @Test void ordinaryWorldProgramsCanSampleOwnedDepthWithoutAliasingTheirAttachment() {
        var pack=assertDoesNotThrow(()->parse("""
            "programs":[{"target":"terrain","vertex":"test:world","fragment":"test:world",
            "inputs":{"D0":"world_depth","D1":"opaque_depth","D2":"opaque_no_hand_depth"}}],
            ""","[]"));
        assertEquals("opaque_no_hand_depth",pack.programs().getFirst().inputs().get("D2"));
    }
    @Test void depthProducerNamesCannotBeShadowedByUserResourcesOrOutputs() {
        for(String name:new String[]{"world_depth","opaque_depth","opaque_no_hand_depth"}) {
            assertThrows(IllegalArgumentException.class,()->parse("\"targets\":{\""+name+"\":{\"scale\":1}},","[]"));
            assertThrows(IllegalArgumentException.class,()->parse("","[{\"name\":\""+name+"\",\"fragment\":\"test:depth\"}]"));
            assertThrows(IllegalArgumentException.class,()->parse("\"resources\":{\"images\":{\""+name+"\":{\"format\":\"rgba16f\",\"size\":[2,2]}}},","[]"));
        }
    }
}
