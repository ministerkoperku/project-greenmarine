package com.koper.greenmarine.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Renderpearl exposes U/V addressing; extend V consistently to a volume's third axis. */
@Mixin(VulkanGpuSampler.class)
public abstract class VulkanVolumeSamplerMixin {
    @Redirect(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkSamplerCreateInfo;addressModeV(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
    private VkSamplerCreateInfo greenmarine$volumeAddressing(VkSamplerCreateInfo info,int mode) {
        return info.addressModeV(mode).addressModeW(mode);
    }
}
