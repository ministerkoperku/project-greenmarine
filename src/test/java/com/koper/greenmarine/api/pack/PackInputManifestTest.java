package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackInputManifestTest {
    private static ShaderPack parse(String inputs) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:inputs\",\"passes\":[],\"uniformInputs\":"+inputs+"}").getAsJsonObject());
    }
    @Test void malformedUniformInputsCannotBeSilentlyIgnored() {
        for(String inputs:new String[]{"null","[]","{\"unknown\":1}","{\"sunPathRotation\":\"-35\"}",
            "{\"expressions\":[{\"kind\":\"uniform\",\"type\":\"float\",\"name\":\"value\",\"expression\":\"unknownFunction(1)\"}]}"})
            assertThrows(IllegalArgumentException.class,()->parse(inputs),inputs);
    }
    @Test void manifestRetainsItsCompiledExpressionConfiguration() throws Exception {
        var pack=parse("{\"constants\":{\"SEA_LEVEL\":62},\"sunPathRotation\":-35,\"expressions\":[{\"kind\":\"uniform\",\"type\":\"float\",\"name\":\"height\",\"expression\":\"eyeAltitude - SEA_LEVEL\"}]}");
        var inputs=(PackInputs)ShaderPack.class.getMethod("uniformInputs").invoke(pack);
        assertEquals(-35,inputs.sunPathRotation());assertEquals(1,inputs.expressions().size());
        assertEquals(62,((Number)inputs.constants().get("SEA_LEVEL")).intValue());
    }
}
