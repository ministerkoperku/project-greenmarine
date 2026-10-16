package com.koper.greenmarine.client;

import com.koper.greenmarine.api.render.ShaderProgram;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.SpvModule;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Uses the same Vulkan binary interception and GLSL fallback as schema-v1 packs. */
public final class ProgramSources implements BinaryShaderSource {
    private final ShaderProgram program;
    private final boolean vulkan;
    private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();
    public ProgramSources(ShaderProgram program, boolean vulkan) { this.program = program; this.vulkan = vulkan; }
    @Override public String getShader(Identifier id, ShaderType type) { return program.source(id,type); }
    @Override public CachedIncludeSource getInclude(Identifier id) {
        return includes.computeIfAbsent(id, key -> {
            var text = program.includes().get(key);
            return text == null ? CachedIncludeSource.createError("Missing program include: " + key) : CachedIncludeSource.create(key, text);
        });
    }
    @Override public SpvModule rawModule(String name, ShaderType type) {
        if (!vulkan || type != ShaderType.FRAGMENT) return null;
        var binary = program.fragments().get(Identifier.parse(name));
        if (binary == null || !binary.baselineCompatible()) return null;
        byte[] bytes = binary.bytes(); var data = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length); data.put(bytes).flip();
        var module = new com.mojang.renderpearl.frontend.shaders.SPIRVModule(data, type);
        try { module.reflect(); com.koper.greenmarine.GreenMarine.LOGGER.info("Loaded program SPIR-V fragment {} ({} bytes)", name, bytes.length); return module; }
        catch (com.mojang.renderpearl.util.ShaderCompileException | RuntimeException e) { module.close(); return null; }
    }
    @Override public void close() { includes.values().forEach(CachedIncludeSource::close); includes.clear(); }
}
