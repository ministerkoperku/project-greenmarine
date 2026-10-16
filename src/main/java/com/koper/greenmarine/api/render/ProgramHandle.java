package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;

public final class ProgramHandle extends OwnedResource {
    private final ShaderProgram definition;
    private final CompiledRenderPipeline pipeline;
    ProgramHandle(ResourceScope owner, ShaderProgram definition, CompiledRenderPipeline pipeline) { super(owner); this.definition = definition; this.pipeline = pipeline; }
    public ShaderProgram definition() { check(); return definition; }
    public CompiledRenderPipeline pipeline() { check(); return pipeline; }
    @Override protected void destroy() { pipeline.close(); }
}
