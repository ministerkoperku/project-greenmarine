package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.material.MaterialAddons;
import com.koper.greenmarine.api.pack.PackCatalog;
import com.koper.greenmarine.api.pack.ShaderPack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import java.io.*;

/** Mounted resource packs override mod-root fallbacks, which work without Fabric Resource Loader. */
final class ResourceMaterials {
    private ResourceMaterials() {}
    static void reload(ResourceManager resources,List<PackCatalog.LoadedPack> packs) {
        try {
            Function<String,byte[]> binary=id->read(resources,id,4*1024*1024);
            var roots=net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
                .sorted(Comparator.comparing(mod->mod.getMetadata().getId())).flatMap(mod->mod.getRootPaths().stream()).toList();
            var addons=new LinkedHashMap<>(discover(roots,binary));
            for(var entry:resources.listResources("greenmarine/materials",id->id.getPath().endsWith(".json")).entrySet()
                    .stream().sorted(Map.Entry.comparingByKey()).toList()) {
                String id=entry.getKey().toString(),json;
                try{var bytes=read(resources,id,262144);json=bytes==null?"":new String(bytes,StandardCharsets.UTF_8);}
                catch(RuntimeException error){json="";}
                var fallback=addons.get(id);
                addons.put(id,new MaterialAddons.Source(id,json,mask->{var bytes=binary.apply(mask);return bytes!=null?bytes:fallback==null?null:fallback.binaries().apply(mask);},null));
            }
            var sources=sources(addons,packs);
            var errors=MaterialAddons.reload(sources);
            for(var error:errors)GreenMarine.LOGGER.warn("Material addon rejected: {}",error);
            GreenMarine.LOGGER.info("Material addon catalog: sources={}, rejected={}, generation={}",sources.size(),errors.size(),MaterialAddons.generation());
        }catch(RuntimeException error){GreenMarine.LOGGER.warn("Keeping material addons after discovery failure: {}",error.getMessage());}
    }
    static List<MaterialAddons.Source> sources(Map<String,MaterialAddons.Source> discovered,List<PackCatalog.LoadedPack> packs) {
        var owned=new HashSet<String>();
        for(var pack:packs)owned.addAll(pack.definition().materialAddons());
        var result=new ArrayList<MaterialAddons.Source>();
        discovered.forEach((id,source)->{if(!owned.contains(id))result.add(source);});
        for(var pack:packs)result.addAll(pack.materialAddons());
        return List.copyOf(result);
    }
    static Map<String,MaterialAddons.Source> discover(List<Path> roots,Function<String,byte[]> override) {
        var found=new LinkedHashMap<String,MaterialAddons.Source>();
        for(var root:roots) {
            var assets=root.resolve("assets");if(!Files.isDirectory(assets))continue;
            try(var namespaces=Files.list(assets)) {
                for(var namespace:namespaces.sorted().toList()) {
                    var directory=namespace.resolve("greenmarine/materials");if(!Files.isDirectory(directory))continue;
                    try(var paths=Files.walk(directory,8)) {
                        var files=paths.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".json")).sorted().limit(129).toList();
                        if(files.size()>128)throw new IllegalArgumentException("Too many mod material addons");
                        for(var file:files) {
                            String id=namespace.getFileName()+":"+namespace.relativize(file).toString().replace('\\','/');
                            ShaderPack.requireId(id);String json;
                            try{json=new String(read(file,262144),StandardCharsets.UTF_8);}catch(RuntimeException error){json="";}
                            found.put(id,new MaterialAddons.Source(id,json,mask->{
                                ShaderPack.requireId(mask);var data=override.apply(mask);if(data!=null)return data;
                                int colon=mask.indexOf(':');var path=assets.resolve(mask.substring(0,colon)).resolve(mask.substring(colon+1));
                                return Files.isRegularFile(path)?read(path,4*1024*1024):null;
                            },null));
                            if(found.size()>128)throw new IllegalArgumentException("Too many mod material addons");
                        }
                    }
                }
            }catch(IOException error){throw new UncheckedIOException(error);}
        }
        return Collections.unmodifiableMap(found);
    }
    private static byte[] read(Path path,int limit) {
        try(var input=Files.newInputStream(path)){return bounded(input,limit);}
        catch(IOException error){throw new UncheckedIOException(error);}
    }
    private static byte[] read(ResourceManager resources,String id,int limit) {
        var resource=resources.getResource(Identifier.parse(id));if(resource.isEmpty())return null;
        try(var input=resource.get().open()){return bounded(input,limit);}
        catch(IOException error){throw new UncheckedIOException(error);}
    }
    private static byte[] bounded(InputStream input,int limit) throws IOException {
        var data=input.readNBytes(limit+1);if(data.length>limit)throw new IllegalArgumentException("Material resource exceeds "+limit+" bytes");return data;
    }
}
