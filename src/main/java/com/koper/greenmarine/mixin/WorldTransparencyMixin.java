package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(LevelRenderer.class)
public abstract class WorldTransparencyMixin {
    @Shadow @Final private LevelTargetBundle targets;
    @Shadow private com.mojang.renderpearl.api.textures.GpuSampler chunkLayerSampler;
    @Shadow private void executeClassicTransparency(ChunkSectionsToRender chunks,FeatureRenderDispatcher.PreparedFrame features,RenderPass pass) {throw new AssertionError();}
    @Unique private ChunkSectionsToRender greenmarine$chunks;
    @Unique private FeatureRenderDispatcher.PreparedFrame greenmarine$features;
    @Inject(method="lambda$addMainPass$0",at=@At("HEAD"))
    private void greenmarine$begin(CallbackInfo ci) {greenmarine$chunks=null;greenmarine$features=null;WorldPrograms.beginMain();}
    @Inject(method="lambda$addMainPass$0",at=@At("RETURN"))
    private void greenmarine$end(CallbackInfo ci) {WorldPrograms.endMain();}
    @Redirect(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void greenmarine$defer(LevelRenderer renderer,ChunkSectionsToRender chunks,FeatureRenderDispatcher.PreparedFrame features,RenderPass pass) {
        if(WorldPrograms.enabled()){greenmarine$chunks=chunks;greenmarine$features=features;}
        else executeClassicTransparency(chunks,features,pass);
    }
    @Inject(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;executeOit(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void greenmarine$opaqueOit(GpuBufferSlice fog,boolean oit,ChunkSectionsToRender chunks,FeatureRenderDispatcher.PreparedFrame features,boolean a,boolean b,CallbackInfo ci) {
        var main=targets.main.get();WorldPrograms.captureOpaque(main);WorldPrograms.captureMaterials(main,fog,chunks,chunkLayerSampler,net.minecraft.client.Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.renderWireframeTerrain);
    }
    @Inject(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void greenmarine$classic(GpuBufferSlice fog,boolean oit,ChunkSectionsToRender chunks,FeatureRenderDispatcher.PreparedFrame features,boolean a,boolean b,CallbackInfo ci) {
        if(greenmarine$chunks==null)return;
        var deferred=greenmarine$chunks;var prepared=greenmarine$features;greenmarine$chunks=null;greenmarine$features=null;
        var main=targets.main.get();WorldPrograms.captureOpaque(main);WorldPrograms.captureMaterials(main,fog,chunks,chunkLayerSampler,net.minecraft.client.Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.renderWireframeTerrain);
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        try(var pass=encoder.createRenderPass(()->"GreenMarine refractive transparency",main.getColorTextureView(),Optional.empty(),main.getDepthTextureView(),OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);pass.setUniform("Fog",fog);executeClassicTransparency(deferred,prepared,pass);
        }
    }
}
