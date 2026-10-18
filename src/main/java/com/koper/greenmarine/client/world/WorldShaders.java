package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.PackCatalog;
import com.koper.greenmarine.client.BinaryShaderSource;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.SpvModule;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.util.*;

final class WorldShaders implements BinaryShaderSource {
    private final PackCatalog.LoadedPack pack;
    private final boolean sodium;
    private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
    WorldShaders(PackCatalog.LoadedPack pack) {this(pack,false);}
    WorldShaders(PackCatalog.LoadedPack pack,boolean sodium) {this.pack=pack;this.sodium=sodium;}
    @Override public String getShader(Identifier id,ShaderType type) {
        var text=pack.sources().get((type==ShaderType.VERTEX?"vertex|":"")+id);
        if(text==null)text=resource(id,"shaders/",type==ShaderType.VERTEX?".vsh":".fsh");
        return text!=null && sodium?SodiumShaderAdapter.shader(text,type):text;
    }
    @Override public CachedIncludeSource getInclude(Identifier id) {
        return includes.computeIfAbsent(id,key->{
            var text=pack.sources().get("include|"+key);
            if(text==null) text=resource(key,"shaders/include/","");
            if(text!=null && sodium)text=SodiumShaderAdapter.include(text);
            return text==null?CachedIncludeSource.createError("Missing world shader include: "+key):CachedIncludeSource.create(key,text);
        });
    }
    private String resource(Identifier id,String prefix,String suffix) {
        var resource=Minecraft.getInstance().getResourceManager().getResource(id.withPath(prefix+id.getPath()+suffix));
        if(resource.isEmpty()) return null;
        try(var reader=resource.get().openAsReader()) {var text=new StringBuilder();char[] buffer=new char[4096];int n;while((n=reader.read(buffer))>=0)text.append(buffer,0,n);return text.toString();}
        catch(java.io.IOException error) {throw new IllegalArgumentException("Unable to read world shader "+id,error);}
    }
    @Override public SpvModule rawModule(String name,ShaderType type) {return null;}
    @Override public void close() {includes.values().forEach(CachedIncludeSource::close);includes.clear();}
}
