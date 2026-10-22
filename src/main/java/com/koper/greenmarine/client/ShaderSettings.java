package com.koper.greenmarine.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

public record ShaderSettings(String pack, Quality quality, RayMode rays, boolean profiling,
                             Map<String,Map<String,Float>> parameters) {
    public ShaderSettings(String pack,Quality quality,RayMode rays,boolean profiling) {
        this(pack,quality,rays,profiling,Map.of());
    }
    public ShaderSettings {
        ShaderPack.requireId(pack); java.util.Objects.requireNonNull(quality); java.util.Objects.requireNonNull(rays);
        if(parameters.size()>64) throw new IllegalArgumentException("Parameter pack limit exceeded");
        var copy=new LinkedHashMap<String,Map<String,Float>>();
        parameters.forEach((id,values)-> {
            ShaderPack.requireId(id);
            if(values.size()>32) throw new IllegalArgumentException("Parameter limit exceeded");
            values.forEach((name,value)-> {
                if(!name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,47}") || !Float.isFinite(value)) throw new IllegalArgumentException("Invalid parameter override");
            });
            copy.put(id,Map.copyOf(values));
        });
        parameters=Map.copyOf(copy);
    }
    /** Diagnostics and value-only edits do not retire live programs, scripts or ray scenes. */
    public boolean requiresRendererRebuild(ShaderSettings previous) {
        return !pack.equals(previous.pack()) || quality!=previous.quality() || rays!=previous.rays();
    }
    public Map<String,Float> overridesFor(ShaderPack definition) {
        var stored=parameters.getOrDefault(definition.id(),Map.of()); var result=new LinkedHashMap<String,Float>();
        for(var parameter:definition.parameters()) if(stored.containsKey(parameter.name())) {
            result.put(parameter.name(),Math.clamp(stored.get(parameter.name()),parameter.min(),parameter.max()));
        }
        return Map.copyOf(result);
    }
    public ShaderSettings withParameter(String id,String name,float value) {
        var packs=new LinkedHashMap<>(parameters); var values=new LinkedHashMap<>(packs.getOrDefault(id,Map.of()));
        values.put(name,value); packs.put(id,values); return new ShaderSettings(pack,quality,rays,profiling,packs);
    }
    public ShaderSettings withParameter(ShaderPack definition,String name,float value) {
        var parameter=definition.parameters().stream().filter(p->p.name().equals(name)).findFirst().orElseThrow(
            ()->new IllegalArgumentException("Parameter was removed by resource reload"));
        parameter.validate(value);
        var packs=new LinkedHashMap<>(parameters);var values=new LinkedHashMap<>(overridesFor(definition));
        values.put(name,value);packs.put(definition.id(),values);
        return new ShaderSettings(pack,quality,rays,profiling,packs);
    }
    public ShaderSettings withoutParameters(String id) {
        var packs=new LinkedHashMap<>(parameters); packs.remove(id); return new ShaderSettings(pack,quality,rays,profiling,packs);
    }
    static final java.util.Set<String> RETIRED_PACKS=java.util.Set.of("greenmarine:advanced","greenmarine:aurora_rt","greenmarine:portable","greenmarine:radiance");
    public static ShaderSettings defaults() { return new ShaderSettings("greenmarine:raster", Quality.MEDIUM, RayMode.OFF, false); }
    public static ShaderSettings load(Path path, ShaderSettings previous) {
        try {
            if (!Files.exists(path)) previous.save(path);
            if(Files.size(path)>1_048_576) throw new IllegalArgumentException("Settings file exceeds limit");
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            var parameters=new LinkedHashMap<String,Map<String,Float>>();
            if(json.has("parameters")) for(var entry:json.getAsJsonObject("parameters").entrySet()) {
                var values=new LinkedHashMap<String,Float>();
                for(var value:entry.getValue().getAsJsonObject().entrySet()) values.put(value.getKey(),value.getValue().getAsFloat());
                parameters.put(entry.getKey(),values);
            }
            String pack=json.get("pack").getAsString();
            // Demo packs left the release jar in 0.9.4; their saved selection becomes the default pack.
            if(RETIRED_PACKS.contains(pack)) pack=defaults().pack();
            return new ShaderSettings(pack, Quality.valueOf(json.get("quality").getAsString().toUpperCase(Locale.ROOT)),
                RayMode.valueOf(json.get("rays").getAsString().toUpperCase(Locale.ROOT)), json.has("profiling") && json.get("profiling").getAsBoolean(),parameters);
        } catch (IOException | RuntimeException error) {
            GreenMarine.LOGGER.warn("Keeping previous GreenMarine shader settings: {}", error.getMessage()); return previous;
        }
    }
    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        var json = new JsonObject(); json.addProperty("pack", pack); json.addProperty("quality", quality.name().toLowerCase(Locale.ROOT));
        json.addProperty("rays", rays.name().toLowerCase(Locale.ROOT)); json.addProperty("profiling", profiling);
        json.add("parameters",new com.google.gson.Gson().toJsonTree(parameters));
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "greenmarine-settings-", ".tmp");
        try {
            Files.writeString(temp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
