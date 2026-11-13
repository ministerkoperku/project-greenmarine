package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK12.*;

/** Typed clears for owned scene attachments. Integer values must never pass through float casts. */
final class TargetClears {
    private TargetClears() {}
    static void clear(CommandEncoder encoder,GpuTexture texture,PackTarget spec) {
        if(!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder backend)) {
            if(PackTarget.integer(spec.format()))throw new UnsupportedOperationException("Unsigned scene target clears require Vulkan");
            encoder.clearColorTexture(texture,new Vector4f(spec.clear().get(0).floatValue(),spec.clear().get(1).floatValue(),spec.clear().get(2).floatValue(),spec.clear().get(3).floatValue()));
            return;
        }
        if(frontend.isInRenderPass())throw new IllegalStateException("Scene target clear inside a render pass");
        var command=backend.allocateAndBeginTransientCommandBuffer();
        try(var stack=MemoryStack.stackPush()) {
            var before=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,before,null,null);
            var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            var value=VkClearColorValue.calloc(stack);var bits=spec.clearBits();
            for(int i=0;i<4;i++)if(PackTarget.integer(spec.format()))value.uint32(i,bits[i]);else value.float32(i,Float.intBitsToFloat(bits[i]));
            vkCmdClearColorImage(command,((VulkanGpuTexture)texture).vkImage(),VK_IMAGE_LAYOUT_GENERAL,value,range);
            var after=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,after,null,null);
            int result=vkEndCommandBuffer(command);
            if(result!=VK_SUCCESS)throw new IllegalStateException("Scene target clear recording failed: "+result);
        }
        backend.execute(command);
    }
}
