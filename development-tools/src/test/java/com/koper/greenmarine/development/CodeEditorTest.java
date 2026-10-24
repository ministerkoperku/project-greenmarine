package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.koper.greenmarine.development.ShaderCompiler.*;

class CodeEditorTest {
    @Test void errorLinesPointAtEditorSourceAfterMultiLineIncludes() {
        var includes = Map.of("demo:lib.glsl", "float a(){return 1.0;}\nfloat b(){return 2.0;}\nfloat c(){return 3.0;}\n");
        String source = "#version 450\n#include <demo:lib.glsl>\nlayout(location=0) out vec4 color;\nvoid main(){\n    color = vec4(nope);\n}\n";
        var result = compile(Language.GLSL, Stage.FRAGMENT, source, "edit.fsh", null, includes::get);
        assertFalse(result.success());
        var issues = CompilerDiagnostics.parse(result.diagnostics(), "edit.fsh");
        assertFalse(issues.isEmpty(), result.diagnostics());
        assertEquals(5, issues.getFirst().line(), result.diagnostics());
        assertTrue(issues.getFirst().error());
    }
    @Test void errorsInsideIncludesLandOnTheIncludeLine() {
        var includes = Map.of("demo:bad.glsl", "float a(){return 1.0;}\nfloat b(){return broken;}\n");
        var result = compile(Language.GLSL, Stage.FRAGMENT, "#version 450\n\n#include <demo:bad.glsl>\nvoid main(){}\n", "inc.fsh", null, includes::get);
        var issues = CompilerDiagnostics.parse(result.diagnostics(), "inc.fsh");
        assertEquals(3, issues.getFirst().line(), result.diagnostics());
        assertTrue(issues.getFirst().message().contains("demo:bad.glsl"));
    }
    @Test void parserKeepsFirstErrorPerLineAndIgnoresOtherFiles() {
        var issues = CompilerDiagnostics.parse("a.fsh:4: warning: meh\na.fsh:4: error: bad\nb.fsh:2: error: other\na.fsh:9: error: later\n1 error generated.", "a.fsh");
        assertEquals(3, issues.size());
        var lines = CompilerDiagnostics.byLine(issues);
        assertTrue(lines.get(4).error()); assertEquals("bad", lines.get(4).message());
        assertEquals(java.util.List.of(4, 9), java.util.List.copyOf(lines.keySet()));
    }
    @Test void highlighterSeparatesCommentsDirectivesKeywordsAndNumbers() {
        String text = "#define X 1 \\\n  2\n/* a\nb */ uniform vec3 v = 1.5; // tail\ngl_FragColor";
        int[] colors = GlslHighlighter.colors(text);
        assertEquals(text.length(), colors.length);
        assertEquals(GlslHighlighter.PREPROCESSOR, colors[text.indexOf("2")]);
        assertEquals(GlslHighlighter.COMMENT, colors[text.indexOf("b */")]);
        assertEquals(GlslHighlighter.KEYWORD, colors[text.indexOf("uniform")]);
        assertEquals(GlslHighlighter.TYPE, colors[text.indexOf("vec3")]);
        assertEquals(GlslHighlighter.NUMBER, colors[text.indexOf("1.5")]);
        assertEquals(GlslHighlighter.COMMENT, colors[text.indexOf("tail")]);
        assertEquals(GlslHighlighter.BUILTIN, colors[text.indexOf("gl_Frag")]);
        assertEquals(GlslHighlighter.TEXT, colors[text.indexOf(" v ") + 1]);
    }
}
