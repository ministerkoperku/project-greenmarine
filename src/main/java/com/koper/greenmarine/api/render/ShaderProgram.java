package com.koper.greenmarine.api.render;

import com.koper.greenmarine.api.pack.ShaderBinary;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Immutable shader source snapshot with a host raster pipeline declaration. */
public final class ShaderProgram {
    private final RenderPipeline pipeline;
    private final Map<Identifier, String> sources, includes;
    private final Map<Identifier, ShaderBinary> fragments;
    private final Map<Identifier,String> vertexSources;
    private final Map<String, UniformType> bindings;
    public ShaderProgram(RenderPipeline pipeline, Map<Identifier, String> sources,
                         Map<Identifier, String> includes, Map<Identifier, ShaderBinary> fragments) {
        this(pipeline,sources,includes,fragments,Map.of());
    }
    private ShaderProgram(RenderPipeline pipeline,Map<Identifier,String> sources,Map<Identifier,String> includes,Map<Identifier,ShaderBinary> fragments,Map<Identifier,String> vertices) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.vertexSources=Map.copyOf(vertices);
        this.sources = Map.copyOf(sources); this.includes = Map.copyOf(includes); this.fragments = Map.copyOf(fragments);
        for (var stage : pipeline.getShaders().entrySet()) {
            if (stage.getKey() != ShaderType.VERTEX && stage.getKey() != ShaderType.FRAGMENT)
                throw new IllegalArgumentException("Only raster vertex/fragment programs are supported");
            if ((source(stage.getValue(),stage.getKey())==null || source(stage.getValue(),stage.getKey()).isBlank())) throw new IllegalArgumentException("Missing GLSL fallback: " + stage.getValue());
        }
        if (!pipeline.getShaders().containsKey(ShaderType.VERTEX) || !pipeline.getShaders().containsKey(ShaderType.FRAGMENT))
            throw new IllegalArgumentException("Both raster shader stages are required");
        for (var id : fragments.keySet()) if (!id.equals(pipeline.getShaders().get(ShaderType.FRAGMENT)))
            throw new IllegalArgumentException("Binary must correspond to the fragment stage");
        var declarations = new LinkedHashMap<String, UniformType>();
        for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
            if (uniform.type() != UniformType.UNIFORM_BUFFER && uniform.type() != UniformType.COMBINED_IMAGE_SAMPLER)
                throw new IllegalArgumentException("Unsupported material binding: " + uniform.type());
            if (declarations.putIfAbsent(uniform.name(), uniform.type()) != null) throw new IllegalArgumentException("Duplicate binding: " + uniform.name());
        }
        bindings = Collections.unmodifiableMap(declarations);
    }
    /** Reuses a schema-v1 pack snapshot; additional sources normally supply the vertex stage. */
    public static ShaderProgram fromPack(RenderPipeline pipeline, com.koper.greenmarine.api.pack.PackCatalog.LoadedPack pack,
                                         Map<Identifier, String> additionalSources) {
        var sources = new LinkedHashMap<Identifier, String>();
        var includes = new LinkedHashMap<Identifier, String>();
        var binaries = new LinkedHashMap<Identifier, ShaderBinary>();
        var vertices=new LinkedHashMap<Identifier,String>();
        pack.sources().forEach((key, value) -> {
            if (key.startsWith("include|")) includes.put(Identifier.parse(key.substring(8)), value);
            else if(key.startsWith("vertex|")) vertices.put(Identifier.parse(key.substring(7)),value);
            else sources.put(Identifier.parse(key), value);
        });
        sources.putAll(additionalSources);
        additionalSources.keySet().forEach(vertices::remove);
        var fragment = pipeline.getShaders().get(ShaderType.FRAGMENT);
        if (fragment != null && !additionalSources.containsKey(fragment) && pack.binaries().containsKey(fragment.toString()))
            binaries.put(fragment, pack.binaries().get(fragment.toString()));
        return new ShaderProgram(pipeline, sources, includes, binaries,vertices);
    }
    /** Stage-aware lookup also supports matching vertex/fragment resource ids. */
    public String source(Identifier id,ShaderType type) {return type==ShaderType.VERTEX?vertexSources.getOrDefault(id,sources.get(id)):sources.get(id);}
    public RenderPipeline pipeline() { return pipeline; }
    public Map<Identifier, String> sources() { return sources; }
    public Map<Identifier, String> includes() { return includes; }
    public Map<Identifier, ShaderBinary> fragments() { return fragments; }
    public Map<String, UniformType> bindings() { return bindings; }
    public void checkBinding(String name, UniformType type) {
        if (bindings.get(name) != type) throw new IllegalArgumentException("Undeclared or incompatible binding: " + name);
    }
    public void checkComplete(Set<String> names) {
        if (!names.equals(bindings.keySet())) throw new IllegalArgumentException("Material requires bindings: " + bindings.keySet());
    }
}
