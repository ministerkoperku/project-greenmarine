package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackExportTest {
    @TempDir Path root;
    @Test void exportsTheEntirePackWithAnOptionalDraftAndLeavesTheWorkspaceUnchanged() throws Exception {
        var workspace=new ShaderWorkspace(root.resolve("workspace"));
        String source=workspace.createExample(),project=source.substring(0,source.indexOf('/'));
        workspace.save(project+"/assets/demo/shaders/include/shared.glsl","float value=1.0;");
        Path texture=root.resolve("workspace").resolve(project).resolve("assets/demo/textures/test.png");
        Files.createDirectories(texture.getParent());Files.write(texture,new byte[]{1,2,3,4});
        String baseline=workspace.read(source);
        Path destination=root.resolve("My exported pack.zip");
        var exported=ShaderPackExporter.exportZip(root.resolve("workspace"),source,"draft source",destination);
        assertTrue(exported.includesDraft());assertEquals(5,exported.files());
        try(var zip=new ZipFile(destination.toFile())){
            String relative=source.substring(source.indexOf('/')+1);
            assertEquals("draft source",new String(zip.getInputStream(zip.getEntry(relative)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertNotNull(zip.getEntry("assets/development/greenmarine/packs/preview.json"));
            assertNotNull(zip.getEntry("assets/demo/shaders/include/shared.glsl"));
            assertArrayEquals(new byte[]{1,2,3,4},zip.getInputStream(zip.getEntry("assets/demo/textures/test.png")).readAllBytes());
            assertNull(zip.getEntry(project+"/"+relative));
        }
        assertEquals(baseline,workspace.read(source));
    }
    @Test void rejectsSymlinkedAssetsWithoutReplacingAnExistingExport() throws Exception {
        var workspace=new ShaderWorkspace(root.resolve("workspace"));String source=workspace.createExample();
        Path project=root.resolve("workspace").resolve(source.substring(0,source.indexOf('/')));
        Path outside=root.resolve("private.txt");Files.writeString(outside,"private");
        Files.createSymbolicLink(project.resolve("linked.txt"),outside);
        Path destination=root.resolve("pack.zip");Files.writeString(destination,"keep old export");
        assertThrows(java.io.IOException.class,()->ShaderPackExporter.exportZip(root.resolve("workspace"),source,null,destination));
        assertEquals("keep old export",Files.readString(destination));
    }
    @Test void refusesToExportIntoTheProjectItselfOrTraverseOutsideIt() throws Exception {
        var workspace=new ShaderWorkspace(root.resolve("workspace"));String source=workspace.createExample();
        Path project=root.resolve("workspace").resolve(source.substring(0,source.indexOf('/')));
        assertThrows(java.io.IOException.class,()->ShaderPackExporter.exportZip(root.resolve("workspace"),source,null,project.resolve("recursive.zip")));
        assertThrows(java.io.IOException.class,()->ShaderPackExporter.exportZip(root.resolve("workspace"),"../outside/a.fsh",null,root.resolve("bad.zip")));
    }
}
