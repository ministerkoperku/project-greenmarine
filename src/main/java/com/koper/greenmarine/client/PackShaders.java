package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackCatalog;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;

final class PackShaders implements BinaryShaderSource {
    private final PackCatalog.LoadedPack pack;
    private final java.util.Map<Identifier,CachedIncludeSource> includes=new java.util.HashMap<>();
    PackShaders(PackCatalog.LoadedPack pack) { this.pack = pack; }
    @Override public String getShader(Identifier id, ShaderType type) {
        if (type == ShaderType.VERTEX) return """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(location=0) out vec2 texCoord;
            void main() {
                texCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
        if (id.toString().equals("greenmarine:core/copy")) return """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform sampler2D ColorSampler;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            void main() { fragColor = texture(ColorSampler,texCoord); }
            """;
        return pack.sources().get(id.toString());
    }
    @Override public CachedIncludeSource getInclude(Identifier id) {
        return includes.computeIfAbsent(id,key -> {
            String text=pack.sources().get("include|"+key);
            return text==null?CachedIncludeSource.createError("Pack include is missing: "+key):CachedIncludeSource.create(key,text);
        });
    }
    @Override public com.mojang.renderpearl.backend.api.SpvModule rawModule(String name,ShaderType type) {
        if(type!=ShaderType.FRAGMENT || !ShaderRuntime.capabilities().vulkan()) return null;
        var binary=pack.binaries().get(name);
        if(binary==null || !binary.baselineCompatible()) return null;
        byte[] bytes=binary.bytes(); var nativeBytes=org.lwjgl.system.MemoryUtil.memAlloc(bytes.length); nativeBytes.put(bytes).flip();
        var module=new com.mojang.renderpearl.frontend.shaders.SPIRVModule(nativeBytes,type);
        try {
            module.reflect();
            com.koper.greenmarine.GreenMarine.LOGGER.info("Loaded pack SPIR-V fragment {} ({} bytes)",name,bytes.length);
            return module;
        } catch(com.mojang.renderpearl.util.ShaderCompileException | RuntimeException error) {
            module.close(); com.koper.greenmarine.GreenMarine.LOGGER.warn("SPIR-V reflection failed for {}; using GLSL: {}",name,error.getMessage()); return null;
        }
    }
    @Override public void close() { includes.values().forEach(CachedIncludeSource::close); includes.clear(); }
}
