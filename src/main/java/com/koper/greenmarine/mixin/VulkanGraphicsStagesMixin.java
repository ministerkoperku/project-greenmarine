package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.GraphicsStagePipelines;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Small host creation hooks keep all drawing and pipeline binding inside Renderpearl. */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanGraphicsStagesMixin {
    @ModifyArg(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;stageFlags(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
    private static int greenmarine$visibility(int mask){return GraphicsStagePipelines.visibility(mask);}
    @Redirect(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VK12;vkCreateDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private static int greenmarine$set(VkDevice d,VkDescriptorSetLayoutCreateInfo i,VkAllocationCallbacks a,LongBuffer o){return GraphicsStagePipelines.createSet(d,i,a,o);}
    @Redirect(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VK12;vkCreatePipelineLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private static int greenmarine$layout(VkDevice d,VkPipelineLayoutCreateInfo i,VkAllocationCallbacks a,LongBuffer o){return GraphicsStagePipelines.createLayout(d,i,a,o);}
    @Redirect(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VK12;vkCreateShaderModule(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkShaderModuleCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private static int greenmarine$module(VkDevice d,VkShaderModuleCreateInfo i,VkAllocationCallbacks a,LongBuffer o){return GraphicsStagePipelines.createModule(d,i,a,o);}
    @Redirect(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private static int greenmarine$pipelines(VkDevice d,long c,VkGraphicsPipelineCreateInfo.Buffer i,VkAllocationCallbacks a,LongBuffer o){return GraphicsStagePipelines.createPipelines(d,c,i,a,o);}
}
