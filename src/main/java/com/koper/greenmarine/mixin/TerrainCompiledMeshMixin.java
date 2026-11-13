package com.koper.greenmarine.mixin;
import com.koper.greenmarine.client.terrain.*;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(CompiledSectionMesh.class)
public abstract class TerrainCompiledMeshMixin implements TerrainAttachment {
    @Unique private volatile TerrainSidecarData greenmarine$terrain;
    public TerrainSidecarData greenmarine$terrainData(){return greenmarine$terrain;}
    public void greenmarine$terrainData(TerrainSidecarData data){greenmarine$terrain=data;}
    @Inject(method="<init>",at=@At("RETURN"))
    private void greenmarine$transfer(TranslucencyPointOfView pov,SectionCompiler.Results results,long started,CallbackInfo ci){var source=(TerrainAttachment)(Object)results;var data=source.greenmarine$terrainData();source.greenmarine$terrainData(null);if(data!=null&&data.current()){greenmarine$terrain=data;TerrainSidecars.published(this);}else if(data!=null)data.close();}
    @Inject(method="close",at=@At("HEAD"))
    private void greenmarine$release(CallbackInfo ci){TerrainSidecars.release(this);greenmarine$terrain=null;}
}
