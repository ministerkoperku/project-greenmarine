package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.GreenMarineClient;
import com.koper.greenmarine.client.GreenPostEffect;
import com.koper.greenmarine.client.VulkanHost;
import com.koper.greenmarine.client.ResourceEffects;
import com.koper.greenmarine.client.ResourcePacks;
import com.koper.greenmarine.client.PackRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void greenmarine$render(CallbackInfo ci) {
        com.koper.greenmarine.client.world.WorldPrograms.finishWorld();
        com.koper.greenmarine.client.ShaderRuntime.frameStatus();
        com.koper.greenmarine.api.render.MarineRenderer.render(com.koper.greenmarine.api.render.MarineRenderer.Stage.BEFORE_LIGHTING, (GameRenderer)(Object)this);
        GreenPostEffect.render((GameRenderer)(Object)this);
        com.koper.greenmarine.api.render.MarineRenderer.render(com.koper.greenmarine.api.render.MarineRenderer.Stage.BEFORE_PACKS, (GameRenderer)(Object)this);
        PackRenderer.renderScene((GameRenderer)(Object)this);
        com.koper.greenmarine.api.render.MarineRenderer.render(com.koper.greenmarine.api.render.MarineRenderer.Stage.AFTER_PACKS, (GameRenderer)(Object)this);
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f greenmarine$projection(Matrix4f projection) {
        com.koper.greenmarine.api.render.MarineRenderer.captureProjection(projection);
        com.koper.greenmarine.client.rt.RayWorld.captureProjection(projection);
        GreenPostEffect.captureProjection(projection);
        PackRenderer.captureProjection(projection);
        com.koper.greenmarine.client.rt.RayWorld.renderFrame((GameRenderer)(Object)this);
        com.koper.greenmarine.client.world.WorldPrograms.prepare((GameRenderer)(Object)this,projection);
        return projection;
    }

    @Inject(method="renderLevel",at=@At("RETURN"))
    private void greenmarine$finishWorld(CallbackInfo ci) {com.koper.greenmarine.client.world.WorldPrograms.finishWorld();}

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void greenmarine$reload(ResourceManager resources, CallbackInfo ci) {
        GreenMarineClient.reload();
        ResourceEffects.reload(resources);
        ResourcePacks.reload(resources);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void greenmarine$close(CallbackInfo ci) {
        VulkanHost.reset();
        GreenMarineClient.close();
    }
}
