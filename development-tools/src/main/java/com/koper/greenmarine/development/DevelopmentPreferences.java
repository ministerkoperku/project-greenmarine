package com.koper.greenmarine.development;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

record DevelopmentPreferences(boolean autoReload, boolean verboseDebug, String slangCompiler) {
    static DevelopmentPreferences defaults() { return new DevelopmentPreferences(false, false, ""); }
    static DevelopmentPreferences load(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 4096) return defaults();
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new DevelopmentPreferences(json.has("autoReload") && json.get("autoReload").getAsBoolean(),
                json.has("verboseDebug") && json.get("verboseDebug").getAsBoolean(),
                json.has("slangCompiler") ? json.get("slangCompiler").getAsString() : "");
        } catch (Exception e) { return defaults(); }
    }
    void save(Path file) throws IOException {
        if (slangCompiler == null || slangCompiler.length() > 2048) throw new IOException("Compiler path is too long");
        Files.createDirectories(file.getParent());
        var json = new JsonObject(); json.addProperty("autoReload", autoReload); json.addProperty("verboseDebug", verboseDebug); json.addProperty("slangCompiler", slangCompiler);
        Path temporary = Files.createTempFile(file.getParent(), ".greenmarine-development-", ".json");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
