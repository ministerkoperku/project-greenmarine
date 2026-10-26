package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class ShaderProjectTest {
    @TempDir Path root;
    @Test void multipleBuffersKeepDraftsAndCaretsAcrossSwitchesAndExport() throws Exception {
        var workspace=new ShaderWorkspace(root);String fragment=workspace.createExample();
        var project=new ShaderProject(workspace,fragment);
        project.edit(fragment,"fragment draft",7);
        String vertex=fragment.replace(".fsh",".vsh");project.open(vertex);project.edit(vertex,"vertex draft",4);
        assertEquals("fragment draft",project.open(fragment).text());assertEquals(7,project.open(fragment).cursor());
        Path zip=root.resolve("export.zip");project.export(zip,true);
        try(var archive=new ZipFile(zip.toFile())){
            for(var entry:Map.of(fragment,"fragment draft",vertex,"vertex draft").entrySet())
                assertEquals(entry.getValue(),new String(archive.getInputStream(archive.getEntry(entry.getKey().substring(entry.getKey().indexOf('/')+1))).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(workspace.read(fragment).startsWith("#version"));assertTrue(workspace.read(vertex).startsWith("#version"));
    }
    @Test void managesComputeIncludesAndAssetsWithoutOverwritingOtherFiles() throws Exception {
        var workspace=new ShaderWorkspace(root);String start=workspace.createExample();var project=new ShaderProject(workspace,start);
        String compute=project.create("assets/demo/shaders/compute/work.csh","#version 450\nvoid main(){}\n");
        project.edit(compute,"draft compute",3);
        String moved=project.rename(compute,"assets/demo/shaders/compute/renamed.csh");
        assertEquals("draft compute",project.open(moved).text());assertFalse(Files.exists(root.resolve(compute)));
        assertThrows(Exception.class,()->project.create("assets/demo/shaders/compute/renamed.csh","overwrite"));
        Path png=root.resolve("source.png");Files.write(png,new byte[]{1,2,3});
        String asset=project.importAsset(png,"assets/demo/textures/test.png");
        assertTrue(project.files().contains(asset));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(root.resolve(asset)));
        assertThrows(Exception.class,()->project.create("../escape.fsh","bad"));
    }
    @Test void saveAllPreflightsEveryDestinationBeforeWriting() throws Exception {
        var workspace=new ShaderWorkspace(root);String start=workspace.createExample(),other=start.replace(".fsh",".vsh");
        var project=new ShaderProject(workspace,start);String old=workspace.read(start);
        project.edit(start,"changed fragment",0);project.edit(other,"changed vertex",0);
        Files.delete(root.resolve(other));Files.createDirectory(root.resolve(other));
        assertThrows(Exception.class,project::saveAll);
        assertEquals(old,workspace.read(start));assertEquals(2,project.drafts().size());
    }
    @Test void detectsExternalChangesBeforeReplacingAnUnsavedSource() throws Exception {
        var workspace=new ShaderWorkspace(root);String start=workspace.createExample();var project=new ShaderProject(workspace,start);
        project.edit(start,"my draft",0);workspace.save(start,"external edit");
        assertThrows(Exception.class,project::saveAll);assertEquals("external edit",workspace.read(start));
    }
}
