package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackCatalogTest {
    private final String valid = "{\"schema\":1,\"id\":\"demo:pack\",\"passes\":[{\"name\":\"grade\",\"fragment\":\"demo:core/grade\"}]}";
    @Test void failedReloadPreservesLastValidSourcesAndCatalog() {
        var catalog = new PackCatalog();
        assertTrue(catalog.reload(Map.of("demo:manifest", valid), id -> id.equals("demo:shaders/core/grade.fsh") ? "#version 330\nvoid main(){}" : null));
        var previous = catalog.get("demo:pack").orElseThrow();
        assertFalse(catalog.reload(Map.of("demo:manifest", valid), id -> null));
        assertSame(previous, catalog.get("demo:pack").orElseThrow());
        assertEquals(1, catalog.generation());
        assertFalse(catalog.error().isBlank());
    }

    @Test void rejectsMissingScriptDuplicateIdsAndMissingFallbackSource() {
        var catalog = new PackCatalog();
        assertFalse(catalog.reload(Map.of("a",valid,"b",valid), id -> "source"));
        assertFalse(catalog.reload(Map.of("a",valid.replace("\"passes\"", "\"script\":\"demo:absent.lua\",\"passes\"")), id -> id.endsWith(".fsh") ? "source" : null));
        assertFalse(catalog.reload(Map.of("a",valid.replace("\"fragment\":\"demo:core/grade\"", "\"fragment\":\"demo:core/grade\",\"fallback\":\"demo:core/missing\"")), id -> id.contains("missing") ? null : "source"));
    }

    @Test void validatesAndSnapshotsIncludesForPortableAuthoring() {
        var catalog=new PackCatalog();
        assertTrue(catalog.reload(Map.of("p",valid),id -> switch(id) {
            case "demo:shaders/core/grade.fsh" -> "#include <demo:frame.glsl>\nvoid main(){}";
            case "demo:shaders/include/frame.glsl" -> "layout(std140) uniform MarineFrame {vec4 Frame;};";
            default -> null;
        }));
        assertTrue(catalog.get("demo:pack").orElseThrow().sources().containsKey("include|demo:frame.glsl"));
        assertFalse(catalog.reload(Map.of("p",valid),id -> id.endsWith("grade.fsh") ? "#include <demo:missing.glsl>" : null));
    }

    @Test void registeredModPacksHaveOwnedLifetimesAndSurviveResourceReload() throws Exception {
        var catalog=new PackCatalog(); var definition=ShaderPack.parse(com.google.gson.JsonParser.parseString(valid).getAsJsonObject());
        var loaded=new PackCatalog.LoadedPack(definition,"function update(f) end",Map.of("demo:core/grade","void main(){}"));
        try(var lease=catalog.register(loaded)) {
            assertSame(loaded,catalog.get("demo:pack").orElseThrow());
            assertThrows(IllegalArgumentException.class,()->catalog.register(loaded));
            assertTrue(catalog.reload(Map.of(),id -> null));
            assertSame(loaded,catalog.get("demo:pack").orElseThrow());
        }
        assertTrue(catalog.get("demo:pack").isEmpty());
    }
}
