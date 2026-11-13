package com.koper.greenmarine.api.pack;

import java.util.*;
import com.koper.greenmarine.api.render.GraphicsStage;

/** Replaces a vanilla world shader family while preserving its host drawing state. */
public record PackProgram(Target target, String vertex, String fragment, boolean worldRays, String surface, boolean surfaceOnly, boolean voxelShadows, boolean surfaceBeforeTerrain, Map<Integer,String> outputs, Map<String,String> inputs, Map<String,String> uniforms, Set<String> pipelines, Map<GraphicsStage,String> stages, int patchVertices) {
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface,boolean surfaceOnly,boolean voxelShadows,boolean surfaceBeforeTerrain,Map<Integer,String> outputs,Map<String,String> inputs,Map<String,String> uniforms,Set<String> pipelines) {
        this(target,vertex,fragment,worldRays,surface,surfaceOnly,voxelShadows,surfaceBeforeTerrain,outputs,inputs,uniforms,pipelines,Map.of(),0);
    }
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface,boolean surfaceOnly,boolean voxelShadows,boolean surfaceBeforeTerrain,Map<Integer,String> outputs,Map<String,String> inputs,Map<String,String> uniforms) {
        this(target,vertex,fragment,worldRays,surface,surfaceOnly,voxelShadows,surfaceBeforeTerrain,outputs,inputs,uniforms,Set.of());
    }
    /** Retains the original world shader constructor; named attachments are opt-in. */
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface,boolean surfaceOnly,boolean voxelShadows,boolean surfaceBeforeTerrain) {
        this(target,vertex,fragment,worldRays,surface,surfaceOnly,voxelShadows,surfaceBeforeTerrain,Map.of(),Map.of(),Map.of());
    }
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface,boolean surfaceOnly,boolean voxelShadows) {
        this(target,vertex,fragment,worldRays,surface,surfaceOnly,voxelShadows,defaultSurfaceBeforeTerrain(fragment,surface));
    }
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface,boolean surfaceOnly) {this(target,vertex,fragment,worldRays,surface,surfaceOnly,false);}
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays) {this(target,vertex,fragment,worldRays,null,false);}
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface) {this(target,vertex,fragment,worldRays,surface,false);}
    public enum Target { TERRAIN, BLOCK, ENTITY, SKY, CLOUDS, WEATHER, PARTICLES, BACKGROUND }
    static boolean defaultSurfaceBeforeTerrain(String fragment,String surface) {
        return "greenmarine:core/solstice_terrain".equals(fragment) && "greenmarine:core/solstice_surface".equals(surface);
    }
    public PackProgram {
        Objects.requireNonNull(target);
        stages=Map.copyOf(stages);
        stages.forEach((stage,id)->{if(!stage.intermediate())throw new IllegalArgumentException("World stage map accepts intermediate stages only");ShaderPack.requireId(id);});
        boolean tc=stages.containsKey(GraphicsStage.TESS_CONTROL),te=stages.containsKey(GraphicsStage.TESS_EVALUATION);
        if(tc!=te || tc!=(patchVertices>0) || patchVertices<0 || patchVertices>32)
            throw new IllegalArgumentException("World tessellation requires both stages and an input patch size in 1..32");
        if(!stages.isEmpty() && (vertex==null || surface!=null || target==Target.BACKGROUND))
            throw new IllegalArgumentException("Intermediate world stages require an explicit vertex and ordinary world drawing without surface replay");
        pipelines=Collections.unmodifiableSet(new LinkedHashSet<>(pipelines));
        if(pipelines.size()>128)throw new IllegalArgumentException("Too many exact world pipeline selectors");
        pipelines.forEach(ShaderPack::requireId);
        if(target==Target.BACKGROUND && !pipelines.isEmpty())throw new IllegalArgumentException("Background does not select host pipelines");
        outputs=Collections.unmodifiableMap(new TreeMap<>(outputs));
        inputs=Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        uniforms=Collections.unmodifiableMap(new LinkedHashMap<>(uniforms));
        if(outputs.size()>32 || inputs.size()>32 || uniforms.size()>64)throw new IllegalArgumentException("World program binding limit exceeded");
        var names=new HashSet<String>();
        for(int location=0;location<outputs.size();location++) {
            var name=outputs.get(location);
            if(name==null || !names.add(name))throw new IllegalArgumentException("World outputs require unique targets at consecutive locations starting at zero");
            PackPass.requireBindingName(name);
        }
        for(var input:inputs.entrySet()) {PackPass.requireBindingName(input.getKey());PackPass.requireBindingName(input.getValue());}
        for(var uniform:uniforms.entrySet()) {
            PackPass.requireBindingName(uniform.getKey());PackPass.requireBindingName(uniform.getValue());
            if(inputs.containsKey(uniform.getKey()))throw new IllegalArgumentException("World uniform shadows an input sampler: "+uniform.getKey());
        }
        if((surface!=null || target==Target.BACKGROUND) && (!outputs.isEmpty() || !inputs.isEmpty() || !uniforms.isEmpty()))
            throw new IllegalArgumentException("Named world bindings currently require an ordinary world program without surface replay");
        if(surfaceBeforeTerrain && (target!=Target.TERRAIN || surface==null))
            throw new IllegalArgumentException("Early surface capture requires a terrain surface program");
        if(target==Target.BACKGROUND && (vertex==null || worldRays || surface!=null || surfaceOnly || voxelShadows))
            throw new IllegalArgumentException("Background requires explicit vertex/fragment stages and no ray capture flags");
        if(voxelShadows && target!=Target.TERRAIN)throw new IllegalArgumentException("Voxel shadows require a terrain program");
        if(vertex!=null) ShaderPack.requireId(vertex);
        ShaderPack.requireId(fragment);
        if(surfaceOnly && (surface==null || !worldRays)) throw new IllegalArgumentException("Surface-only tracing requires a ray-enabled surface program");
        if(surface!=null) {
            ShaderPack.requireId(surface);
            if(target!=Target.TERRAIN) throw new IllegalArgumentException("Surface capture currently supports terrain only");
        }
    }
}
