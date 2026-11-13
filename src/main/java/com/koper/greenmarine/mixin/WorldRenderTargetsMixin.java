package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** All public host overloads converge here before attachments and backend passes are created. */
@Mixin(FrontendCommandEncoder.class)
public abstract class WorldRenderTargetsMixin {
    @ModifyVariable(method="createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",at=@At("HEAD"),argsOnly=true)
    private RenderPassDescriptor greenmarine$worldTargets(RenderPassDescriptor descriptor) {return WorldPrograms.renderPass(descriptor);}
}
