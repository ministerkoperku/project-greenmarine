package com.koper.greenmarine.development.iris;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisTranslationTest {
    @TempDir Path temp;

    @Test void conditionalsCompareFloatsAndKeepDefinesForExpansion() {
        var pre = new MiniPreprocessor(Map.of("IRIS_VERSION", "11200"));
        String out = pre.resolveConditionals("#define daySpeed 0.5\n#if daySpeed < 1.0 && IRIS_VERSION >= 11004\nkeep\n#else\ndrop\n#endif\n#define daySpeed 2.0\n");
        assertTrue(out.contains("keep")); assertFalse(out.contains("drop"));
        assertTrue(out.contains("#undef daySpeed\n#define daySpeed 2.0"), out);
    }
    @Test void elifAndNestedBranchesMatchCpp() {
        var pre = new MiniPreprocessor(Map.of("A", "2"));
        String out = pre.resolveConditionals("#if A == 1\none\n#elif A == 2\n#ifdef B\nb\n#else\ntwo\n#endif\n#else\nother\n#endif");
        assertEquals("two", out.lines().filter(l -> !l.isBlank()).reduce("", String::concat));
    }
    @Test void commentsCannotHideOrAddDirectives() {
        String stripped = IrisPreprocess.stripComments("a /* #if 0\n */ b // #endif\nc");
        assertFalse(stripped.contains("#")); assertEquals(3, stripped.lines().count());
    }
    @Test void includesResolveAbsoluteAndRelativePaths() throws Exception {
        Path shaders = temp.resolve("pack/shaders");
        Files.createDirectories(shaders.resolve("world0")); Files.createDirectories(shaders.resolve("lib"));
        Files.writeString(shaders.resolve("world0/final.fsh"), "#version 430 compatibility\n#include \"/lib/a.glsl\"\nvoid main(){ gl_FragColor = vec4(a()); }\n");
        Files.writeString(shaders.resolve("lib/a.glsl"), "#include \"b.glsl\"\nfloat a(){ return b(); }\n");
        Files.writeString(shaders.resolve("lib/b.glsl"), "float b(){ return 1.0; }\n");
        var sources = IrisSources.load(temp.resolve("pack"));
        String expanded = IrisPreprocess.expandIncludes(sources, "world0/final.fsh");
        assertTrue(expanded.contains("float b()") && expanded.contains("float a()"));
        assertThrows(IllegalArgumentException.class, () -> IrisPreprocess.normalize("../outside.glsl"));
    }
    @Test void compatFragmentBecomesStrictVulkanGlsl() {
        String source = """
            #version 120
            /* RENDERTARGETS: 3,7 */
            uniform sampler2D colortex0;
            uniform sampler2DShadow shadowtex0;
            uniform float frameTimeCounter, rainStrength = 0.5;
            uniform mat4 gbufferProjectionInverse;
            varying vec2 texcoord;
            vec3 sky(sampler2D sampler) { return texture2D(sampler, texcoord).rgb; }
            void main() {
                float shadow = shadow2D(shadowtex0, vec3(texcoord, 0.5)).x;
                gl_FragData[0] = vec4(sky(colortex0) * shadow * frameTimeCounter, 1.0);
                gl_FragData[1] = gl_ProjectionMatrix * vec4(rainStrength);
            }
            """;
        var pre = IrisPreprocess.preprocess(source, IrisStage.FRAGMENT, "final.fsh", IrisCoverage.hostMacros());
        assertTrue(pre.success(), pre.diagnostics());
        var translation = IrisTranslator.translate(pre.text(), IrisStage.FRAGMENT);
        assertEquals(List.of(3, 7), translation.targets(), pre.text());
        assertEquals(List.of("frameTimeCounter", "gbufferProjectionInverse", "iris_ProjMat", "rainStrength"),
            translation.uniforms().stream().map(IrisTranslator.Uniform::name).toList());
        assertEquals(List.of("colortex0", "shadowtex0"), translation.opaques().stream().map(IrisTranslator.Opaque::name).toList());
        var compiled = IrisCompiler.compile(translation.text(), IrisStage.FRAGMENT, "final.fsh");
        assertTrue(compiled.success(), compiled.diagnostics() + "\n" + translation.text());
    }
    @Test void compatVertexUsesHostAttributesAndMatrices() {
        String source = "#version 120\nvarying vec4 color;\nattribute vec4 mc_Entity;\nvoid main(){ gl_Position = ftransform(); color = gl_Color * mc_Entity.x;"
            + " gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0; vec2 lm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy; color.a += lm.x; }\n";
        var pre = IrisPreprocess.preprocess(source, IrisStage.VERTEX, "gbuffers_basic.vsh", IrisCoverage.hostMacros());
        var translation = IrisTranslator.translate(pre.text(), IrisStage.VERTEX);
        assertTrue(translation.text().contains("in vec3 iris_Position"));
        var compiled = IrisCompiler.compile(translation.text(), IrisStage.VERTEX, "gbuffers_basic.vsh");
        assertTrue(compiled.success(), compiled.diagnostics() + "\n" + translation.text());
    }
    /** Opt-in: -Dgreenmarine.iris.pack=/path/to/Eclipse-Shader keeps the real pack out of the repo. */
    @Test void wholePackCoverageWhenConfigured() throws Exception {
        String pack = System.getProperty("greenmarine.iris.pack");
        org.junit.jupiter.api.Assumptions.assumeTrue(pack != null && !pack.isBlank());
        var results = IrisCoverage.run(IrisSources.load(Path.of(pack)), null);
        var failing = results.stream().filter(r -> !r.optional() && !r.success()).toList();
        assertTrue(failing.isEmpty(), IrisCoverage.report(results));
    }

    @Test void importerReportsWholePackReadinessWhenConfigured() throws Exception {
        String pack = System.getProperty("greenmarine.iris.pack");
        org.junit.jupiter.api.Assumptions.assumeTrue(pack != null && !pack.isBlank());
        var report = com.koper.greenmarine.development.IrisImporter.convert(Path.of(pack), temp.resolve("workspace/eclipse-import"));
        String text = Files.readString(report.destination().resolve("IMPORT-REPORT.md"));
        assertTrue(text.contains("IR-1 translate+compile: 250/250"), text);
        assertTrue(Files.isRegularFile(report.destination().resolve("iris-source/shaders/shaders.properties")));
    }
}
