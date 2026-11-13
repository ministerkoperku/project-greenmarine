package com.koper.greenmarine.development.iris;

import com.google.gson.*;
import com.koper.greenmarine.api.pack.PackInputs;
import com.koper.greenmarine.api.render.UniformExpressions;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisNativeInputsTest {
    private static IrisPipelinePlan plan(String settings,String properties,String... worldAndAngle) throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        files.put("shaders/lib/settings.glsl",settings.getBytes(StandardCharsets.UTF_8));
        files.put("shaders/shaders.properties",properties.getBytes(StandardCharsets.UTF_8));
        for(int i=0;i<worldAndAngle.length;i+=2) {
            String prefix=worldAndAngle[i].isEmpty()?"":worldAndAngle[i]+"/";
            files.put("shaders/"+prefix+"composite.vsh",("#version 430\nconst float sunPathRotation="+worldAndAngle[i+1]+";void main(){gl_Position=vec4(0);}").getBytes(StandardCharsets.UTF_8));
            files.put("shaders/"+prefix+"composite.fsh","#version 430\nvoid main(){gl_FragColor=vec4(1);}".getBytes(StandardCharsets.UTF_8));
        }
        return IrisPipelinePlan.parse(IrisSources.of(files));
    }
    private static JsonObject world(JsonObject document,String folder) {
        return document.getAsJsonArray("worlds").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(w->w.get("folder").getAsString().equals(folder)).findFirst().orElseThrow();
    }
    @Test void exportsNativeDeclarationsAndOnlyTheirRequiredOptionConstants() throws Exception {
        var document=IrisNativeInputs.export(plan("#define GAIN 1.25\n#define UNUSED 99\n",
            "variable.float.base=eyeAltitude * GAIN\nuniform.float.height=base + 2\n", "", "-35"));
        var entry=world(document,"");assertTrue(entry.get("configurationValid").getAsBoolean());
        var config=PackInputs.parse(entry.getAsJsonObject("uniformInputs"));
        assertEquals(Set.of("GAIN"),config.constants().keySet());assertEquals(-35,config.sunPathRotation());
        var inputs=new HashMap<String,Object>(config.constants());inputs.put("eyeAltitude",4);
        assertEquals(7f,config.program().evaluate(inputs,new UniformExpressions.State(),0).uniforms().get("height"));
        assertEquals(List.of("eyeAltitude"),entry.getAsJsonArray("requiredHostReferences").asList().stream().map(JsonElement::getAsString).toList());
        assertFalse(document.get("runtimeReady").getAsBoolean());
    }
    @Test void unresolvedOptionIsExplicitRatherThanZeroOrFalse() throws Exception {
        var document=IrisNativeInputs.export(plan("#define GAIN (1.0 + 2.0)\n",
            "uniform.float.gain=GAIN\n", "", "0"));
        var entry=world(document,"");
        assertTrue(entry.getAsJsonObject("unresolvedOptionMacros").has("GAIN"));
        assertFalse(entry.getAsJsonObject("uniformInputs").getAsJsonObject("constants").has("GAIN"));
        assertFalse(entry.get("optionConstantsComplete").getAsBoolean());
        assertTrue(entry.getAsJsonArray("diagnostics").size()>0);
    }
    @Test void worldSunPathDoesNotLeakAcrossCompleteOverrideSets() throws Exception {
        var document=IrisNativeInputs.export(plan("", "", "", "0", "world0", "-35", "world-1", "12.5"));
        assertEquals(0,world(document,"").getAsJsonObject("uniformInputs").get("sunPathRotation").getAsFloat());
        assertEquals(-35,world(document,"world0").getAsJsonObject("uniformInputs").get("sunPathRotation").getAsFloat());
        assertEquals(12.5f,world(document,"world-1").getAsJsonObject("uniformInputs").get("sunPathRotation").getAsFloat());
    }
    @Test void malformedExpressionKeepsDiagnosticsWithoutEmittingAValidNativeConfig() throws Exception {
        var document=IrisNativeInputs.export(plan("", "uniform.float.bad=unknownFunction(1)\n", "", "0"));
        var entry=world(document,"");assertFalse(entry.get("configurationValid").getAsBoolean());
        assertTrue(entry.get("uniformInputs").isJsonNull());assertFalse(entry.getAsJsonArray("rawExpressions").isEmpty());
        assertTrue(entry.getAsJsonArray("diagnostics").get(0).getAsString().contains("Unknown function"));
    }
}
