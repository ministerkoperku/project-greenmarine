package com.koper.greenmarine.mixin;
import com.koper.greenmarine.client.terrain.TerrainMesher;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FluidRenderer.class)
public abstract class TerrainFluidMixin {
    @Inject(method="tesselate",at=@At("HEAD"))
    private void greenmarine$fluid(BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,BlockState host,FluidState fluid,CallbackInfo ci){TerrainMesher.block(pos,fluid.createLegacyBlock(),true);}
    @Redirect(method="tesselate",at=@At(value="INVOKE",target="Lnet/minecraft/util/ARGB;scaleRGB(IF)I"))
    private int greenmarine$unlit(int color,float brightness){TerrainMesher.tint(color);return ARGB.scaleRGB(color,brightness);}
    @Inject(method="vertex",at=@At("HEAD"))
    private void greenmarine$vertex(VertexConsumer consumer,float x,float y,float z,int color,float u,float v,int light,CallbackInfo ci){TerrainMesher.fluidVertex(consumer,x,y,z,u,v);}
}
