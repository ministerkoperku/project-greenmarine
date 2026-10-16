package com.koper.greenmarine.client;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;

public interface BinaryShaderSource extends ShaderSource {
    SpvModule rawModule(String name,ShaderType type);
}
