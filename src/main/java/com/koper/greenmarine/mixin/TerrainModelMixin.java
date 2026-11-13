package com.koper.greenmarine.mixin;
import com.koper.greenmarine.client.terrain.TerrainMesher;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelBlockRenderer.class)
public abstract class TerrainModelMixin {
    @Inject(method="tesselateBlock",at=@At("HEAD"))
    private void greenmarine$block(BlockQuadOutput out,float x,float y,float z,BlockAndTintGetter level,BlockPos pos,BlockState state,BlockStateModel model,long seed,CallbackInfo ci){TerrainMesher.block(pos,state,false);}
    @Inject(method="putQuadWithTint",at=@At("HEAD"))
    private void greenmarine$white(CallbackInfo ci){TerrainMesher.whiteTint();}
    @Redirect(method="putQuadWithTint",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/QuadInstance;multiplyColor(I)V"))
    private void greenmarine$tint(QuadInstance instance,int tint){TerrainMesher.tint(tint);instance.multiplyColor(tint);}
}
