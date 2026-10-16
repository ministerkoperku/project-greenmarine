package com.koper.greenmarine.client;

import com.google.gson.JsonParser;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.EffectDefinition;
import com.koper.greenmarine.api.EffectRegistry;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceEffects {
    private static final EffectRegistry REGISTRY = new EffectRegistry();
    private ResourceEffects() {}

    public static void reload(ResourceManager resources) {
        REGISTRY.beginReload();
        boolean success = true;
        for (var entry : resources.listResources("greenmarine/effects", id -> id.getPath().endsWith(".json")).entrySet()) {
            try (var reader = entry.getValue().openAsReader()) {
                REGISTRY.stage(EffectDefinition.fromJson(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception error) {
                success = false;
                GreenMarine.LOGGER.warn("Invalid GreenMarine resource {}: {}", entry.getKey(), error.getMessage());
            }
        }
        REGISTRY.finishReload(success);
        GreenMarine.LOGGER.info("GreenMarine resource effects: {}, reloadHealthy={}", REGISTRY.size(), success);
    }

    public static EffectDefinition selected(String id) {
        return GreenMarine.EFFECTS.get(id).orElseGet(() -> REGISTRY.get(id).orElse(null));
    }
}
