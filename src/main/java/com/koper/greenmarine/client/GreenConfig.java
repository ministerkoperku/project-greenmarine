package com.koper.greenmarine.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.greenmarine.GreenMarine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record GreenConfig(boolean enabled, String effectId, int color, float intensity, float bloomThreshold,
                          float bloomStrength, float bloomRadius, float pulseSpeed, float radius) {
    public static final String DEFAULT_JSON = """
        {
          "enabled": true,
          "color": "#33ff99",
          "intensity": 1.5,
          "radius": 4.0,
          "bloomThreshold": 0.65,
          "bloomStrength": 0.0,
          "bloomRadius": 2.0,
          "pulseSpeed": 1.0
        }
        """;

    public GreenConfig {
        if (effectId == null || !effectId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Selected effect id must be namespaced");
        }
        require(intensity, 0, 16, "intensity");
        require(bloomThreshold, 0, 16, "bloomThreshold");
        require(bloomStrength, 0, 4, "bloomStrength");
        require(bloomRadius, 0.1f, 16, "bloomRadius");
        require(pulseSpeed, 0, 16, "pulseSpeed");
        require(radius, 0.1f, 64, "radius");
    }

    public GreenConfig(boolean enabled, String effectId, int color, float intensity, float bloomThreshold,
                       float bloomStrength, float bloomRadius, float pulseSpeed) {
        this(enabled, effectId, color, intensity, bloomThreshold, bloomStrength, bloomRadius, pulseSpeed, 4);
    }

    public static GreenConfig parse(JsonObject json) {
        try {
            String hex = json.has("color") ? json.get("color").getAsString() : "#33ff99";
            if (!hex.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Use #RRGGBB for color");
            return new GreenConfig(!json.has("enabled") || json.get("enabled").getAsBoolean(),
                json.has("effect") ? json.get("effect").getAsString() : "greenmarine:config",
                Integer.parseInt(hex.substring(1), 16), number(json, "intensity", 1.5f),
                number(json, "bloomThreshold", 0.65f), number(json, "bloomStrength", 0),
                number(json, "bloomRadius", 2), number(json, "pulseSpeed", 1), number(json, "radius", 4));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid GreenMarine configuration", error);
        }
    }

    public static GreenConfig load(Path path, GreenConfig previous) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT_JSON);
            }
            return parse(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
        } catch (IOException | RuntimeException error) {
            GreenMarine.LOGGER.warn("Keeping previous GreenMarine settings: {}", error.getMessage());
            return previous;
        }
    }

    public static GreenConfig defaults() { return parse(new JsonObject()); }
    private static float number(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }
    private static void require(float value, float minimum, float maximum, String name) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and within " + minimum + ".." + maximum);
        }
    }
}
