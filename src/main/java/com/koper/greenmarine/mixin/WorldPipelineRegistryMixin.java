package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.world.SodiumCompatibility;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(FrontendGpuDevice.class)
public abstract class WorldPipelineRegistryMixin {
    @Inject(method = "compilePipeline", at = @At("HEAD"))
    private void greenmarine$hostDefinition(RenderPipeline pipeline, ShaderSource source, Executor executor,
        CallbackInfoReturnable<CompletableFuture<CompiledRenderPipeline.Pending>> ci) {
        SodiumCompatibility.observe(pipeline);
    }
}
