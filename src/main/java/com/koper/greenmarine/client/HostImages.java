package com.koper.greenmarine.client;

import com.koper.greenmarine.api.render.VolumeSpec;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import java.util.function.Supplier;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Opt-in 3D allocations through the host allocator and destruction queue. The host facade still
 * describes one subresource layer: depth slices belong to a single 3D image, never array layers.
 */
public final class HostImages {
    private record Shape(int dimensions, int depth) {}
    private static final ThreadLocal<Shape> CREATING = new ThreadLocal<>();
    private static final Map<GpuTexture, Shape> SHAPES = Collections.synchronizedMap(new WeakHashMap<>());
    private HostImages() {}

    public static int depth(GpuTexture texture) { var shape = SHAPES.get(texture); return shape == null ? 1 : shape.depth(); }
    public static boolean volume(GpuTexture texture) { return dimensions(texture) == 3; }
    public static int dimensions(GpuTexture texture) { var shape = SHAPES.get(texture); return shape == null ? 2 : shape.dimensions(); }

    public static GpuTexture create(GpuDevice device, String label, VolumeSpec spec) {
        return create(device, label, spec, new Shape(3, spec.depth()));
    }
    public static GpuTexture createLine(GpuDevice device, String label, com.mojang.renderpearl.api.GpuFormat format, int width) {
        return create(device, label, new VolumeSpec(format, width, 1, 1), new Shape(1, 1));
    }
    private static GpuTexture create(GpuDevice device, String label, VolumeSpec spec, Shape shape) {
        var vk = ComputeBackend.vulkan(device);
        if (vk == null) throw new UnsupportedOperationException("Volume images require the Vulkan backend");
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkImageFormatProperties.calloc(stack);
            int result = vkGetPhysicalDeviceImageFormatProperties(vk.vkDevice().getPhysicalDevice(), VulkanConst.toVk(spec.format()),
                shape.dimensions() - 1, VK_IMAGE_TILING_OPTIMAL, usage, 0, properties);
            if (result != VK_SUCCESS || spec.width() > properties.maxExtent().width() || spec.height() > properties.maxExtent().height()
                || spec.depth() > properties.maxExtent().depth())
                throw new IllegalArgumentException("Volume dimensions/format unsupported on the host device: " + spec);
        }
        var texture = creating(shape, () -> HostStorage.create(() -> device.createTexture(label,
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
            spec.format(), spec.width(), spec.height(), 1, 1)));
        SHAPES.put(texture, shape);
        return ComputeBackend.storage(texture);
    }

    private static <T> T creating(Shape shape, Supplier<T> factory) {
        var previous = CREATING.get(); CREATING.set(shape);
        try { return factory.get(); } finally { if (previous == null) CREATING.remove(); else CREATING.set(previous); }
    }
    /** Called immediately before VMA creates a host-owned wrapper's GreenMarine allocation. */
    public static void configure(VkImageCreateInfo info) {
        var shape = CREATING.get();
        if (shape == null) return;
        if (info.arrayLayers() != 1 || info.mipLevels() != 1 || info.flags() != 0
            || (info.usage() & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) != 0)
            throw new IllegalArgumentException("3D storage images cannot be ordinary render attachments, arrays or cubemaps");
        info.imageType(shape.dimensions() - 1);
        info.extent().depth(shape.depth());
    }
    public static void configure(VkImageViewCreateInfo info, GpuTexture texture) {
        if (dimensions(texture) != 2) info.viewType(dimensions(texture) - 1);
    }
    public static void requireDimensions(String name, int expected, GpuTexture texture) {
        if (expected != dimensions(texture)) throw new IllegalArgumentException(name + " requires a " + expected + "D image, got " + dimensions(texture) + "D");
    }

    /** Transfer clear of every depth slice; the host's attachment-only clear rejects 3D images. */
    public static void clear(CommandEncoder encoder, GpuTexture texture) {
        if (!SHAPES.containsKey(texture) || texture.isClosed()) throw new IllegalArgumentException("Expected a live GreenMarine 1D/3D image");
        if (!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder backend))
            throw new UnsupportedOperationException("Volume clear requires Vulkan");
        if (frontend.isInRenderPass()) throw new IllegalStateException("Volume clear cannot run inside a render pass");
        var command = backend.allocateAndBeginTransientCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            var before = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before, null, null);
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdClearColorImage(command, ((VulkanGpuTexture) texture).vkImage(), VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack), range);
            var after = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, after, null, null);
            int result = vkEndCommandBuffer(command);
            if (result != VK_SUCCESS) throw new IllegalStateException("Volume clear recording failed: " + result);
        }
        backend.execute(command);
    }
}
