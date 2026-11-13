package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.terrain.TerrainDrawBindings;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Collection;

@Mixin(ChunkSectionsToRender.DrawSeparate.class)
public abstract class TerrainDrawSeparateMixin implements TerrainDrawBindings.Attachment {
    @Unique private TerrainDrawBindings.Capture greenmarine$terrainDraws;
    public TerrainDrawBindings.Capture greenmarine$terrainDraws() { return greenmarine$terrainDraws; }
    public void greenmarine$terrainDraws(TerrainDrawBindings.Capture value) { greenmarine$terrainDraws = value; }

    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"))
    private <T> void greenmarine$bind(RenderPass pass, Collection<RenderPass.Draw<T>> draws, GpuBuffer indices, IndexType type,
                                    Collection<String> uniforms, T data, Operation<Void> original, @Local(argsOnly=true) ChunkSectionLayer layer) {
        if (!TerrainDrawBindings.enabled() || greenmarine$terrainDraws == null) {
            original.call(pass, draws, indices, type, uniforms, data);
            return;
        }
        original.call(pass, TerrainDrawBindings.decorate(greenmarine$terrainDraws, layer, draws), indices, type, TerrainDrawBindings.uniforms(uniforms), data);
    }
}
