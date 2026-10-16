package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public abstract class WorldRenderPassMixin {
    @Shadow private FrontendRenderPipeline boundPipeline;
    @ModifyVariable(method="setPipeline",at=@At("HEAD"),argsOnly=true)
    private CompiledRenderPipeline greenmarine$program(CompiledRenderPipeline pipeline) {return WorldPrograms.select(pipeline);}
    @Inject(method="setPipeline",at=@At("RETURN"))
    private void greenmarine$bindings(CompiledRenderPipeline pipeline,CallbackInfo ci) {WorldPrograms.bind((RenderPass)(Object)this,boundPipeline);}
    @Inject(method={"draw","drawIndexed","multiDraw","multiDrawIndexed","drawIndirect","drawIndexedIndirect","drawMultipleIndexed"},at=@At("RETURN"))
    private void greenmarine$drawn(CallbackInfo ci) {WorldPrograms.drawn(boundPipeline);}
}
