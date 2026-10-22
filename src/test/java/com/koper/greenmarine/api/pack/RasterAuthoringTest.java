package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class RasterAuthoringTest {
    @TempDir Path root;
    private static final Path HOST = Path.of(System.getProperty("user.home"), ".gradle/caches/fabric-loom/26.3/minecraft-client.jar");
    private static final Path ASSETS = Path.of("src/main/resources/assets");

    private String read(String id) {
        String path = id.replace(':', '/');
        try {
            Path local = ASSETS.resolve(path);
            if (Files.isRegularFile(local)) return Files.readString(local);
            try (var zip = new ZipFile(HOST.toFile())) {
                var entry = zip.getEntry("assets/" + path);
                if (entry == null) return null;
                try (var input = zip.getInputStream(entry)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            }
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    @Test void actualStarterAndPackagedZipLoadAlongsideBuiltinUsingRealIncludes() throws Exception {
        assumeTrue(Files.isRegularFile(HOST), "Cached game includes required for authoring integration");
        Path directoryRoot = Files.createDirectory(root.resolve("directories"));
        Path editable = directoryRoot.resolve("ocean");
        var init = new ProcessBuilder("python3", "tools/greenmarine-pack.py", "init", editable.toString(), "--id", "demo:ocean")
            .redirectErrorStream(true).start();
        String output = new String(init.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, init.waitFor(), output);
        var builtin = new PackSource("builtin", read("greenmarine:greenmarine/packs/raster.json"), this::read, id -> null);
        var catalog = new PackCatalog();
        var sources = new ArrayList<>(ExternalPacks.scan(directoryRoot, this::read, id -> null));
        sources.add(builtin);
        assertTrue(catalog.reload(sources), catalog.error());
        var starter = catalog.get("demo:ocean").orElseThrow();
        assertFalse(starter.definition().programs().stream().anyMatch(PackProgram::worldRays));
        assertFalse(starter.sources().get("demo:core/terrain").contains("RayLighting"));
        assertTrue(starter.sources().containsKey("include|minecraft:fog.glsl"));

        Path zipRoot = Files.createDirectory(root.resolve("archives"));
        var pack = new ProcessBuilder("python3", "tools/greenmarine-pack.py", "package", editable.toString(), zipRoot.resolve("ocean.zip").toString())
            .redirectErrorStream(true).start();
        output = new String(pack.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, pack.waitFor(), output);
        assertTrue(catalog.reload(ExternalPacks.scan(zipRoot, this::read, id -> null)), catalog.error());
        assertEquals(starter, catalog.get("demo:ocean").orElseThrow());
        assertTrue(catalog.get("greenmarine:raster").isEmpty());
    }
}
