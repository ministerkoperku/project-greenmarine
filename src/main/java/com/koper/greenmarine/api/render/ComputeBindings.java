package com.koper.greenmarine.api.render;

import com.koper.greenmarine.client.ComputeBackend;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.*;
import java.util.*;

/**
 * Resources for one dispatch, checked against the reflected module. Storage images and storage
 * buffers must come from {@link RenderSession#storageTexture}/{@link RenderSession#storageBuffer}
 * (or a graph {@code storageTexture}); sampled textures can be any host view.
 */
public final class ComputeBindings {
    final ComputeHandle program;
    private final Map<String, ComputeBackend.Bound> bound = new HashMap<>();
    final Set<GpuTexture> writtenImages = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<GpuTexture> sampledImages = Collections.newSetFromMap(new IdentityHashMap<>());
    ComputeBindings(ComputeHandle program) { this.program = program; }
    private ComputeHandle.Binding slot(String name, ComputeHandle.Access... accepted) {
        var binding = program.bindings().stream().filter(b -> b.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Compute program " + program.program().id() + " has no resource " + name));
        if (!Arrays.asList(accepted).contains(binding.access())) throw new IllegalArgumentException(name + " is " + binding.access());
        if (bound.containsKey(name)) throw new IllegalArgumentException("Compute resource bound twice: " + name);
        return binding;
    }
    public ComputeBindings buffer(String name, BufferHandle buffer) { return buffer(name, buffer.buffer(), 0, buffer.buffer().size()); }
    /** Uniform blocks need USAGE_UNIFORM; buffer blocks need a storage buffer. */
    public ComputeBindings buffer(String name, GpuBuffer buffer, long offset, long size) {
        var slot = slot(name, ComputeHandle.Access.UNIFORM_BUFFER, ComputeHandle.Access.STORAGE_BUFFER);
        if (offset < 0 || size <= 0 || offset > buffer.size() - size) throw new IllegalArgumentException("Buffer range exceeds allocation: " + name);
        if (slot.access() == ComputeHandle.Access.STORAGE_BUFFER && !ComputeBackend.isStorage(buffer))
            throw new IllegalArgumentException(name + " needs a buffer from RenderSession.storageBuffer");
        if (slot.access() == ComputeHandle.Access.UNIFORM_BUFFER && (buffer.usage() & GpuBuffer.USAGE_UNIFORM) == 0)
            throw new IllegalArgumentException(name + " needs a uniform buffer");
        bound.put(name, new ComputeBackend.BufferRange(((com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer) buffer).vkBuffer(), offset, size));
        return this;
    }
    public ComputeBindings image(String name, TextureHandle texture) { return image(name, texture.view()); }
    /** Storage image, read/write in GENERAL layout; single mip, the whole view. */
    public ComputeBindings image(String name, GpuTextureView view) {
        slot(name, ComputeHandle.Access.STORAGE_IMAGE);
        if (!ComputeBackend.isStorage(view.texture())) throw new IllegalArgumentException(name + " needs a storage texture");
        if (!writtenImages.add(view.texture()) || sampledImages.contains(view.texture())) throw new IllegalArgumentException("Texture aliased within one dispatch: " + name);
        bound.put(name, new ComputeBackend.StorageImage(((com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView) view).vkImageView()));
        return this;
    }
    public ComputeBindings texture(String name, GpuTextureView view, FilterMode filter) {
        slot(name, ComputeHandle.Access.SAMPLED_TEXTURE);
        if ((view.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) throw new IllegalArgumentException(name + " is not sampleable");
        if (writtenImages.contains(view.texture()) || !sampledImages.add(view.texture())) throw new IllegalArgumentException("Texture aliased within one dispatch: " + name);
        var sampler = (com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler) com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(Objects.requireNonNull(filter));
        bound.put(name, new ComputeBackend.SampledImage(((com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView) view).vkImageView(), sampler.vkSampler()));
        return this;
    }
    List<ComputeBackend.Bound> resolve() {
        var missing = new ArrayList<String>(); var ordered = new ArrayList<ComputeBackend.Bound>();
        for (var binding : program.bindings()) {
            var resource = bound.get(binding.name());
            if (resource == null) missing.add(binding.name()); else ordered.add(resource);
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("Compute program " + program.program().id() + " requires " + missing);
        return ordered;
    }
}
