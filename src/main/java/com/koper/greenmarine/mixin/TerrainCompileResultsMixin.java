package com.koper.greenmarine.mixin;
import com.koper.greenmarine.client.terrain.*;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SectionCompiler.Results.class)
public abstract class TerrainCompileResultsMixin implements TerrainAttachment {
    @Unique private TerrainSidecarData greenmarine$terrain;
    public TerrainSidecarData greenmarine$terrainData(){return greenmarine$terrain;}
    public void greenmarine$terrainData(TerrainSidecarData data){greenmarine$terrain=data;}
    @Inject(method="release",at=@At("HEAD"))
    private void greenmarine$release(CallbackInfo ci){if(greenmarine$terrain!=null){greenmarine$terrain.close();greenmarine$terrain=null;}}
}
