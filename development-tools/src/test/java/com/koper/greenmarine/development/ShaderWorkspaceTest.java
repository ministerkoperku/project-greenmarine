package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShaderWorkspaceTest {
    @TempDir Path temp;
    @Test void createsUniqueEditableExamplesInsideInitiallyAbsentRoot() throws Exception {
        var workspace=new ShaderWorkspace(temp.resolve("missing/packs"));
        String first=workspace.createExample(), second=workspace.createExample();
        assertNotEquals(first,second); assertTrue(first.endsWith("/assets/development/shaders/core/preview.fsh"));
        assertTrue(workspace.files().containsAll(List.of(first,second)));
        var result=ShaderCompiler.compile(ShaderCompiler.Language.GLSL,ShaderCompiler.Stage.FRAGMENT,workspace.read(first),first,null);
        assertTrue(result.success(),result.diagnostics());
        String pack=first.substring(0,first.indexOf('/'));
        assertTrue(workspace.read(pack+"/assets/development/greenmarine/packs/preview.json").contains("development:preview_1"));
        var vertex=ShaderCompiler.compile(ShaderCompiler.Language.GLSL,ShaderCompiler.Stage.VERTEX,workspace.read(first.replace(".fsh",".vsh")),"preview.vsh",null);
        assertTrue(vertex.success(),vertex.diagnostics());
    }
    @Test void savesUtf8AndPreservesOriginalWhenValidationFails() throws Exception {
        var workspace=new ShaderWorkspace(temp.resolve("packs")); String path=workspace.createExample();
        workspace.save(path,"original π"); byte[] original=Files.readAllBytes(temp.resolve("packs").resolve(path));
        var failure=new ShaderCompiler.Result(false,"bad shader",new byte[0]);
        assertSame(failure,workspace.saveValidated(path,"broken",source -> failure));
        assertArrayEquals(original,Files.readAllBytes(temp.resolve("packs").resolve(path)));
        var success=new ShaderCompiler.Result(true,"",new byte[]{1});
        assertSame(success,workspace.saveValidated(path,"updated λ",source -> success));
        assertEquals("updated λ",workspace.read(path));
        try(var files=Files.list(temp.resolve("packs").resolve(path).getParent())) { assertEquals(2,files.count()); }
    }
    @Test void editsAndListsComputeShaderSources() throws Exception {
        var workspace=new ShaderWorkspace(temp.resolve("packs"));
        String source="#version 450\nlayout(local_size_x=1) in; void main(){}";
        workspace.save("example/shaders/sample.comp",source);
        assertEquals(source,workspace.read("example/shaders/sample.comp"));
        assertEquals(List.of("example/shaders/sample.comp"),workspace.files());
    }
    @Test void rejectsTraversalAbsoluteBackslashAndNonEditablePaths() throws Exception {
        var workspace=new ShaderWorkspace(temp.resolve("packs")); workspace.createExample();
        for(String path:List.of("../escape.glsl","/tmp/escape.glsl","pack/../escape.glsl","pack\\escape.glsl","pack/native.so","top.glsl")) {
            assertThrows(IOException.class,()->workspace.save(path,"bad"),path);
            assertThrows(IOException.class,()->workspace.read(path),path);
        }
        assertFalse(Files.exists(temp.resolve("escape.glsl")));
    }
    @Test void rejectsSymlinkFilesDirectoriesAndRootAncestors() throws Exception {
        Path root=temp.resolve("packs"), external=Files.createDirectory(temp.resolve("external"));
        var workspace=new ShaderWorkspace(root); String path=workspace.createExample();
        Files.writeString(external.resolve("outside.glsl"),"untouched");
        Files.createSymbolicLink(root.resolve("linked"),external);
        Files.createSymbolicLink(root.resolve("workbench-1/link.glsl"),external.resolve("outside.glsl"));
        assertThrows(IOException.class,()->workspace.save("linked/outside.glsl","bad"));
        assertThrows(IOException.class,()->workspace.read("workbench-1/link.glsl"));
        assertThrows(IOException.class,()->workspace.save("workbench-1/link.glsl","bad"));
        assertEquals("untouched",Files.readString(external.resolve("outside.glsl")));
        assertFalse(workspace.files().contains("workbench-1/link.glsl"));
        Path link=temp.resolve("alias"); Files.createSymbolicLink(link,external);
        assertThrows(IOException.class,()->new ShaderWorkspace(link.resolve("packs")).createExample());
    }
    @Test void limitsUtf8BytesAndListedFilesAndIgnoresZips() throws Exception {
        Path root=temp.resolve("packs"); var workspace=new ShaderWorkspace(root); String path=workspace.createExample();
        assertThrows(IOException.class,()->workspace.save(path,"π".repeat(131073)));
        Files.writeString(root.resolve(path),"x".repeat(262145));
        assertThrows(IOException.class,()->workspace.read(path));
        Files.writeString(root.resolve("archive.zip"),"not scanned");
        Path pack=root.resolve("many"); Files.createDirectory(pack);
        for(int i=0;i<520;i++) Files.writeString(pack.resolve(i+".glsl"),"test");
        assertEquals(512,workspace.files().size());
        assertTrue(workspace.files().stream().noneMatch(name->name.contains("archive.zip")));
    }
}
