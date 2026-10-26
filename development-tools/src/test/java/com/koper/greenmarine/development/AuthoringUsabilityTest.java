package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthoringUsabilityTest {
    @TempDir Path directory;
    @Test void materialPackCanBeSavedOutsideTheGameDirectoryAndReopened() throws Exception {
        var canvas = new MaterialCanvas(64,64);
        canvas.beginStroke(); canvas.paintLine(31,31,31,31,0xffffffff,1); canvas.endStroke();
        Path chosen = directory.resolve("My materials");
        var saved = canvas.saveToPack(chosen,"demo:textures/test.png");
        assertEquals(chosen,saved.pack());
        assertTrue(Files.isRegularFile(chosen.resolve("pack.mcmeta")));
        assertEquals(0xffffffff,MaterialCanvas.loadFromPack(chosen,"demo:textures/test.png").pixel(31,31));
    }
    @Test void previewKeepsThinStrokesWhenZoomedOut() {
        var canvas = new MaterialCanvas(256,256);
        canvas.beginStroke(); canvas.paintLine(129,129,129,129,0xffffffff,1); canvas.endStroke();
        int[] preview = MaterialPreview.render(canvas,64,0,0,256,256,null,0,0,true);
        assertEquals(0xffffffff,preview[32*64+32]);
        canvas.undo();
        assertNotEquals(0xffffffff,MaterialPreview.render(canvas,64,0,0,256,256,null,0,0,true)[32*64+32]);
    }
    @Test void mainNameCompletionDoesNotDuplicateTheReturnType() {
        String source="void mai";
        var choice=ShaderCompletions.suggest(source,source.length()).stream().filter(c->c.label().equals("main")).findFirst().orElseThrow();
        assertEquals("void main()",ShaderCompletions.accept(source,source.length(),choice).text());
    }
    @Test void completionsReplaceOnlyThePrefixAndExplainFunctions() {
        String source = "float customLight;\nvoid main(){ vec3 c = norm";
        var choices = ShaderCompletions.suggest(source,source.length());
        var normalize = choices.stream().filter(c -> c.label().equals("normalize")).findFirst().orElseThrow();
        assertTrue(normalize.help().contains("length"));
        var edit = ShaderCompletions.accept(source,source.length(),normalize);
        assertEquals("float customLight;\nvoid main(){ vec3 c = normalize()",edit.text());
        assertEquals(edit.text().length()-1,edit.cursor());
        assertTrue(ShaderCompletions.suggest("float customLight;\ncust",22).stream().anyMatch(c -> c.label().equals("customLight")));
        assertTrue(ShaderCompletions.suggest("// norm",7).isEmpty());
    }
}
