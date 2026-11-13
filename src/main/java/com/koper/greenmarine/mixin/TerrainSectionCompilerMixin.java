package com.koper.greenmarine.mixin;
import com.koper.greenmarine.client.terrain.TerrainMesher;
import com.mojang.blaze3d.vertex.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;
@Mixin(SectionCompiler.class)
public abstract class TerrainSectionCompilerMixin {
    @WrapMethod(method="compile")
    private SectionCompiler.Results greenmarine$capture(SectionPos pos,RenderSectionRegion region,VertexSorting sorting,SectionBufferBuilderPack buffers,Operation<SectionCompiler.Results> original){
        TerrainMesher.begin(pos);
        try {var results=original.call(pos,region,sorting,buffers);TerrainMesher.finish(results);return results;}
        finally {TerrainMesher.discard();}
    }
    @Inject(method="getOrBeginLayer",at=@At("RETURN"))
    private void greenmarine$layer(Map<ChunkSectionLayer,BufferBuilder> map,SectionBufferBuilderPack pack,ChunkSectionLayer layer,CallbackInfoReturnable<BufferBuilder> ci){TerrainMesher.builder(ci.getReturnValue(),layer);}
    @Redirect(method={"lambda$compile$0","lambda$compile$1"},at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/BufferBuilder;putBlockBakedQuad(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void greenmarine$quad(BufferBuilder builder,float x,float y,float z,BakedQuad quad,QuadInstance instance){TerrainMesher.quad(builder,x,y,z,quad);builder.putBlockBakedQuad(x,y,z,quad,instance);}
}
