package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.HostStorage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Opt-in storage usage for GreenMarine compute resources; host-created objects never see it. */
@Mixin(VulkanConst.class)
public abstract class HostStorageMixin {
    @Inject(method="textureUsageToVk",at=@At("RETURN"),cancellable=true)
    private static void greenmarine$storageImage(int usage,GpuFormat format,CallbackInfoReturnable<Integer> ci) {
        if(HostStorage.active() && format.hasColorAspect()) ci.setReturnValue(ci.getReturnValue()|0x8); // VK_IMAGE_USAGE_STORAGE_BIT
    }
    @Inject(method="bufferUsageToVk",at=@At("RETURN"),cancellable=true)
    private static void greenmarine$storageBuffer(int usage,CallbackInfoReturnable<Integer> ci) {
        if(HostStorage.active()) ci.setReturnValue(ci.getReturnValue()|0x20); // VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    }
}
