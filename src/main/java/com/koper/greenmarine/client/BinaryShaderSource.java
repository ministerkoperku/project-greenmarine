package com.koper.greenmarine.client;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;

public interface BinaryShaderSource extends ShaderSource {
    /** Optional complete graphics chain, compiled on the same host pipeline as the endpoints. */
    default com.koper.greenmarine.api.render.ShaderProgram graphicsProgram() {return null;}
    default void validateIntermediateModule(com.koper.greenmarine.api.render.GraphicsStage stage,java.nio.ByteBuffer module) {}
    SpvModule rawModule(String name,ShaderType type);
    /** Checks the exact compiled module, after preprocessing and optional binary selection. */
    default void validateModule(String name,ShaderType type,SpvModule module) {}
}
