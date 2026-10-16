package com.koper.greenmarine.client;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GreenResourcesTest {
    @TempDir Path directory;

    @Test void splitDevRootsExposeAssetsWithoutFabricResourceLoader() throws Exception {
        SharedConstants.tryDetectVersion();
        Path classes = Files.createDirectory(directory.resolve("classes"));
        Path resources = Files.createDirectory(directory.resolve("resources"));
        Path payload = resources.resolve("assets/greenmarine/test.bin");
        Files.createDirectories(payload.getParent());
        Files.write(payload, new byte[]{1, 2, 3});
        var pack = GreenResources.createPack(List.of(classes, resources));
        assertTrue(pack.isRequired());
        try (var opened = pack.open()) {
            var resource = opened.findFirst().orElseThrow();
            try (resource; var stream = resource.getResource(PackType.CLIENT_RESOURCES,
                    Identifier.fromNamespaceAndPath("greenmarine", "test.bin")).get()) {
                assertArrayEquals(new byte[]{1, 2, 3}, stream.readAllBytes());
            }
        }
    }
}
