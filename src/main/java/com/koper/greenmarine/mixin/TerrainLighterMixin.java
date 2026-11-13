package com.koper.greenmarine.mixin;
import com.koper.greenmarine.client.terrain.TerrainMesher;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockModelLighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockModelLighter.class)
public abstract class TerrainLighterMixin {
    @Redirect(method="prepareQuadAmbientOcclusion",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/QuadInstance;scaleColor(F)V"))
    private void greenmarine$rawAo(QuadInstance instance,float brightness){TerrainMesher.ambient(instance);instance.scaleColor(brightness);}
    @Inject(method="prepareQuadFlat",at=@At("HEAD"))
    private void greenmarine$flat(CallbackInfo ci){TerrainMesher.flat();}
}
