package com.koper.greenmarine.api.pack;

import java.util.Objects;

/** Replaces a vanilla world shader family while preserving its host drawing state. */
public record PackProgram(Target target, String vertex, String fragment, boolean worldRays, String surface, boolean surfaceOnly, boolean voxelShadows, boolean surfaceBeforeTerrain) {
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
