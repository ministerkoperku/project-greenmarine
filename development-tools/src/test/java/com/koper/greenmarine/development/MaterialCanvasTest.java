package com.koper.greenmarine.development;

import com.koper.greenmarine.api.material.MaterialAddons;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MaterialCanvasTest {
    @TempDir Path directory;
    @AfterEach void clear(){MaterialAddons.reload(List.of());}
    @Test void strokesUndoAsOneOperationAndErasePreservesHoles(){
        var canvas=new MaterialCanvas(64,128);
        canvas.beginStroke();canvas.paintLine(0,0,10,10,0xffffffff,1);canvas.endStroke();
        assertEquals(0xffffffff,canvas.pixel(5,5));assertEquals(0,canvas.pixel(6,5));
        assertTrue(canvas.undo());assertEquals(0,canvas.pixel(5,5));assertFalse(canvas.undo());
        canvas.beginStroke();canvas.paintLine(0,0,0,0,0xffffffff,2);canvas.endStroke();
        canvas.beginStroke();canvas.paintLine(0,0,0,0,0,1);canvas.endStroke();
        assertEquals(0,canvas.pixel(0,0));assertEquals(0xffffffff,canvas.pixel(1,0));
    }
    @Test void exportedWhiteMotifResolvesAndReopens() throws Exception {
        var canvas=new MaterialCanvas(64,64);
        canvas.setMaterial(0xffffff,new MaterialCanvas.Material("coated",.8f,.05f,.12f));
        canvas.beginStroke();canvas.paintLine(2,3,2,3,0xffffffff,1);canvas.endStroke();
        var saved=canvas.save(directory,"mechanics_dream:textures/craftbot.png");
        assertTrue(Files.readString(saved.pack().resolve("pack.mcmeta")).contains("97"));
        var errors=MaterialAddons.reload(List.of(new MaterialAddons.Source("editor",Files.readString(saved.json()),id->{try{return Files.readAllBytes(saved.mask());}catch(Exception e){throw new RuntimeException(e);}},null)));
        assertEquals(List.of(),errors);
        var pixels=MaterialAddons.resolve("mechanics_dream:craftbot","greenmarine:solstice_rt",64,64).orElseThrow().pixels();
        int at=(3*64+2)*4;assertEquals(9,pixels.get(at+3));assertEquals(31,Byte.toUnsignedInt(pixels.get(at+2)));assertEquals(0,pixels.get(3));
        var reopened=MaterialCanvas.load(directory,"mechanics_dream:craftbot");
        assertEquals(0xffffffff,reopened.pixel(2,3));assertEquals(.12f,reopened.material(0xffffff).emission());
    }
    @Test void fullpackFallbackScansRootsWithoutChangingSourceBytes() throws Exception {
        Path empty=directory.resolve("empty"),root=directory.resolve("mod");Files.createDirectories(empty);
        Path png=root.resolve("koperlib/fullpacks/other_namespace/textures/robot/panel.png");Files.createDirectories(png.getParent());
        var image=new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(1,2,0xffffffff);
        var encoded=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",encoded);byte[] original=encoded.toByteArray();Files.write(png,original);
        assertArrayEquals(original,MaterialCanvas.fullpackAlbedo(List.of(empty,root),"other_namespace:robot/panel").orElseThrow());
        assertArrayEquals(original,Files.readAllBytes(png));
        assertTrue(MaterialCanvas.fullpackAlbedo(List.of(root),"absent:robot").isEmpty());
    }
    @Test void sourceFallbackRejectsTraversalOversizedFilesAndOversizedImageHeaders() throws Exception {
        assertThrows(IllegalArgumentException.class,()->MaterialCanvas.fullpackAlbedo(List.of(directory),"test:../secret"));
        Path png=directory.resolve("koperlib/fullpacks/test/textures/robot.png");Files.createDirectories(png.getParent());Files.write(png,new byte[4*1024*1024+1]);
        assertThrows(java.io.IOException.class,()->MaterialCanvas.fullpackAlbedo(List.of(directory),"test:robot"));
        var image=new java.awt.image.BufferedImage(257,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);var encoded=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",encoded);
        assertThrows(java.io.IOException.class,()->MaterialCanvas.previewPng(encoded.toByteArray()));
    }
    @Test void saveAndLoadRejectPackSymlinksWithoutTouchingOutside() throws Exception {
        Path outside=Files.createDirectory(directory.resolve("outside")),game=Files.createDirectory(directory.resolve("game"));
        Files.createDirectories(game.resolve("resourcepacks"));Files.createSymbolicLink(game.resolve("resourcepacks/greenmarine-development-materials"),outside);
        var canvas=new MaterialCanvas(2,2);
        assertThrows(java.io.IOException.class,()->canvas.save(game,"test:robot"));
        assertThrows(java.io.IOException.class,()->MaterialCanvas.load(game,"test:robot"));
        try(var files=Files.list(outside)){assertEquals(0,files.count());}
    }
    @Test void saveRejectsNestedAncestorSymlinkBeforeAnyMaskReplacement() throws Exception {
        var canvas=new MaterialCanvas(2,2);var saved=canvas.save(directory,"test:robot");byte[] mask=Files.readAllBytes(saved.mask());
        Path outside=Files.createDirectory(directory.resolve("outside")),materials=saved.pack().resolve("assets/greenmarine_dev/greenmarine/materials");
        Files.delete(saved.json());Files.delete(saved.json().getParent());Files.delete(materials.resolve("test"));Files.delete(materials);
        Files.createSymbolicLink(materials,outside);
        assertThrows(java.io.IOException.class,()->canvas.save(directory,"test:robot"));assertArrayEquals(mask,Files.readAllBytes(saved.mask()));
        try(var files=Files.list(outside)){assertEquals(0,files.count());}
    }
    @Test void failedSavePreflightPreservesEarlierMaskMetadataAndOtherTextures() throws Exception {
        var canvas=new MaterialCanvas(2,2);var saved=canvas.save(directory,"test:robot");
        byte[] mask=Files.readAllBytes(saved.mask()),json=Files.readAllBytes(saved.json());
        Path metadata=saved.pack().resolve("pack.mcmeta");Files.writeString(metadata,"owner metadata");
        var other=canvas.save(directory,"test:other");byte[] otherJson=Files.readAllBytes(other.json());
        assertEquals("owner metadata",Files.readString(metadata));
        Files.delete(saved.json());Files.createDirectory(saved.json());Files.writeString(saved.json().resolve("occupied"),"keep");
        canvas.beginStroke();canvas.paintLine(0,0,0,0,0xffffffff,1);canvas.endStroke();
        assertThrows(java.io.IOException.class,()->canvas.save(directory,"test:robot"));
        assertArrayEquals(mask,Files.readAllBytes(saved.mask()));assertEquals("owner metadata",Files.readString(metadata));assertArrayEquals(otherJson,Files.readAllBytes(other.json()));
        Files.delete(saved.json().resolve("occupied"));Files.delete(saved.json());Files.write(saved.json(),json);
        Files.delete(metadata);Files.createDirectory(metadata);
        assertThrows(java.io.IOException.class,()->canvas.save(directory,"test:robot"));
        assertArrayEquals(mask,Files.readAllBytes(saved.mask()));assertArrayEquals(json,Files.readAllBytes(saved.json()));
    }
    @Test void rejectsUnsafeIdentifiersDimensionsAndNonFiniteParameters(){
        assertThrows(IllegalArgumentException.class,()->new MaterialCanvas(257,64));
        assertThrows(IllegalArgumentException.class,()->MaterialCanvas.canonicalTexture("test:../secret"));
        assertThrows(IllegalArgumentException.class,()->new MaterialCanvas.Material("glass",Float.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->new MaterialCanvas.Material("glass",0,2,0));
        assertThrows(IllegalArgumentException.class,()->new MaterialCanvas.Material("unknown",0,0,0));
    }
}
