package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalPacksTest {
    @TempDir Path root;
    private static final String MANIFEST = """
        {"schema":1,"id":"demo:external","passes":[{"name":"color","fragment":"demo:core/color"}]}
        """;
    private Path folder() throws Exception {
        Path pack = Files.createDirectories(root.resolve("editable/assets/demo/greenmarine/packs"));
        Files.writeString(pack.resolve("main.json"), MANIFEST);
        Path shaders = Files.createDirectories(root.resolve("editable/assets/demo/shaders/core"));
        Files.writeString(shaders.resolve("color.fsh"), "external");
        return root.resolve("editable");
    }
    private void zip(String name, String... entries) throws Exception {
        try (var output = new ZipOutputStream(Files.newOutputStream(root.resolve(name)))) {
            for (int i=0; i<entries.length; i+=2) {
                output.putNextEntry(new ZipEntry(entries[i])); output.write(entries[i+1].getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
    }
    @Test void directoryAndZipProduceEquivalentIsolatedSnapshots() throws Exception {
        folder();
        zip("packed.zip", "assets/demo/greenmarine/packs/main.json", MANIFEST,
            "assets/demo/shaders/core/color.fsh", "external");
        var sources = ExternalPacks.scan(root, id -> "fallback", id -> null);
        assertEquals(2, sources.size());
        for (var source : sources) {
            var catalog = new PackCatalog();
            assertTrue(catalog.reload(java.util.List.of(source)), catalog.error());
            assertEquals("external", catalog.get("demo:external").orElseThrow().sources().get("demo:core/color"));
            assertEquals("fallback", source.resources().apply("minecraft:shaders/include/fog.glsl"));
        }
        Files.writeString(root.resolve("editable/assets/demo/shaders/core/color.fsh"), "changed");
        assertEquals("external", sources.getFirst().resources().apply("demo:shaders/core/color.fsh"));
    }
    @Test void rejectsUnsafeZipPaths() throws Exception {
        zip("unsafe.zip", "../assets/demo/shaders/core/color.fsh", "bad");
        assertThrows(IllegalArgumentException.class, () -> ExternalPacks.scan(root, id -> null, id -> null));
    }
    @Test void rejectsLinkedResources() throws Exception {
        folder();
        Files.createSymbolicLink(root.resolve("editable/assets/demo/shaders/core/linked.fsh"), root.resolve("editable/assets/demo/shaders/core/color.fsh"));
        assertThrows(IllegalArgumentException.class, () -> ExternalPacks.scan(root, id -> null, id -> null));
    }
    @Test void rejectsExcessiveInflatedResource() throws Exception {
        zip("oversize.zip", "assets/demo/shaders/core/color.fsh", "x".repeat(262_145));
        assertThrows(IllegalArgumentException.class, () -> ExternalPacks.scan(root, id -> null, id -> null));
    }

    @Test void acceptsConvertedWorkspaceEntriesWithoutLoadingArchivedSources() throws Exception {
        Path pack=folder();
        Path originals=Files.createDirectories(pack.resolve("iris-source/shaders"));
        for(int i=0;i<2100;i++)Files.writeString(originals.resolve("source"+i+".glsl"),"original");
        // Preserved originals may be much larger than active shader resources.
        Files.write(originals.resolve("moon.png"),new byte[300_000]);
        var sources=ExternalPacks.scan(root,id -> null,id -> null);
        assertEquals(1,sources.size());
        assertEquals("external",sources.getFirst().resources().apply("demo:shaders/core/color.fsh"));
        assertNull(sources.getFirst().resources().apply("demo:iris-source/shaders/source0.glsl"));
    }

    @Test void directoryAssetsCanExceedTheOldEightMiBBudget() throws Exception {
        Path shaders=folder().resolve("assets/demo/shaders/core");
        byte[] block=new byte[262_144];
        for(int i=0;i<65;i++)Files.write(shaders.resolve("stage"+i+".fsh"),block);
        var source=ExternalPacks.scan(root,id -> null,id -> null).getFirst();
        assertEquals(262_144,source.binaries().apply("demo:shaders/core/stage64.fsh").length);
    }

    @Test void zipEntryBudgetHasAnEnforcedBoundary() throws Exception {
        String[] entries=new String[8192];
        for(int i=0;i<4096;i++) {entries[2*i]="preserved/file"+i;entries[2*i+1]="";}
        zip("entries.zip",entries);
        assertTrue(ExternalPacks.scan(root,id -> null,id -> null).isEmpty());
        Files.delete(root.resolve("entries.zip"));
        String[] excessive=java.util.Arrays.copyOf(entries,8194);
        excessive[8192]="preserved/overflow";excessive[8193]="";
        zip("entries.zip",excessive);
        var error=assertThrows(IllegalArgumentException.class,() -> ExternalPacks.scan(root,id -> null,id -> null));
        assertTrue(error.getMessage().contains("file limit"),error.getMessage());
    }

    @Test void zipAssetBudgetsApplyToInflatedBytesPerContainerAndAcrossScan() throws Exception {
        sizedZip("a.zip",32*1024*1024);
        assertEquals(1,ExternalPacks.scan(root,id -> null,id -> null).size());
        sizedZip("b.zip",32*1024*1024);
        sizedZip("c.zip",32*1024*1024);
        sizedZip("d.zip",32*1024*1024);
        assertEquals(4,ExternalPacks.scan(root,id -> null,id -> null).size());
        zip("e.zip","assets/demo/greenmarine/packs/main.json",MANIFEST);
        var scanError=assertThrows(IllegalArgumentException.class,() -> ExternalPacks.scan(root,id -> null,id -> null));
        assertTrue(scanError.getMessage().contains("scan"),scanError.getMessage());
        Files.delete(root.resolve("e.zip"));
        Files.delete(root.resolve("b.zip"));
        Files.delete(root.resolve("c.zip"));
        Files.delete(root.resolve("d.zip"));
        sizedZip("overflow.zip",32*1024*1024+1);
        var packError=assertThrows(IllegalArgumentException.class,() -> ExternalPacks.scan(root,id -> null,id -> null));
        assertTrue(packError.getMessage().contains("Pack snapshot"),packError.getMessage());
    }

    private void sizedZip(String name,int assetBytes) throws Exception {
        byte[] manifest=MANIFEST.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] block=new byte[262_144];
        try(var output=new ZipOutputStream(Files.newOutputStream(root.resolve(name)))) {
            output.putNextEntry(new ZipEntry("assets/demo/greenmarine/packs/main.json"));
            output.write(manifest);output.closeEntry();
            for(int remaining=assetBytes-manifest.length,i=0;remaining>0;i++) {
                int size=Math.min(remaining,block.length);
                output.putNextEntry(new ZipEntry("assets/demo/shaders/core/stage"+i+".fsh"));
                output.write(block,0,size);output.closeEntry();remaining-=size;
            }
        }
    }
    @Test void absentRootIsEmptyAndDoesNotCreateDirectories() throws Exception {
        Path absent = root.resolve("missing");
        assertTrue(ExternalPacks.scan(absent, id -> null, id -> null).isEmpty());
        assertFalse(Files.exists(absent));
    }
}
