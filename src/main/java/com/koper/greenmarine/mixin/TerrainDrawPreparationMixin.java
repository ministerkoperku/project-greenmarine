package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.terrain.TerrainDrawBindings;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LevelRenderer.class)
public abstract class TerrainDrawPreparationMixin {
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"))
    private ChunkSectionsToRender greenmarine$separate(LevelRenderer renderer, Matrix4fc matrix, boolean sorted) {
        return TerrainDrawBindings.enabled() ? renderer.prepareChunkRenders(matrix, sorted) : renderer.prepareChunkRendersIndirect(matrix, sorted);
    }

    @WrapMethod(method="prepareChunkRenders")
    private ChunkSectionsToRender greenmarine$capture(Matrix4fc matrix, boolean sorted, Operation<ChunkSectionsToRender> original) {
        if (!TerrainDrawBindings.enabled()) return original.call(matrix, sorted);
        var previous = TerrainDrawBindings.begin();
        try {
            var result = original.call(matrix, sorted);
            ((TerrainDrawBindings.Attachment) result).greenmarine$terrainDraws(TerrainDrawBindings.current());
            return result;
        } finally { TerrainDrawBindings.end(previous); }
    }
}
