package com.koper.greenmarine.development;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceEditingTest {
    @TempDir Path root;
    @Test void directoryRenameKeepsDirtyDocumentsCaretsAndEmptyFolders() throws Exception {
        var ws=new ShaderWorkspace(root);String start=ws.createExample();var project=new ShaderProject(ws,start);
        project.createFolder("empty/nested");
        assertTrue(project.children("empty").stream().anyMatch(e->e.directory()&&e.name().equals("nested")));
        project.edit(start,"unsaved",3);
        String directory=start.substring(project.folder().length()+1,start.lastIndexOf('/'));
        project.rename(project.folder()+"/"+directory,"moved");
        String moved=project.folder()+"/moved/preview.fsh";
        assertEquals("unsaved",project.open(moved).text());assertEquals(3,project.open(moved).cursor());
        assertThrows(Exception.class,()->project.rename(project.folder()+"/empty","empty/nested/inside"));
        assertThrows(Exception.class,()->project.createFolder("../escape"));
    }
    @Test void imageDraftJoinsZipAndTransactionalSaveAndDetectsExternalWrites() throws Exception {
        var ws=new ShaderWorkspace(root);String start=ws.createExample();var project=new ShaderProject(ws,start);
        String image=project.createImage("assets/demo/textures/test.png",16,8);
        var texture=project.openImage(image);
        assertSame(texture,project.openImage(image));
        texture.beginStroke();texture.paint(1,2,7,2,0xff12ab34,1);texture.endStroke();
        assertTrue(texture.dirty());assertEquals(0xff12ab34,texture.pixel(4,2));
        assertTrue(texture.undo());assertEquals(0,texture.pixel(4,2));
        texture.beginStroke();texture.paint(0,0,0,0,0xff112233,1);texture.endStroke();
        project.edit(start,"draft source",2);
        Path zip=root.resolve("bundle.zip");project.export(zip,true);
        try(var archive=new ZipFile(zip.toFile())) {
            var exported=TextureDocument.decode(archive.getInputStream(archive.getEntry("assets/demo/textures/test.png")).readAllBytes());
            assertEquals(0xff112233,exported.pixel(0,0));
        }
        assertEquals(0,TextureDocument.decode(Files.readAllBytes(root.resolve(image))).pixel(0,0));
        project.saveAll();assertFalse(texture.dirty());assertEquals("draft source",ws.read(start));
        texture.beginStroke();texture.paint(0,0,0,0,0xffabcdef,1);texture.endStroke();
        Files.write(root.resolve(image),new TextureDocument(16,8).encode());
        project.edit(start,"second draft",0);
        assertThrows(Exception.class,project::saveAll);
        assertEquals("draft source",ws.read(start));
    }
    @Test void directoryRenameMovesImageDraftWithoutClobberingAnExistingFile() throws Exception {
        var ws=new ShaderWorkspace(root);String start=ws.createExample();var project=new ShaderProject(ws,start);
        String image=project.createImage("textures/a.png",2,2);var draft=project.openImage(image);
        draft.beginStroke();draft.paint(0,0,1,1,0xffeeeeee,1);draft.endStroke();
        project.rename(project.folder()+"/textures","renamed");
        assertSame(draft,project.openImage(project.folder()+"/renamed/a.png"));
        assertThrows(Exception.class,()->project.createImage("renamed/a.png",2,2));
    }
}
