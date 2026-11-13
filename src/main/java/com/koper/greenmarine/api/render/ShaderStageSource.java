package com.koper.greenmarine.api.render;

/** Editable GLSL fallback with an optional binary of the same stage. */
public record ShaderStageSource(String source,StageBinary binary) {
    public ShaderStageSource {
        if(source==null || source.isBlank() || source.length()>2*1024*1024)throw new IllegalArgumentException("Missing or oversized stage GLSL fallback");
    }
    public ShaderStageSource(String source) {this(source,null);}
}
