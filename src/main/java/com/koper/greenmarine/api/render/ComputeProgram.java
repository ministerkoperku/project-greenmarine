package com.koper.greenmarine.api.render;

import net.minecraft.resources.Identifier;
import java.util.*;

/**
 * Immutable GLSL compute source snapshot. Bindings come from the compiled module itself:
 * uniform/buffer blocks by block name, {@code image2D}/{@code sampler2D} by variable name,
 * all in descriptor set 0 (explicit {@code layout(binding=N)} is honoured, the rest is
 * auto-assigned). Optional SPIR-V replaces the GLSL only on Vulkan; the GLSL stays the source of truth.
 */
public final class ComputeProgram {
    private final Identifier id;
    private final String source;
    private final Map<Identifier, String> includes;
    private final byte[] spirv;
    public ComputeProgram(Identifier id, String source, Map<Identifier, String> includes) { this(id, source, includes, null); }
    public ComputeProgram(Identifier id, String source, Map<Identifier, String> includes, byte[] spirv) {
        this.id = Objects.requireNonNull(id);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Missing compute GLSL: " + id);
        if (source.length() > com.koper.greenmarine.client.ComputeBackend.MAX_SOURCE) throw new IllegalArgumentException("Compute source exceeds 2 MiB: " + id);
        this.source = source; this.includes = Map.copyOf(includes);
        if (spirv != null && (spirv.length < 20 || spirv.length % 4 != 0 || spirv.length > 4 * 1024 * 1024)) throw new IllegalArgumentException("Invalid compute SPIR-V size");
        this.spirv = spirv == null ? null : spirv.clone();
    }
    public Identifier id() { return id; }
    public String source() { return source; }
    public Map<Identifier, String> includes() { return includes; }
    public Optional<byte[]> spirv() { return Optional.ofNullable(spirv).map(byte[]::clone); }
    byte[] rawSpirv() { return spirv; }
    /** Source with includes resolved, exactly as handed to the compiler. */
    public String expandedSource() {
        return com.koper.greenmarine.client.ComputeBackend.expand(source, name -> includes.get(Identifier.tryParse(name)));
    }
}
