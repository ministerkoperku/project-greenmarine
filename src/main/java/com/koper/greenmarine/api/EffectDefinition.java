package com.koper.greenmarine.api;

import com.google.gson.JsonObject;

public record EffectDefinition(String id, int color, float radius, float intensity) {
    public EffectDefinition {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Effect id must be namespaced");
        }
        if (intensity > 16) throw new IllegalArgumentException("Effect intensity must be within 0..16");
        new Light(0, 0, 0, (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f,
            (color & 255) / 255f, radius, intensity);
    }

    public static EffectDefinition fromJson(JsonObject json) {
        try {
            String id = json.get("id").getAsString();
            String hex = json.has("color") ? json.get("color").getAsString() : "#33ff99";
            if (!hex.matches("#[a-fA-F0-9]{6}")) throw new IllegalArgumentException("Use #RRGGBB for effect color");
            return new EffectDefinition(id, Integer.parseInt(hex.substring(1), 16),
                json.has("radius") ? json.get("radius").getAsFloat() : 4,
                json.has("intensity") ? json.get("intensity").getAsFloat() : 1);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid GreenMarine effect definition", error);
        }
    }
}
