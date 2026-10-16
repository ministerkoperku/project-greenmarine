package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.rt.RayWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Includes server-verified edits, chunk replacement and biome tint changes. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelGeometryMixin {
    @Inject(method="setBlocksDirty",at=@At("TAIL"))
    private void greenmarine$blockGeometry(BlockPos pos,BlockState before,BlockState after,CallbackInfo ci) {
        RayWorld.invalidateBlock(pos);
    }
    @Inject(method="onChunkLoaded",at=@At("TAIL"))
    private void greenmarine$columnGeometry(ChunkPos pos,CallbackInfo ci) {RayWorld.invalidateColumn(pos.x(),pos.z());}
    @Inject(method="unload",at=@At("TAIL"))
    private void greenmarine$unloadGeometry(LevelChunk chunk,CallbackInfo ci) {
        var pos=chunk.getPos();RayWorld.unloadColumn(pos.x(),pos.z());
    }
    @Inject(method="clearTintCaches",at=@At("TAIL"))
    private void greenmarine$tintGeometry(CallbackInfo ci) {RayWorld.invalidateGeometry();}
}
