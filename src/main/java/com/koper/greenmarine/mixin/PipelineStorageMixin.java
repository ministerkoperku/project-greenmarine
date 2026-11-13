package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.RasterStorage;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;

/** Lets registered names reflect as storage buffers/images instead of failing the type check. */
@Mixin(PipelineBuilder.class)
public abstract class PipelineStorageMixin {
    /** Validate actual modules on every pipeline build, including cached/precompiled shader modules. */
    @Inject(method="generateBackendCreateInfo",at=@At("RETURN"))
    private void greenmarine$uniformAbi(RenderPipeline pipeline,ShaderSource source,
                                        it.unimi.dsi.fastutil.objects.ReferenceArrayList<com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo.Shader> shaders,
                                        it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> bindings,
                                        CallbackInfoReturnable<com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo> ci) {
        if(ci.getReturnValue()!=null && source instanceof com.koper.greenmarine.client.BinaryShaderSource pack) {
            try {for(var shader:shaders)pack.validateModule(shader.name(),shader.module().type(),shader.module());}
            catch(RuntimeException|LinkageError failure) {
                // The host only handles ShaderCompileException here. No owner receives these modules on rejection.
                for(var shader:shaders)try {shader.module().close();}
                    catch(RuntimeException|LinkageError cleanup) {failure.addSuppressed(cleanup);}
                shaders.clear();throw failure;
            }
        }
    }
    /** Keep the host's cross-stage dimensionality check, then allow the 3D descriptor kind on GM's Vulkan path. */
    @Redirect(method="generateBackendCreateInfo",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/ints/IntList;getInt(I)I",ordinal=3))
    private int greenmarine$volumeSampler(it.unimi.dsi.fastutil.ints.IntList dimensions,int index,
                                         RenderPipeline pipeline,ShaderSource source,
                                         it.unimi.dsi.fastutil.objects.ReferenceArrayList<?> shaders,it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> bindings) {
        int value=dimensions.getInt(index);
        return (value==0 || value==2) && source instanceof com.koper.greenmarine.client.BinaryShaderSource
            && com.koper.greenmarine.client.ComputeBackend.vulkan(com.mojang.blaze3d.systems.RenderSystem.getDevice())!=null?1:value;
    }
    @Redirect(method="generateBackendCreateInfo",at=@At(value="INVOKE",target="Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object greenmarine$name(Map<?,?> map,Object key) {
        RasterStorage.lookedUp(key);
        return map.get(key);
    }
    @Redirect(method="generateBackendCreateInfo",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/frontend/shaders/SpvUtil;resourceType(Lcom/mojang/renderpearl/api/pipeline/UniformType;)I"))
    private int greenmarine$storageType(UniformType type,RenderPipeline pipeline,ShaderSource source,
                                        it.unimi.dsi.fastutil.objects.ReferenceArrayList<?> shaders,it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> bindings) {
        return RasterStorage.resourceType(pipeline.getLocation().toString(),com.mojang.renderpearl.frontend.shaders.SpvUtil.resourceType(type));
    }
}
