package com.koper.greenmarine.client;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
import java.util.function.Supplier;
import static org.lwjgl.vulkan.VK12.*;

/** Transactional augmentation of the host pipeline; no device or command encoder is substituted. */
public final class GraphicsStagePipelines {
    private enum Kind {MODULE,SET_LAYOUT,LAYOUT,PIPELINE}
    private record Handle(Kind kind,long value) {}
    private static final ThreadLocal<Deque<Compilation>> ACTIVE=ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Compilation {
        final VulkanDevice device;final GraphicsStageBuild build;final int[] visibility;
        final List<Handle> owned=new ArrayList<>();final List<Long> extraModules=new ArrayList<>();int visibilityIndex;
        Compilation(VulkanDevice device,GraphicsStageBuild build,BackendRenderPipeline.CreateInfo info){this.device=device;this.build=build;visibility=build==null?null:build.visibility(info.uniforms());}
        void track(Kind kind,long handle){if(handle!=0){var item=new Handle(kind,handle);if(!owned.contains(item))owned.add(item);}}
        void destroy(Handle handle){switch(handle.kind){case MODULE->vkDestroyShaderModule(device.vkDevice(),handle.value,null);case SET_LAYOUT->vkDestroyDescriptorSetLayout(device.vkDevice(),handle.value,null);case LAYOUT->vkDestroyPipelineLayout(device.vkDevice(),handle.value,null);case PIPELINE->vkDestroyPipeline(device.vkDevice(),handle.value,null);}}
        void finish(boolean transferred){
            // Extra modules are needed only during creation; pipeline objects contain compiled code.
            for(long module:extraModules)vkDestroyShaderModule(device.vkDevice(),module,null);
            if(!transferred)for(int i=owned.size()-1;i>=0;i--)destroy(owned.get(i));
        }
    }
    private GraphicsStagePipelines() {}
    private static Compilation current(){var stack=ACTIVE.get();return stack.isEmpty()?null:stack.peek();}
    public static VulkanRenderPipeline compile(VulkanDevice device,BackendRenderPipeline.CreateInfo info,Supplier<VulkanRenderPipeline> original) {
        var build=GraphicsStageBuild.take(info);if(build!=null)GraphicsStageFeatures.validate(device,build.chain(),build.patchVertices());
        var scope=new Compilation(device,build,info);ACTIVE.get().push(scope);boolean transferred=false;
        try {var pipeline=original.get();transferred=pipeline!=null;return pipeline;}
        finally {try{scope.finish(transferred);}finally{var stack=ACTIVE.get();stack.pop();if(stack.isEmpty())ACTIVE.remove();}}
    }
    public static int visibility(int original) {var c=current();return c==null||c.visibility==null?original:c.visibility[c.visibilityIndex++];}
    public static int createSet(VkDevice device,VkDescriptorSetLayoutCreateInfo info,VkAllocationCallbacks allocator,LongBuffer output) {
        int result=vkCreateDescriptorSetLayout(device,info,allocator,output);var c=current();if(c!=null&&result==VK_SUCCESS)c.track(Kind.SET_LAYOUT,output.get(output.position()));return result;
    }
    public static int createLayout(VkDevice device,VkPipelineLayoutCreateInfo info,VkAllocationCallbacks allocator,LongBuffer output) {
        int result=vkCreatePipelineLayout(device,info,allocator,output);var c=current();if(c!=null&&result==VK_SUCCESS)c.track(Kind.LAYOUT,output.get(output.position()));return result;
    }
    public static int createModule(VkDevice device,VkShaderModuleCreateInfo info,VkAllocationCallbacks allocator,LongBuffer output) {
        int result=vkCreateShaderModule(device,info,allocator,output);var c=current();if(c!=null&&result==VK_SUCCESS)c.track(Kind.MODULE,output.get(output.position()));return result;
    }
    public static int createPipelines(VkDevice device,long cache,VkGraphicsPipelineCreateInfo.Buffer infos,VkAllocationCallbacks allocator,LongBuffer output) {
        var c=current();if(c==null || c.build==null) {
            for(int i=output.position();i<output.limit();i++)output.put(i,0);
            int result=vkCreateGraphicsPipelines(device,cache,infos,allocator,output);
            if(c!=null)for(int i=output.position();i<output.limit();i++)c.track(Kind.PIPELINE,output.get(i));
            return result;
        }
        if(infos.remaining()!=1)throw new IllegalArgumentException("Extended graphics hook requires the host's single-pipeline create call");
        try(var stack=MemoryStack.stackPush()) {
            var extra=c.build.intermediate();
            if(c.extraModules.isEmpty())for(var shader:extra) {
                byte[] bytes=shader.bytes();var words=stack.malloc(bytes.length);words.put(bytes).flip();
                var moduleInfo=VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(words);var pointer=stack.callocLong(1);
                int result=vkCreateShaderModule(device,moduleInfo,null,pointer);
                if(result!=VK_SUCCESS)throw new IllegalStateException("Intermediate shader module creation failed: "+result);
                c.extraModules.add(pointer.get(0));
            }
            for(int p=infos.position();p<infos.limit();p++) {
                var info=infos.get(p);var old=info.pStages();var oldTessellation=info.pTessellationState();int oldTopology=info.pInputAssemblyState().topology();
                var stages=VkPipelineShaderStageCreateInfo.calloc(old.remaining()+extra.size(),stack);
                for(int i=0;i<old.remaining();i++)stages.get(i).set(old.get(old.position()+i));
                for(int i=0;i<extra.size();i++)stages.get(old.remaining()+i).sType$Default().stage(extra.get(i).stage().vulkanBit()).module(c.extraModules.get(i)).pName(stack.UTF8("main"));
                info.pStages(stages);
                if(c.build.patchVertices()>0) {
                    info.pInputAssemblyState().topology(VK_PRIMITIVE_TOPOLOGY_PATCH_LIST);
                    info.pTessellationState(VkPipelineTessellationStateCreateInfo.calloc(stack).sType$Default().patchControlPoints(c.build.patchVertices()));
                }
                int result;
                try {
                    for(int i=output.position();i<output.limit();i++)output.put(i,0);
                    result=vkCreateGraphicsPipelines(device,cache,infos,allocator,output);
                }
                finally { // Do not retain pointers into this temporary stack between host variants.
                    info.pStages(old);info.pTessellationState(oldTessellation);info.pInputAssemblyState().topology(oldTopology);
                }
                // Vulkan may return non-null partial pipelines on a failed create call.
                for(int i=output.position();i<output.limit();i++)c.track(Kind.PIPELINE,output.get(i));
                return result;
            }
            throw new IllegalArgumentException("Host supplied no graphics pipeline create info");
        }
    }
}
