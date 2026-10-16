package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackPassLightingHistoryTest {
    private static ShaderPack parse(String options) {
        return ShaderPack.parse(JsonParser.parseString("{\"schema\":1,\"id\":\"test:history\",\"passes\":[{\"name\":\"compose\",\"fragment\":\"test:compose\","+options+"}]}").getAsJsonObject());
    }
    @Test void lightingHistoryRequiresSceneHistory() {
        assertThrows(IllegalArgumentException.class,()->parse("\"lightingHistory\":true"));
        assertThrows(IllegalArgumentException.class,()->parse("\"history\":false,\"lightingHistory\":true"));
        assertThrows(IllegalArgumentException.class,()->parse("\"stage\":\"lightmap\",\"history\":true,\"lightingHistory\":true"));
        assertDoesNotThrow(()->parse("\"lightingHistory\":false"));
    }
    @Test void lightingHistoryIsAnExplicitOptIn() {
        var optedIn=parse("\"history\":true,\"lightingHistory\":true").passes().getFirst();
        var ordinary=parse("\"history\":true").passes().getFirst();
        assertNotEquals(ordinary,optedIn);
        assertTrue(optedIn.lightingHistory());
        assertFalse(ordinary.lightingHistory());
        assertThrows(IllegalArgumentException.class,()->new PackPass("compose",PackPass.Stage.SCENE,"test:compose",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE,false,Map.of(),true));
    }
    @Test void everyPreviousConstructorKeepsLightingResetBehavior() {
        assertFalse(new PackPass("compose",PackPass.Stage.SCENE,"test:compose",null,PackPass.Requirement.NONE,Quality.LOW,1,null).lightingHistory());
        assertFalse(new PackPass("compose",PackPass.Stage.SCENE,"test:compose",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE).lightingHistory());
        assertFalse(new PackPass("compose",PackPass.Stage.SCENE,"test:compose",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE,true).lightingHistory());
        assertFalse(new PackPass("compose",PackPass.Stage.SCENE,"test:compose",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE,true,Map.of()).lightingHistory());
    }
}
