package com.koper.greenmarine.api.pack;

import com.google.gson.JsonParser;
import com.koper.greenmarine.api.LightRegistry;
import com.koper.greenmarine.script.PackScript;
import java.util.*;
import java.util.function.Function;

public final class PackCatalog {
    public record LoadedPack(ShaderPack definition, String script, Map<String, String> sources,Map<String,ShaderBinary> binaries) {
        public LoadedPack { sources = Map.copyOf(sources); binaries=Map.copyOf(binaries); }
        public LoadedPack(ShaderPack definition,String script,Map<String,String> sources) { this(definition,script,sources,Map.of()); }
    }
    private Map<String, LoadedPack> snapshot = Map.of();
    private final Map<String,LoadedPack> registered=new LinkedHashMap<>();
    private long generation;
    private String error = "";

    public synchronized boolean reload(Map<String, String> manifests, Function<String, String> resources) {
        return reload(manifests,resources,id->null);
    }
    public synchronized boolean reload(Map<String,String> manifests,Function<String,String> resources,Function<String,byte[]> binaryResources) {
        var staging = new LinkedHashMap<String, LoadedPack>();
        try {
            if (manifests.size() > 64) throw new IllegalArgumentException("At most 64 shaderpacks are supported");
            for (String source : manifests.values()) {
                if (source.length() > 65_536) throw new IllegalArgumentException("Pack manifest exceeds 64 KiB");
                var pack = ShaderPack.parse(JsonParser.parseString(source).getAsJsonObject());
                var shaders = new LinkedHashMap<String, String>();
                var binaries=new LinkedHashMap<String,ShaderBinary>();
                for (var pass : pack.passes()) {
                    shaders.put(pass.fragment(), require(resources, shaderResource(pass.fragment()), 262_144));
                    if (pass.fallback() != null) shaders.put(pass.fallback(), require(resources, shaderResource(pass.fallback()), 262_144));
                    if(pass.spirv()!=null) {
                        var binary=new ShaderBinary(binaryResources.apply(pass.spirv()));
                        if(binaries.putIfAbsent(pass.fragment(),binary)!=null) throw new IllegalArgumentException("Duplicate SPIR-V fragment mapping");
                    }
                }
                for(var program:pack.programs()) {
                    shaders.put(program.fragment(),require(resources,shaderResource(program.fragment()),262_144));
                    if(program.surface()!=null) shaders.put(program.surface(),require(resources,shaderResource(program.surface()),262_144));
                    if(program.vertex()!=null) shaders.put("vertex|"+program.vertex(),require(resources,
                        shaderResource(program.vertex()).replaceFirst("\\.fsh$",".vsh"),262_144));
                }
                for(String shader:new ArrayList<>(shaders.values())) loadIncludes(shader,shaders,resources,new HashSet<>(),0);
                String script = pack.script() == null ? "function update(frame) end" : require(resources, pack.script(), 65_536);
                try (var validation = new PackScript(pack, script, new LightRegistry())) { /* Validate compilation and initialization before publishing. */ }
                if (staging.putIfAbsent(pack.id(), new LoadedPack(pack, script, shaders,binaries)) != null) {
                    throw new IllegalArgumentException("Duplicate shaderpack: " + pack.id());
                }
            }
            snapshot = Collections.unmodifiableMap(staging); generation++; error = ""; return true;
        } catch (RuntimeException exception) {
            error = exception.getMessage(); return false;
        }
    }

    private static void loadIncludes(String text,Map<String,String> sources,Function<String,String> resources,Set<String> visiting,int depth) {
        if(depth>16 || sources.size()>80) throw new IllegalArgumentException("Shader include limit exceeded");
        var matcher=java.util.regex.Pattern.compile("(?m)^\\s*#include\\s*[<\"]([^>\"]+)[>\"]").matcher(text);
        while(matcher.find()) {
            String id=matcher.group(1); ShaderPack.requireId(id);
            if(!visiting.add(id)) throw new IllegalArgumentException("Cyclic shader include: "+id);
            String key="include|"+id;
            if(!sources.containsKey(key)) {
                int colon=id.indexOf(':');
                String included=require(resources,id.substring(0,colon+1)+"shaders/include/"+id.substring(colon+1),262_144);
                sources.put(key,included); loadIncludes(included,sources,resources,visiting,depth+1);
            }
            visiting.remove(id);
        }
    }

    public synchronized AutoCloseable register(LoadedPack pack) {
        Objects.requireNonNull(pack);
        if(registered.containsKey(pack.definition().id()) || registered.size()>=64) throw new IllegalArgumentException("Duplicate registered pack or registration limit exceeded");
        for(var pass:pack.definition().passes()) {
            if(!pack.sources().containsKey(pass.fragment()) || (pass.fallback()!=null && !pack.sources().containsKey(pass.fallback()))) throw new IllegalArgumentException("Registered pack shader source is missing");
        }
        for(var program:pack.definition().programs()) if(!pack.sources().containsKey(program.fragment())
            || (program.surface()!=null && !pack.sources().containsKey(program.surface()))
            || (program.vertex()!=null && !pack.sources().containsKey("vertex|"+program.vertex())))
            throw new IllegalArgumentException("Registered world program source is missing");
        try(var validation=new PackScript(pack.definition(),pack.script(),new LightRegistry())) { /* Validate the owned script before registration. */ }
        registered.put(pack.definition().id(),pack); generation++;
        return () -> { synchronized(PackCatalog.this) { if(registered.remove(pack.definition().id(),pack)) generation++; } };
    }

    private static String require(Function<String,String> resources, String id, int max) {
        String text = resources.apply(id);
        if (text == null || text.length() > max) throw new IllegalArgumentException("Missing or excessive shaderpack resource: " + id);
        return text;
    }

    public static String shaderResource(String shader) {
        requireId(shader);
        int colon = shader.indexOf(':');
        return shader.substring(0, colon + 1) + "shaders/" + shader.substring(colon + 1) + ".fsh";
    }
    private static void requireId(String id) { ShaderPack.requireId(id); }
    public synchronized Optional<LoadedPack> get(String id) { return Optional.ofNullable(registered.containsKey(id)?registered.get(id):snapshot.get(id)); }
    public synchronized List<LoadedPack> packs() { var all=new LinkedHashMap<>(snapshot); all.putAll(registered); return List.copyOf(all.values()); }
    public synchronized long generation() { return generation; }
    public synchronized String error() { return error; }
}
