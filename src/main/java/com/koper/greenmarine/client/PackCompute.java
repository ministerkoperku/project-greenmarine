package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.vulkan.*;
import org.joml.Vector4f;
import java.util.*;

/**
 * Pack-level compute: persistent pack images/buffers plus compute passes bound purely by name.
 * A compute shader just declares what it wants ({@code uniform MarineFrame}, {@code buffer SunData},
 * {@code image2D Sky}, {@code sampler2D SceneSampler}) and the matching resource is bound.
 */
public final class PackCompute {
    private record Image(PackResources.Image spec, GpuTexture texture, GpuTextureView view) {}
    private static final Map<String, Image> IMAGES = new LinkedHashMap<>();
    private static final Map<String, GpuBuffer> BUFFERS = new LinkedHashMap<>();
    private static final Map<String, ComputeBackend.Pipeline> PIPELINES = new HashMap<>();
    private static PackResources declared;
    private static int width, height;
    private static long dispatches;
    private PackCompute() {}

    static GpuFormat format(PackResources.Format format) {
        return switch (format) {
            case RGBA8 -> GpuFormat.RGBA8_UNORM; case RGBA16F -> GpuFormat.RGBA16_FLOAT; case RGBA32F -> GpuFormat.RGBA32_FLOAT;
            case R32F -> GpuFormat.R32_FLOAT; case RG32F -> GpuFormat.RG32_FLOAT; case R16F -> GpuFormat.R16_FLOAT; case RG16F -> GpuFormat.RG16_FLOAT;
            case R32UI -> GpuFormat.R32_UINT; case RG32UI -> GpuFormat.RG32_UINT; case RGBA32UI -> GpuFormat.RGBA32_UINT;
            case R11G11B10F -> GpuFormat.RG11B10_FLOAT;
        };
    }
    private static boolean integer(PackResources.Format format) {
        return format == PackResources.Format.R32UI || format == PackResources.Format.RG32UI || format == PackResources.Format.RGBA32UI;
    }

    /** Allocates/clears declared resources; scaled images follow the scene size. */
    static void prepare(PackResources resources, CommandEncoder encoder, int sceneWidth, int sceneHeight) {
        if (resources.isEmpty()) { if (declared != null) closeResources(); return; }
        boolean resized = width != sceneWidth || height != sceneHeight;
        if (declared == resources && !resized) return;
        var vk = ComputeBackend.vulkan(RenderSystem.getDevice());
        if (vk == null) throw new IllegalStateException("Pack resources require the Vulkan compute path");
        if (declared != resources) closeResources();
        declared = resources; width = sceneWidth; height = sceneHeight;
        var device = RenderSystem.getDevice();
        for (var entry : resources.images().entrySet()) {
            var spec = entry.getValue(); var existing = IMAGES.get(entry.getKey());
            int w = spec.width(sceneWidth), h = spec.height(sceneHeight);
            if (existing != null && existing.texture().getWidth(0) == w && existing.texture().getHeight(0) == h) continue;
            if (existing != null) { existing.view().close(); existing.texture().close(); }
            var format = format(spec.format());
            if (!ComputeBackend.storageFormat(vk, format)) throw new IllegalArgumentException("Image " + entry.getKey() + ": " + spec.format() + " is not a storage format on this device");
            var texture = HostStorage.create(() -> device.createTexture("GreenMarine pack image " + entry.getKey(),
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, format, w, h, 1, 1));
            ComputeBackend.storage(texture);
            IMAGES.put(entry.getKey(), new Image(spec, texture, device.createTextureView(texture)));
            encoder.clearColorTexture(texture, new Vector4f());
        }
        for (var entry : resources.buffers().entrySet()) {
            if (BUFFERS.containsKey(entry.getKey())) continue;
            int size = entry.getValue().size();
            var buffer = HostStorage.create(() -> device.createBuffer(() -> "GreenMarine pack buffer " + entry.getKey(),
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, size));
            ComputeBackend.storage(buffer); BUFFERS.put(entry.getKey(), buffer);
            var zero = org.lwjgl.system.MemoryUtil.memCalloc(size);
            try { encoder.writeToBuffer(buffer.slice(), zero); } finally { org.lwjgl.system.MemoryUtil.memFree(zero); }
        }
    }

    public static GpuBuffer buffer(String name) { return BUFFERS.get(name); }
    public static GpuTextureView image(String name) { var image = IMAGES.get(name); return image == null ? null : image.view(); }
    static boolean integerImage(String name) { var image = IMAGES.get(name); return image != null && integer(image.spec().format()); }

