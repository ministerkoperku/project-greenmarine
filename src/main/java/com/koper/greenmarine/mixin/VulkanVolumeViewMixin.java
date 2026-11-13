package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.HostImages;
import com.mojang.renderpearl.backend.vulkan.*;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.nio.LongBuffer;

@Mixin(VulkanGpuTextureView.class)
public abstract class VulkanVolumeViewMixin {
    @Redirect(method="<init>", at=@At(value="INVOKE", target="Lorg/lwjgl/vulkan/VK12;vkCreateImageView(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkImageViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private int greenmarine$volume(VkDevice device, VkImageViewCreateInfo info, VkAllocationCallbacks allocator, LongBuffer result,
                                   VulkanDevice owner, VulkanGpuTexture texture, int firstMip, int mipCount) {
        HostImages.configure(info, texture);
        return VK12.vkCreateImageView(device, info, allocator, result);
    }
}
