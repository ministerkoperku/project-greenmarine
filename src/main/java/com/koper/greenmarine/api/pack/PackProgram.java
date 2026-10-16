package com.koper.greenmarine.api.pack;

import java.util.Objects;

/** Replaces a vanilla world shader family while preserving its host drawing state. */
public record PackProgram(Target target, String vertex, String fragment, boolean worldRays, String surface, boolean surfaceOnly) {
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays) {this(target,vertex,fragment,worldRays,null,false);}
    public PackProgram(Target target,String vertex,String fragment,boolean worldRays,String surface) {this(target,vertex,fragment,worldRays,surface,false);}
    public enum Target { TERRAIN, BLOCK, ENTITY, SKY, CLOUDS, WEATHER, PARTICLES }
    public PackProgram {
        Objects.requireNonNull(target);
        if(vertex!=null) ShaderPack.requireId(vertex);
        ShaderPack.requireId(fragment);
        if(surfaceOnly && (surface==null || !worldRays)) throw new IllegalArgumentException("Surface-only tracing requires a ray-enabled surface program");
        if(surface!=null) {
            ShaderPack.requireId(surface);
            if(target!=Target.TERRAIN) throw new IllegalArgumentException("Surface capture currently supports terrain only");
        }
    }
}
