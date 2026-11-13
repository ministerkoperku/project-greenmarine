package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.PackCatalog;
import com.koper.greenmarine.api.pack.PackProgram;
import com.koper.greenmarine.client.UniformAbi;
import com.koper.greenmarine.client.BinaryShaderSource;
import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.SpvModule;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.util.*;

final class WorldShaders implements BinaryShaderSource {
    private final PackCatalog.LoadedPack pack;
    private final boolean sodium;
    private final PackProgram program;
    private final Map<Integer,Integer> outputs;
    private final ShaderProgram graphics;
    private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
    WorldShaders(PackCatalog.LoadedPack pack) {this(pack,false);}
    WorldShaders(PackCatalog.LoadedPack pack,boolean sodium) {this(pack,sodium,null,Map.of());}
    WorldShaders(PackCatalog.LoadedPack pack,boolean sodium,PackProgram program,Map<Integer,Integer> outputs) {
        this(pack,sodium,program,outputs,null);
    }
    WorldShaders(PackCatalog.LoadedPack pack,boolean sodium,PackProgram program,Map<Integer,Integer> outputs,RenderPipeline pipeline) {
        this.pack=pack;this.sodium=sodium;this.program=program;this.outputs=Map.copyOf(outputs);
        if(program!=null && !program.stages().isEmpty()) {
            if(sodium)throw new IllegalArgumentException("Intermediate world stages require a supported native host adapter");
            var stages=new EnumMap<GraphicsStage,ShaderStageSource>(GraphicsStage.class);
            program.stages().forEach((stage,id)->stages.put(stage,new ShaderStageSource(pack.sources().get(PackCatalog.stageKey(stage,id)))));
            graphics=ShaderProgram.fromPack(Objects.requireNonNull(pipeline,"World graphics pipeline"),pack,Map.of()).withStages(stages,program.patchVertices());
        }else graphics=null;
    }
    @Override public ShaderProgram graphicsProgram() {return graphics;}
    @Override public void validateModule(String name,ShaderType type,SpvModule module) {
        validateUniforms(module.spv());
    }
    @Override public void validateIntermediateModule(GraphicsStage stage,java.nio.ByteBuffer module) {validateUniforms(module);}
    private void validateUniforms(java.nio.ByteBuffer module) {
        if(program==null || program.uniforms().isEmpty())return;
        var definitions=new LinkedHashMap<String,com.koper.greenmarine.api.pack.PackUniformBlock>();
        program.uniforms().forEach((binding,resource)->definitions.put(binding,pack.definition().resources().uniforms().get(resource)));
        UniformAbi.validate(module,definitions);
    }
    @Override public String getShader(Identifier id,ShaderType type) {
        var text=pack.sources().get((type==ShaderType.VERTEX?"vertex|":"")+id);
        if(text==null)text=resource(id,"shaders/",type==ShaderType.VERTEX?".vsh":".fsh");
        if(text!=null && sodium)text=SodiumShaderAdapter.shader(text,type);
        if(text!=null && type==ShaderType.FRAGMENT && program!=null && !program.outputs().isEmpty() && !outputs.isEmpty())text=WorldShaderOutputs.remap(text,outputs);
        return text;
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
