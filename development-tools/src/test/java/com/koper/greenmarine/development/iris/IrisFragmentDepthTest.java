package com.koper.greenmarine.development.iris;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisFragmentDepthTest {
    @Test void coordinateReadsAndHelperWritesRetainSourceForwardValuesUntilMainReturns() {
        String body="""
            // gl_FragDepth gl_FragCoord must stay intact in comments.
            void setDepth(out float d){d=gl_FragCoord[2]*.5;}
            vec4 coord(){return gl_FragCoord;}
            void main(){setDepth(gl_FragDepth);gl_FragDepth+=.1;
                gl_FragColor=vec4(coord().z,gl_FragDepth,gl_FragCoord.zz.x,1.);return;}
            """;
        var original=linked(body);String before=original.stages().get(IrisStage.FRAGMENT).text();
        var program=IrisTerrainAdapter.adapt(original).program();
        String text=program.stages().get(IrisStage.FRAGMENT).text();
        assertTrue(text.contains("return vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w)"),text);
        assertTrue(text.contains("setDepth(gm_IrisDepthValue)"),text);
        assertTrue(text.contains("gl_FragDepth = 1.0 - gm_IrisDepthValue"),text);
        assertTrue(text.contains("// gl_FragDepth gl_FragCoord must stay intact in comments."),text);
        assertEquals(before,original.stages().get(IrisStage.FRAGMENT).text());compile(program);
    }
    @Test void coordinateReadsNeverIntroduceAFragmentDepthWrite() {
        var program=IrisTerrainAdapter.adapt(linked("void main(){gl_FragColor=gl_FragCoord;}" )).program();
        String text=program.stages().get(IrisStage.FRAGMENT).text();
        assertTrue(text.contains("gm_IrisDepthCoord()"),text);
        assertFalse(text.contains("gl_FragDepth"),text);compile(program);
    }
    @Test void readingAnUnwrittenOutputNeverCreatesAHostDepthWrite() {
        var program=IrisTerrainAdapter.adapt(linked("void main(){gl_FragColor=vec4(gl_FragDepth);}")).program();
        String text=program.stages().get(IrisStage.FRAGMENT).text();
        assertFalse(text.contains("gl_FragDepth ="),text);compile(program);
    }
    @Test void forwardHostDepthNeedsNoAdaptationAndDeclarationAloneNeverIntroducesWrites() {
        var original=linked("layout(depth_less) out float gl_FragDepth;void main(){gl_FragColor=vec4(1.);}");
        for(var depth:IrisTerrainAdapter.ClipDepth.values()) {
            var program=IrisTerrainAdapter.adapt(original,IrisTerrainAdapter.ColorProfile.HOST_LIT,depth).program();
            String text=program.stages().get(IrisStage.FRAGMENT).text();
            assertFalse(text.contains("gl_FragDepth ="),text);
            assertTrue(text.contains(depth==IrisTerrainAdapter.ClipDepth.ZERO_TO_ONE_REVERSED?"depth_greater":"depth_less"),text);
            compile(program);
        }
    }
    @Test void conservativeDepthInequalitiesReverseWhileAnyAndUnchangedRemain() {
        for(String qualifier:List.of("greater","less","any","unchanged")) {
            var program=IrisTerrainAdapter.adapt(linked("layout(depth_"+qualifier+") out float gl_FragDepth;void main(){gl_FragDepth=gl_FragCoord.z;gl_FragColor=vec4(1.);}" )).program();
            String text=program.stages().get(IrisStage.FRAGMENT).text();
            String expected=switch(qualifier){case "greater"->"less";case "less"->"greater";default->qualifier;};
            assertTrue(text.contains("layout(depth_"+expected+") out float gl_FragDepth;"),text);compile(program);
        }
    }
    @Test void coordinateRedeclarationsRemainExplicitlyUnsupported() {
        var source=linked("layout(pixel_center_integer) in vec4 gl_FragCoord;void main(){gl_FragColor=gl_FragCoord;}");
        assertTrue(assertThrows(IllegalArgumentException.class,()->IrisTerrainAdapter.adapt(source)).getMessage().contains("FRAGMENT_COORD_LAYOUT"));
    }
    @Test void mainPrototypeAndEmbeddedCommentsSurviveDepthWrapping() {
        var program=IrisTerrainAdapter.adapt(linked("void main();void/*main-comment*/main(){gl_FragDepth=.25;gl_FragColor=vec4(1.);}" )).program();
        String text=program.stages().get(IrisStage.FRAGMENT).text();
        assertTrue(text.contains("/*main-comment*/"),text);compile(program);
    }
    @Test void outputMetadataAndItsCommentsStayOnTheActualDepthOutput() {
        for(String metadata:List.of("invariant gl_FragDepth;", "invariant precise gl_FragDepth;", "precise invariant gl_FragDepth;", "layout(/*depth-comment*/depth_less) invariant out float gl_FragDepth;",
            "layout(depth_less) out invariant highp float gl_FragDepth;")) {
            var program=IrisTerrainAdapter.adapt(linked(metadata+"void main(){gl_FragDepth=gl_FragCoord.z;gl_FragColor=vec4(1.);}")).program();
            String text=program.stages().get(IrisStage.FRAGMENT).text();
            assertFalse(text.contains("invariant gm_IrisDepthValue"),text);
            if(metadata.contains("/*depth-comment*/"))assertTrue(text.contains("/*depth-comment*/"),text);
            compile(program);
        }
    }
    @Test void conservativeBoundsRemainValidAtFloatingPointEquality() {
        for(String qualifier:List.of("greater","less","unchanged")) {
            var program=IrisTerrainAdapter.adapt(linked("layout(depth_"+qualifier+") out float gl_FragDepth;void main(){gl_FragDepth=gl_FragCoord.z;gl_FragColor=vec4(1.);}" )).program();
            String text=program.stages().get(IrisStage.FRAGMENT).text();
            String expected=switch(qualifier) {
                case "greater"->"min(1.0 - gm_IrisDepthValue, gl_FragCoord.z)";
                case "less"->"max(1.0 - gm_IrisDepthValue, gl_FragCoord.z)";
                default->"gl_FragDepth = gl_FragCoord.z";
            };
            assertTrue(text.contains(expected),text);compile(program);
        }
    }
    @Test void declarationPlaceholderTextInsideCommentsIsNeverRestoredAsCode() {
        String comment="// gm_IrisDepthDeclaration0;";
        var program=IrisFragmentDepth.adapt(linked("layout(depth_less) out float gl_FragDepth;\n"+comment+"\nvoid main(){gl_FragDepth=gl_FragCoord.z;gl_FragColor=vec4(1.);}"));
        assertTrue(program.stages().get(IrisStage.FRAGMENT).text().contains(comment));compile(program);
    }
    private static IrisProgramLinker.Program linked(String fragment) {
        return IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 450\nvoid main(){gl_Position=ftransform();}",IrisStage.FRAGMENT,"#version 450\n"+fragment));
    }
    private static void compile(IrisProgramLinker.Program program) {
        for(var stage:program.stages().entrySet()) {
            var compiled=IrisCompiler.compileExplicit(stage.getValue().text(),stage.getKey(),"depth");
            assertTrue(compiled.success(),compiled.diagnostics()+"\n"+stage.getValue().text());
        }
    }
}
