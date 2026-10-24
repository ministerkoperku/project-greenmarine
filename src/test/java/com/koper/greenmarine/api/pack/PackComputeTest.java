package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import com.koper.greenmarine.client.RenderSchedule;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackComputeTest {
    private static final String MANIFEST = """
        {"schema":1,"id":"demo:compute","name":"Compute",
         "resources":{"images":{"Sky":{"format":"rgba16f","size":[64,32]},"Half":{"format":"r32f","scale":0.5}},
                      "buffers":{"SunData":{"size":256}}},
         "passes":[
           {"name":"sun","stage":"compute","compute":"demo:compute/sun","groups":[1,1,1]},
           {"name":"sky","stage":"compute","compute":"demo:compute/sky","groups":"image:Sky","requires":"compute"},
           {"name":"grade","fragment":"demo:core/grade","inputs":{"SkyLut":"Sky"}},
           {"name":"blur","stage":"compute","compute":"demo:compute/blur","groups":"screen","inputs":{"Graded":"grade"}},
           {"name":"compose","fragment":"demo:core/compose","inputs":{"Blurred":"Half","Graded":"grade"}}
         ]}""";
    private static ShaderPack parse(String json) { return ShaderPack.parse(JsonParser.parseString(json).getAsJsonObject()); }
    private final RenderCapabilities compute = new RenderCapabilities("Vulkan", true, false, true, true, true);
    private final RenderCapabilities noCompute = new RenderCapabilities("OpenGL", false, false, true, false);

    @Test void parsesResourcesAndComputePasses() {
        var pack = parse(MANIFEST);
        assertEquals(new PackResources.Image(PackResources.Format.RGBA16F, 64, 32, 0), pack.resources().images().get("Sky"));
        assertEquals(32, pack.resources().images().get("Half").width(63));
        assertEquals(256, pack.resources().buffers().get("SunData").size());
        var sun = pack.passes().getFirst();
        assertEquals(PackPass.Stage.COMPUTE, sun.stage());
        assertEquals("demo:compute/sun", sun.fragment());
        assertEquals("1,1,1", sun.groups());
        assertEquals("image:Sky", pack.passes().get(1).groups());
        assertEquals("assets/demo/shaders/compute/sun.csh".replace("assets/", ""), PackCatalog.computeResource("demo:compute/sun").replace("demo:", "demo/"));
    }

    @Test void schedulesComputeInOrderAndDropsItWithoutComputePath() {
        var pack = parse(MANIFEST);
        var scheduled = RenderSchedule.scene(pack, Quality.LOW, compute, RayMode.OFF, 100, 50).stream().map(p -> p.resolved().definition().name()).toList();
        assertEquals(List.of("sun", "sky", "grade", "blur", "compose"), scheduled);
        var portable = RenderSchedule.scene(pack, Quality.LOW, noCompute, RayMode.OFF, 100, 50).stream().map(p -> p.resolved().definition().name()).toList();
        assertEquals(List.of("grade", "compose"), portable);
        assertTrue(RenderSchedule.retainedOutputs(RenderSchedule.scene(pack, Quality.LOW, compute, RayMode.OFF, 100, 50)).contains("grade"));
    }

    @Test void computeRequirementSelectsFragmentFallback() {
        var pass = new PackPass("lit", PackPass.Stage.SCENE, "demo:core/gpu", "demo:core/cpu", PackPass.Requirement.COMPUTE, Quality.LOW, 1, null);
        assertEquals("demo:core/gpu", RenderPolicy.pass(pass, Quality.LOW, compute, RayMode.OFF).orElseThrow().fragment());
        assertEquals("demo:core/cpu", RenderPolicy.pass(pass, Quality.LOW, noCompute, RayMode.OFF).orElseThrow().fragment());
    }

    @Test void rejectsMalformedComputeDeclarations() {
        for (var bad : List.of(
            // compute pass with fragment key
            MANIFEST.replace("\"compute\":\"demo:compute/sun\"", "\"fragment\":\"demo:compute/sun\""),
            // missing groups
            MANIFEST.replace(",\"groups\":[1,1,1]", ""),
            // groups sized by an undeclared image
            MANIFEST.replace("image:Sky", "image:Nope"),
            // a compute pass cannot take scale/history
            MANIFEST.replace("\"groups\":\"screen\"", "\"groups\":\"screen\",\"scale\":0.5"),
            // input naming an unknown producer
            MANIFEST.replace("\"SkyLut\":\"Sky\"", "\"SkyLut\":\"Moon\""),
            // reserved resource name
            MANIFEST.replace("\"SunData\"", "\"MarineFrame\""),
            // resource colliding with a pass name
            MANIFEST.replace("\"Half\"", "\"grade\"").replace("\"Blurred\":\"Half\"", "\"Blurred\":\"grade\""),
            // bad buffer size
            MANIFEST.replace("\"size\":256", "\"size\":3"),
            // image with both size and scale
            MANIFEST.replace("\"size\":[64,32]", "\"size\":[64,32],\"scale\":1"),
            // groups on a scene pass
            MANIFEST.replace("\"fragment\":\"demo:core/grade\"", "\"fragment\":\"demo:core/grade\",\"groups\":\"screen\""),
            // unknown resource kind
            MANIFEST.replace("\"buffers\":{", "\"textures\":{},\"buffers\":{")))
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
    }

    @Test void computeDoesNotCountTowardsTheEightRasterPasses() {
        var passes = new StringBuilder();
        for (int i = 0; i < 12; i++) passes.append("{\"name\":\"c").append(i).append("\",\"stage\":\"compute\",\"compute\":\"demo:compute/c\",\"groups\":[1,1,1]},");
        for (int i = 0; i < 8; i++) passes.append("{\"name\":\"s").append(i).append("\",\"fragment\":\"demo:core/s\"},");
        var json = "{\"schema\":1,\"id\":\"demo:many\",\"passes\":[" + passes.substring(0, passes.length() - 1) + "]}";
        assertEquals(20, parse(json).passes().size());
    }
}
