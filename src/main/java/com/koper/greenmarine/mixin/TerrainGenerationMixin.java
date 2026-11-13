package com.koper.greenmarine.mixin;
import com.koper.greenmarine.api.render.TerrainCapture;
import com.koper.greenmarine.client.terrain.TerrainSidecars;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelRenderer.class)
public abstract class TerrainGenerationMixin {
    @Inject(method={"resetLevelRenderData","invalidateCompiledGeometry"},at=@At("HEAD"))
    private void greenmarine$invalidate(CallbackInfo ci){TerrainCapture.invalidate();TerrainSidecars.collectRetired();}
    @Inject(method="render",at=@At("HEAD"))
    private void greenmarine$collect(CallbackInfo ci){TerrainSidecars.collectRetired();}
}
