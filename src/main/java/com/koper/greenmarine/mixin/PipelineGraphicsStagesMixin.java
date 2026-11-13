package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.GraphicsStageBuild;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.*;
import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Retains host vertex/binding validation while validating the actual registered graphics chain. */
@Mixin(PipelineBuilder.class)
public abstract class PipelineGraphicsStagesMixin {
    @Shadow private static Int2ObjectMap<?> generateSlotMap(List<SpvModule.Reflection.InterfaceVariable> variables,String name,ShaderType type){throw new AssertionError();}
    @WrapMethod(method="generateBackendCreateInfo")
    private BackendRenderPipeline.CreateInfo greenmarine$build(RenderPipeline pipeline,ShaderSource source,ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders,Object2IntOpenHashMap<String> bindings,Operation<BackendRenderPipeline.CreateInfo> original) {
        boolean begun=false;
        try {
            var build=GraphicsStageBuild.begin(source);begun=true;
            var result=original.call(pipeline,source,shaders,bindings);if(result!=null&&build!=null)build.validate(shaders);
            GraphicsStageBuild.attach(result,build);return result;
        }catch(RuntimeException|Error error) {
            // Host handles ShaderCompileException itself; runtime validation failures have no owner.
            for(var shader:shaders)try{shader.module().close();}catch(RuntimeException|Error cleanup){error.addSuppressed(cleanup);}shaders.clear();throw error;
        }finally{if(begun)GraphicsStageBuild.end();}
    }
    @Redirect(method="generateBackendCreateInfo",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/frontend/shaders/PipelineBuilder;generateSlotMap(Ljava/util/List;Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;)Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;"))
    private Int2ObjectMap<?> greenmarine$chain(List<SpvModule.Reflection.InterfaceVariable> variables,String name,ShaderType type,RenderPipeline pipeline,ShaderSource source,ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders,Object2IntOpenHashMap<String> bindings) {
        var build=GraphicsStageBuild.current();if(build==null)return generateSlotMap(variables,name,type);
        build.validate(shaders);return Int2ObjectMaps.emptyMap();
    }
    @Redirect(method="generateBackendCreateInfo",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/api/SpvModule$Reflection;descriptors()Ljava/util/List;"))
    private List<SpvModule.Reflection.Descriptor> greenmarine$descriptors(SpvModule.Reflection reflection) {
        var build=GraphicsStageBuild.current();return build==null?reflection.descriptors():build.descriptors(reflection);
    }
}
