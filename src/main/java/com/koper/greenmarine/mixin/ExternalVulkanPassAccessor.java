package com.koper.greenmarine.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanRenderPass.class)
public interface ExternalVulkanPassAccessor {
    @Accessor("commandBuffer") VkCommandBuffer greenmarine$commandBuffer();
}
