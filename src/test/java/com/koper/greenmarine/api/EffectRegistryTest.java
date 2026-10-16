package com.koper.greenmarine.api;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectRegistryTest {
    private EffectDefinition effect(float intensity) {
        return EffectDefinition.fromJson(JsonParser.parseString("""
            {"id":"demo:glow","color":"#00ff80","intensity":%s,"radius":4}
            """.formatted(intensity)).getAsJsonObject());
    }

    @Test void failedReloadPreservesLastGoodDefinitions() {
        var registry = new EffectRegistry();
        registry.beginReload(); registry.stage(effect(1)); registry.finishReload(true);
        registry.beginReload(); registry.stage(effect(2)); registry.finishReload(false);
        assertEquals(1, registry.get("demo:glow").orElseThrow().intensity());
        registry.beginReload(); registry.stage(effect(3)); registry.finishReload(true);
        assertEquals(3, registry.get("demo:glow").orElseThrow().intensity());
    }

    @Test void duplicateDefinitionRejectsTheWholeReload() {
        var registry = new EffectRegistry();
        registry.beginReload(); registry.stage(effect(1)); registry.finishReload(true);
        registry.beginReload(); registry.stage(effect(2));
        assertThrows(IllegalArgumentException.class, () -> registry.stage(effect(3)));
        registry.finishReload(true);
        assertEquals(1, registry.get("demo:glow").orElseThrow().intensity());
    }

    @Test void rejectsMalformedAuthoringInput() {
        assertThrows(IllegalArgumentException.class, () -> EffectDefinition.fromJson(JsonParser.parseString(
            "{\"id\":\"bad id\",\"color\":\"#00ff80\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> EffectDefinition.fromJson(JsonParser.parseString(
            "{\"id\":\"demo:glow\",\"color\":\"oops\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> effect(-1));
    }

    @Test void rendererIntensityLimitIsValidatedAtAuthoringBoundary() {
        assertEquals(16, effect(16).intensity());
        assertThrows(IllegalArgumentException.class, () -> effect(17));
    }

    @Test void invalidIntensityCannotReplaceAValidReload() {
        var registry = new EffectRegistry();
        registry.beginReload(); registry.stage(effect(1)); registry.finishReload(true);
        registry.beginReload();
        assertThrows(IllegalArgumentException.class, () -> registry.stage(effect(17)));
        registry.finishReload(false);
        assertEquals(1, registry.get("demo:glow").orElseThrow().intensity());
    }
}
