package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.HostImages;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.nio.LongBuffer;

@Mixin(VulkanGpuTexture.class)
public abstract class VulkanVolumeTextureMixin {
    @Redirect(method="<init>", at=@At(value="INVOKE", target="Lorg/lwjgl/util/vma/Vma;vmaCreateImage(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"))
    private int greenmarine$volume(long allocator, VkImageCreateInfo image, VmaAllocationCreateInfo allocation,
                                   LongBuffer result, PointerBuffer memory, VmaAllocationInfo details) {
        HostImages.configure(image);
        return Vma.vmaCreateImage(allocator, image, allocation, result, memory, details);
    }
}