    /** Builtins hold the same samplers as a scene pass at this point of the chain. */
    static void dispatch(CommandEncoder encoder, PackCatalog.LoadedPack pack, PackPass pass, GpuBufferSlice frame,
                         Map<String, GpuTextureView> builtins, Map<String, GpuTextureView> inputs) {
        var vk = ComputeBackend.vulkan(RenderSystem.getDevice());
        var pipeline = PIPELINES.computeIfAbsent(pass.fragment(), id -> {
            String source = pack.sources().get("compute|" + id);
            if (source == null) throw new IllegalArgumentException("Missing compute source: " + id);
            var expanded = ComputeBackend.expand(source, include -> pack.sources().get("include|" + include));
            byte[] spirv = ComputeBackend.compile(id, expanded);
            var created = ComputeBackend.create(vk, spirv, ComputeBackend.reflect(spirv));
            com.koper.greenmarine.GreenMarine.LOGGER.info("Compiled pack compute {}: bindings={}, local={}x{}x{}", id,
                created.reflection().slots(), created.reflection().localX(), created.reflection().localY(), created.reflection().localZ());
            return created;
        });
        var reflection = pipeline.reflection();
        var bound = new ArrayList<ComputeBackend.Bound>(); var written = new HashSet<String>(); var sampled = new HashSet<String>();
        for (var slot : reflection.slots()) {
            String name = slot.name();
            bound.add(switch (slot.kind()) {
                case UNIFORM_BUFFER -> {
                    if (!name.equals("MarineFrame")) throw new IllegalArgumentException(pass.name() + ": unknown uniform block " + name + " (only MarineFrame; use buffer blocks for pack data)");
                    yield new ComputeBackend.BufferRange(((VulkanGpuBuffer) frame.buffer()).vkBuffer(), frame.offset(), frame.length());
                }
                case STORAGE_BUFFER -> {
                    var buffer = BUFFERS.get(name);
                    if (buffer == null) throw new IllegalArgumentException(pass.name() + ": buffer block " + name + " is not a declared pack buffer");
                    yield new ComputeBackend.BufferRange(((VulkanGpuBuffer) buffer).vkBuffer(), 0, buffer.size());
                }
                case STORAGE_IMAGE -> {
                    var image = IMAGES.get(name);
                    if (image == null) throw new IllegalArgumentException(pass.name() + ": image " + name + " is not a declared pack image");
                    written.add(name);
                    yield new ComputeBackend.StorageImage(((VulkanGpuTextureView) image.view()).vkImageView());
                }
                case SAMPLED_TEXTURE -> {
                    var view = inputs.containsKey(name) ? inputs.get(name) : builtins.containsKey(name) ? builtins.get(name) : image(name);
                    if (view == null) throw new IllegalArgumentException(pass.name() + ": sampler " + name + " is not a builtin, input or pack image");
                    if (!inputs.containsKey(name) && !builtins.containsKey(name)) sampled.add(name);
                    boolean nearest = name.equals("DepthSampler") || integerImage(name);
                    var sampler = (VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(nearest ? FilterMode.NEAREST : FilterMode.LINEAR);
                    yield new ComputeBackend.SampledImage(((VulkanGpuTextureView) view).vkImageView(), sampler.vkSampler());
                }
            });
        }
        sampled.retainAll(written);
        if (!sampled.isEmpty()) throw new IllegalArgumentException(pass.name() + ": image both written and sampled in one dispatch: " + sampled);
        int[] groups = groups(pass.groups(), reflection);
        ComputeBackend.dispatch(encoder, pipeline, bound, groups[0], groups[1], groups[2]);
        dispatches++;
    }
    static int[] groups(String spec, ComputeBackend.Reflection reflection) {
        if (spec.equals("screen")) return new int[]{Math.ceilDiv(width, reflection.localX()), Math.ceilDiv(height, reflection.localY()), 1};
        if (spec.startsWith("image:")) {
            var image = IMAGES.get(spec.substring(6)).texture();
            return new int[]{Math.ceilDiv(image.getWidth(0), reflection.localX()), Math.ceilDiv(image.getHeight(0), reflection.localY()), 1};
        }
        var parts = spec.split(",");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }
    /** Pack buffers/images for raster passes; names the shader doesn't declare are ignored by the host. */
    static void bindRaster(com.mojang.renderpearl.api.commands.RenderPass pass) {
        if (declared == null) return;
        BUFFERS.forEach((name, buffer) -> pass.setUniform(name, buffer.slice()));
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        IMAGES.forEach((name, image) -> pass.setUniform(name, image.view(), sampler));
    }
    public static long dispatches() { return dispatches; }

    private static void closeResources() {
        IMAGES.values().forEach(image -> { image.view().close(); image.texture().close(); }); IMAGES.clear();
        BUFFERS.values().forEach(GpuBuffer::close); BUFFERS.clear();
        declared = null; width = height = 0;
    }
    public static void close() {
        closeResources();
        var device = RenderSystem.tryGetDevice(); var vk = device == null ? null : ComputeBackend.vulkan(device);
        if (vk != null) PIPELINES.values().forEach(pipeline -> pipeline.release(vk));
        PIPELINES.clear(); dispatches = 0;
    }
}
