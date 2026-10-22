package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PackSourceTest {
    private static String manifest(String id) {
        return """
            {"schema":1,"id":"%s","passes":[
              {"name":"first","fragment":"demo:core/color"},
              {"name":"second","fragment":"demo:core/color"}]}
            """.formatted(id);
    }
    @Test void resourcesAreIsolatedAndRepeatedReferencesAreReadOnce() {
        var count = new AtomicInteger();
        var catalog = new PackCatalog();
        assertTrue(catalog.reload(List.of(
            new PackSource("first.zip", manifest("demo:first"), id -> {count.incrementAndGet(); return "first";}, id -> null),
            new PackSource("second.zip", manifest("demo:second"), id -> "second", id -> null))));
        assertEquals("first", catalog.get("demo:first").orElseThrow().sources().get("demo:core/color"));
        assertEquals("second", catalog.get("demo:second").orElseThrow().sources().get("demo:core/color"));
        assertEquals(1, count.get());
    }
    @Test void failingSourceNamesItsOriginAndRetainsCatalog() {
        var catalog = new PackCatalog();
        assertTrue(catalog.reload(List.of(new PackSource("valid", manifest("demo:valid"), id -> "shader", id -> null))));
        long generation = catalog.generation();
        assertFalse(catalog.reload(List.of(new PackSource("broken.zip", manifest("demo:bad"), id -> null, id -> null))));
        assertTrue(catalog.error().contains("broken.zip"), catalog.error());
        assertEquals(generation, catalog.generation());
        assertTrue(catalog.get("demo:valid").isPresent());
        assertTrue(catalog.reload(List.of()));
        assertTrue(catalog.packs().isEmpty());
    }
}
