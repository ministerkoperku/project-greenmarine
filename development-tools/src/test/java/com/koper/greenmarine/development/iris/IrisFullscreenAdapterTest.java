package com.koper.greenmarine.development.iris;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisFullscreenAdapterTest {
    @Test void preservesCustomVertexMathAndMatchingVaryingsWhileSupplyingActualQuadInputs() {
        var program=IrisFullscreenAdapter.link(Map.of(
            IrisStage.VERTEX,"#version 120\nvarying vec2 uv; varying float gain; void main(){gl_Position=ftransform();uv=gl_MultiTexCoord0.xy;gain=1.+uv.x*uv.y;}",
            IrisStage.FRAGMENT,"#version 120\nvarying vec2 uv; varying float gain;void main(){gl_FragColor=vec4(uv,gain,1.);}"));
        assertEquals(6,program.vertices());
        assertTrue(program.program().uniforms().isEmpty());
        var vertex=program.program().stages().get(IrisStage.VERTEX).text();
        assertTrue(vertex.contains("uv.x*uv.y"));
        assertFalse(vertex.contains("in vec3 iris_Position"));
        for(var stage:program.program().stages().entrySet()) {
            var compiled=IrisCompiler.compileExplicit(stage.getValue().text(),stage.getKey(),"adapted");
            assertTrue(compiled.success(),compiled.diagnostics());
        }
    }
    @Test void refusesUnknownVertexInputsRatherThanReplacingTheirMeaning() {
        assertThrows(IllegalArgumentException.class,()->IrisFullscreenAdapter.link(Map.of(
            IrisStage.VERTEX,"#version 330\nin vec3 customAttribute;void main(){gl_Position=vec4(customAttribute,1);}",
            IrisStage.FRAGMENT,"#version 330\nvoid main(){gl_FragColor=vec4(1);}")));
    }
    @Test void supportsModernPositionAndUvDeclarationsAndDoesNotConfuseWorldMatricesWithFullscreenOnes() {
        var adapted=IrisFullscreenAdapter.link(Map.of(
            IrisStage.VERTEX,"#version 330\nin vec3 vaPosition;in vec2 vaUV0;out vec2 uv;uniform mat4 gbufferProjection;void main(){uv=vaUV0;gl_Position=vec4(vaPosition.xy*2.-1.,0,1);}",
            IrisStage.FRAGMENT,"#version 330\nin vec2 uv;void main(){gl_FragColor=vec4(uv,0,1);}"));
        assertTrue(adapted.program().uniforms().stream().anyMatch(u->u.name().equals("gbufferProjection")));
        assertFalse(adapted.program().stages().get(IrisStage.VERTEX).text().contains("in vec3 vaPosition"));
    }
}
