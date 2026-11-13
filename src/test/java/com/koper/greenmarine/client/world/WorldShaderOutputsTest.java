package com.koper.greenmarine.client.world;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldShaderOutputsTest {
    @Test void conservativeBuiltinDepthIsNotAColorAttachment() {
        for(String declaration:new String[]{"layout(depth_greater) out float gl_FragDepth;",
            "layout(depth_less) invariant out highp float gl_FragDepth;"}) {
            var source=declaration+"\nlayout(location=0) out vec4 color;";
            var mapped=WorldShaderOutputs.remap(source,Map.of(0,2));
            assertTrue(mapped.contains(declaration));assertTrue(mapped.contains("layout(location=2) out vec4 color;"));
        }
        assertThrows(IllegalArgumentException.class,()->WorldShaderOutputs.remap("layout(depth_any) out float fake;layout(location=0) out vec4 color;",Map.of(0,2)));
    }
    @Test void namedLocationsMapWithoutRewritingVaryingInputsOrCommentExamples() {
        String source="""
            layout(location=0) in vec3 normal;
            // layout(location=4) out vec4 example;
            layout(location=0) out vec4 radiance;
            layout(location=1) flat out uvec4 material;
            """;
        var result=WorldShaderOutputs.remap(source,Map.of(0,2,1,1));
        assertTrue(result.contains("layout(location=0) in vec3 normal"));
        assertTrue(result.contains("// layout(location=4) out vec4 example"));
        assertTrue(result.contains("layout(location=2) out vec4 radiance"));
        assertTrue(result.contains("layout(location=1) flat out uvec4 material"));
    }
    @Test void undeclaredOutputsCannotAccidentallyWriteAnotherTarget() {
        assertThrows(IllegalArgumentException.class,()->WorldShaderOutputs.remap("layout(location=0) out vec4 a;layout(location=1) out vec4 b;",Map.of(0,1)));
        assertThrows(IllegalArgumentException.class,()->WorldShaderOutputs.remap("out vec4 a;",Map.of(0,1)));
    }
}
