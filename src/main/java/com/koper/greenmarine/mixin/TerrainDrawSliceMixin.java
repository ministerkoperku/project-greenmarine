package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.terrain.TerrainDrawBindings;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.class)
public abstract class TerrainDrawSliceMixin {
    @Inject(method="getRenderSectionSlice",at=@At("RETURN"))
    private void greenmarine$observe(SectionMesh mesh, ChunkSectionLayer layer, CallbackInfoReturnable<SectionRenderDispatcher.RenderSectionBufferSlice> ci) {
        TerrainDrawBindings.observe(mesh, layer, ci.getReturnValue());
    }
}
