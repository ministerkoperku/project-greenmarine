package com.koper.greenmarine.client;

import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.mixin.VulkanDeviceAccessor;
import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.backend.vulkan.init.*;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK12.*;

/** Device-enabled graphics-stage contract, separate from physical support and rendering acceptance. */
public final class GraphicsStageFeatures {
    public static final FeatureSet GEOMETRY=set("GreenMarine geometry shaders","geometryShader");
    public static final FeatureSet TESSELLATION=set("GreenMarine tessellation shaders","tessellationShader");
    public static final List<FeatureSet> OPTIONAL=List.of(GEOMETRY,TESSELLATION);
    private GraphicsStageFeatures() {}
    private static FeatureSet set(String name,String feature) {return new FeatureSet(name,Set.of(),Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,feature)));}
    public static void require(VulkanDevice device,ShaderProgram program) {
        if(program.stages().isEmpty())return;
        if(device==null)throw new UnsupportedOperationException("Extended graphics stages require the Vulkan backend");
        var enabled=((VulkanDeviceAccessor)(Object)device).greenmarine$enabledFeatures();
        if(program.stages().containsKey(GraphicsStage.GEOMETRY)&&!StorageFeatures.enabled(enabled,GEOMETRY))throw new UnsupportedOperationException("Host device did not enable geometryShader");
        if(program.stages().containsKey(GraphicsStage.TESS_CONTROL)&&!StorageFeatures.enabled(enabled,TESSELLATION))throw new UnsupportedOperationException("Host device did not enable tessellationShader");
    }
    public static void validate(VulkanDevice device,List<GraphicsSpirv> modules,int patchVertices) {
        var enabled=((VulkanDeviceAccessor)(Object)device).greenmarine$enabledFeatures();
        try(var stack=MemoryStack.stackPush()) {
            var p=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(),p);var limits=p.limits();
            if(patchVertices>limits.maxTessellationPatchSize())throw new IllegalArgumentException("Input patch exceeds host tessellation limit");
            for(var module:modules) {
                for(int capability:module.capabilities()) {
                    if(!Set.of(0,1,2,3,43,44,50,51,55,56).contains(capability))throw new UnsupportedOperationException("Graphics SPIR-V capability is not implemented: "+capability);
                    if(capability==2&&!StorageFeatures.enabled(enabled,GEOMETRY))throw new UnsupportedOperationException("Host device did not enable geometryShader");
                    if(capability==3&&!StorageFeatures.enabled(enabled,TESSELLATION))throw new UnsupportedOperationException("Host device did not enable tessellationShader");
                    if(capability==55 || capability==56)if(!StorageFeatures.enabled(enabled,StorageFeatures.FORMATLESS))throw new UnsupportedOperationException("Host device did not enable format-less storage images");
                }
                validateLimits(module,limits);
            }
        }
    }
    static void validateLimits(GraphicsSpirv module,VkPhysicalDeviceLimits limits) {
                int in=module.inputs().values().stream().mapToInt(GraphicsSpirv.Value::vector).sum(),out=module.outputs().values().stream().mapToInt(GraphicsSpirv.Value::vector).sum();
                switch(module.stage()) {
                    case GEOMETRY -> {
                        out=module.geometryOutputComponents();
                        int vertices=module.modeArgument(26,0,-1),invocations=module.modeArgument(0,0,1);
                        if(vertices<1 || vertices>limits.maxGeometryOutputVertices() || invocations<1 || invocations>limits.maxGeometryShaderInvocations()
                            || in>limits.maxGeometryInputComponents() || out>limits.maxGeometryOutputComponents() || (long)out*vertices>limits.maxGeometryTotalOutputComponents())throw new IllegalArgumentException("Geometry stage exceeds actual host limits");
                    }
                    case TESS_CONTROL -> {
                        int vertices=module.modeArgument(26,0,-1);
                        if(vertices<1 || vertices>limits.maxTessellationPatchSize() || in>limits.maxTessellationControlPerVertexInputComponents()
                            || out>limits.maxTessellationControlPerVertexOutputComponents() || (long)out*vertices>limits.maxTessellationControlTotalOutputComponents())throw new IllegalArgumentException("Tessellation control stage exceeds actual host limits");
                    }
                    case TESS_EVALUATION -> {if(in>limits.maxTessellationEvaluationInputComponents() || out>limits.maxTessellationEvaluationOutputComponents())throw new IllegalArgumentException("Tessellation evaluation stage exceeds actual host limits");}
                    default -> {}
                }
    }

}
