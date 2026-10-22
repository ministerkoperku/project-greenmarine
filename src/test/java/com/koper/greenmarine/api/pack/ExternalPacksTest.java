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
    @Test void absentRootIsEmptyAndDoesNotCreateDirectories() throws Exception {
        Path absent = root.resolve("missing");
        assertTrue(ExternalPacks.scan(absent, id -> null, id -> null).isEmpty());
        assertFalse(Files.exists(absent));
    }
}
