package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.RasterStorage;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.Map;

/** Lets registered names reflect as storage buffers/images instead of failing the type check. */
@Mixin(PipelineBuilder.class)
public abstract class PipelineStorageMixin {
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
