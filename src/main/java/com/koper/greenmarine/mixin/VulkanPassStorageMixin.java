package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.RasterStorage;
import com.mojang.renderpearl.backend.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Pushes storage descriptors for bindings the pipeline marked as storage (images stay GENERAL). */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanPassStorageMixin {
    @Shadow private VulkanRenderPipeline pipeline;
    @Unique private int greenmarine$binding;
    @ModifyArg(method="pushDescriptors",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkWriteDescriptorSet;dstBinding(I)Lorg/lwjgl/vulkan/VkWriteDescriptorSet;"))
    private int greenmarine$binding(int binding) { greenmarine$binding = binding; return binding; }
    @ModifyArg(method="pushDescriptors",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkWriteDescriptorSet;descriptorType(I)Lorg/lwjgl/vulkan/VkWriteDescriptorSet;"))
    private int greenmarine$descriptorType(int type) {
        var types = ((RasterStorage.Pipeline) (Object) pipeline).greenmarine$storageTypes();
        return types != null && greenmarine$binding < types.length && types[greenmarine$binding] != 0 ? types[greenmarine$binding] : type;
    }
}
