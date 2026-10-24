package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoReloadWatcherTest {
    @TempDir Path root;
    private Path source() throws Exception {
        Path source=root.resolve("pack/assets/demo/shaders/core/test.fsh");
        Files.createDirectories(source.getParent());Files.writeString(source,"void main(){}");return source;
    }
    @Test void establishesBaselineThenRequiresTwoIdenticalSnapshots() throws Exception {
        Path source=source();var watcher=new AutoReloadWatcher(List.of(root));
        assertFalse(watcher.poll().changed());assertFalse(watcher.poll().changed());
        Files.writeString(source,"void main(){ /* edit */ }");
        assertFalse(watcher.poll().changed());
        var changed=watcher.poll();assertTrue(changed.changed());assertEquals(List.of(source),changed.changedFiles());
        assertFalse(watcher.poll().changed());
    }
    @Test void continuousWritesRestartDebounceAndDeletionIsReported() throws Exception {
        Path source=source();var watcher=new AutoReloadWatcher(List.of(root));watcher.poll();
        Files.writeString(source,"one");assertFalse(watcher.poll().changed());
        Files.writeString(source,"two");assertFalse(watcher.poll().changed());assertTrue(watcher.poll().changed());
        Files.delete(source);assertFalse(watcher.poll().changed());assertEquals(List.of(source),watcher.poll().changedFiles());
        assertFalse(watcher.poll().changed());
    }
    @Test void contentHashFindsEqualSizeEqualTimestampReplacement() throws Exception {
        Path source=source();var watcher=new AutoReloadWatcher(List.of(root));watcher.poll();
        var timestamp=Files.getLastModifiedTime(source);Files.writeString(source,"void main(){ }");
        // Use an exactly equal-length replacement and restore its timestamp.
        Files.writeString(source,"void main(); ");Files.setLastModifiedTime(source,timestamp);
        assertEquals("void main(){}".length(),Files.size(source));
        assertFalse(watcher.poll().changed());assertTrue(watcher.poll().changed());
    }
    @Test void invalidJsonAndPartialPngDoNotPublishAndRecoveryDebounces() throws Exception {
        Path source=source();var watcher=new AutoReloadWatcher(List.of(root));watcher.poll();
        Path json=root.resolve("pack/config.json");Files.writeString(json,"{");
        var invalid=watcher.poll();assertFalse(invalid.changed());assertFalse(invalid.diagnostic().isBlank());
        assertFalse(watcher.poll().changed());Files.writeString(json,"{\"schema\":1}");
        assertFalse(watcher.poll().changed());assertTrue(watcher.poll().changed());
        Path png=root.resolve("pack/mask.png");Files.write(png,new byte[]{1,2,3});
        assertFalse(watcher.poll().changed());assertFalse(watcher.poll().changed());
        Files.delete(png);assertFalse(watcher.poll().changed());
    }
    @Test void skipsHiddenBuildZipAndLinkedEntries() throws Exception {
        Path source=source();var watcher=new AutoReloadWatcher(List.of(root));watcher.poll();
        Files.createDirectories(root.resolve(".hidden"));Files.writeString(root.resolve(".hidden/malicious.json"),"{");
        Files.createDirectories(root.resolve("build"));Files.writeString(root.resolve("build/malicious.json"),"{");
        Files.write(root.resolve("pack.zip"),new byte[]{1});
        Files.createSymbolicLink(root.resolve("linked.json"),source);
        assertFalse(watcher.poll().changed());assertFalse(watcher.poll().changed());
    }
    @Test void oversizedTextAndMalformedUtf8KeepLastSnapshot() throws Exception {
        Path source=source();var watcher=new AutoReloadWatcher(List.of(root));watcher.poll();
        Files.write(source,new byte[262145]);assertFalse(watcher.poll().changed());assertFalse(watcher.poll().diagnostic().isBlank());
        Files.write(source,new byte[]{(byte)0xc3,(byte)0x28});assertFalse(watcher.poll().changed());
        Files.writeString(source,"recovered");assertFalse(watcher.poll().changed());assertTrue(watcher.poll().changed());
    }
    @Test void absentRootCanAppearAndValidMaskCanChange() throws Exception {
        Path absent=root.resolve("later");var watcher=new AutoReloadWatcher(List.of(absent));assertFalse(watcher.poll().changed());
        Files.createDirectory(absent);var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(image,"PNG",absent.resolve("mask.png").toFile());
        assertFalse(watcher.poll().changed());assertTrue(watcher.poll().changed());
    }
    @Test void limitsFileCountAndTotalBytes() throws Exception {
        var watcher=new AutoReloadWatcher(List.of(root));watcher.poll();
        for(int i=0;i<2049;i++)Files.writeString(root.resolve(i+".glsl"),"x");
        assertFalse(watcher.poll().changed());assertTrue(watcher.poll().diagnostic().contains("2048"));
        try(var paths=Files.list(root)){for(Path p:paths.toList())Files.delete(p);}
        byte[] text=new byte[262144];Arrays.fill(text,(byte)' ');
        for(int i=0;i<129;i++)Files.write(root.resolve(i+".glsl"),text);
        assertFalse(watcher.poll().changed());assertTrue(watcher.poll().diagnostic().contains("32 MiB"));
    }
}
