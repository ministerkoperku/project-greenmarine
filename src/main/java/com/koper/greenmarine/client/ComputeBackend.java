package com.koper.greenmarine.client;

import com.koper.greenmarine.mixin.FrontendGpuDeviceAccessor;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Compute on the host's own device and command stream. Renderpearl has no compute facade, so this
 * records a transient command buffer the same way hardware RT does, between full memory barriers
 * (every host image already lives in GENERAL layout).
 */
public final class ComputeBackend {
    public enum Kind {
        UNIFORM_BUFFER(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER), STORAGE_BUFFER(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
        STORAGE_IMAGE(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE), SAMPLED_TEXTURE(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        final int vk;
        Kind(int vk) { this.vk = vk; }
    }
    public record Slot(String name, int binding, Kind kind) {}
    public record Reflection(List<Slot> slots, int localX, int localY, int localZ) {}
    public sealed interface Bound permits BufferRange, StorageImage, SampledImage {}
    public record BufferRange(long buffer, long offset, long size) implements Bound {}
    public record StorageImage(long view) implements Bound {}
    public record SampledImage(long view, long sampler) implements Bound {}
    public record Limits(int maxInvocations, int[] maxGroupCount, int[] maxGroupSize) {}

    public static final int MAX_SOURCE = 2 * 1024 * 1024, MAX_BINDINGS = 32;
    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#include[ \\t]+[<\"]([^>\"]+)[>\"][^\\n]*$");
    private static final Set<Object> STORAGE = Collections.newSetFromMap(new WeakHashMap<>());
    private ComputeBackend() {}

    public static VulkanDevice vulkan(GpuDevice device) {
        return device instanceof FrontendGpuDeviceAccessor frontend && frontend.greenmarine$backend() instanceof VulkanDevice vk ? vk : null;
    }
    /** Remembers host objects created with storage usage; validation only, never ownership. */
    public static <T> T storage(T resource) { STORAGE.add(resource); return resource; }
    public static boolean isStorage(Object resource) { return STORAGE.contains(resource); }

    public static boolean storageFormat(VulkanDevice vk, GpuFormat format) {
        if (!format.hasColorAspect() || format.hasDepthAspect()) return false;
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(vk.vkDevice().getPhysicalDevice(), VulkanConst.toVk(format), properties);
            int needed = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            return (properties.optimalTilingFeatures() & needed) == needed;
        }
    }
    private static VulkanDevice limitsDevice;
    private static Limits cachedLimits;
    public static Limits limits(VulkanDevice vk) {
        if (vk == limitsDevice) return cachedLimits;
        cachedLimits = queryLimits(vk); limitsDevice = vk; return cachedLimits;
    }
    private static Limits queryLimits(VulkanDevice vk) {
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(vk.vkDevice().getPhysicalDevice(), properties);
            var l = properties.limits();
            return new Limits(l.maxComputeWorkGroupInvocations(),
                new int[]{l.maxComputeWorkGroupCount(0), l.maxComputeWorkGroupCount(1), l.maxComputeWorkGroupCount(2)},
                new int[]{l.maxComputeWorkGroupSize(0), l.maxComputeWorkGroupSize(1), l.maxComputeWorkGroupSize(2)});
        }
    }

    private static VulkanDevice testedDevice;
    private static boolean testedResult;
    /** True only once a pipeline was actually built on this host device (initialized path, not a feature bit). */
    public static boolean initialized(GpuDevice device) {
        var vk = vulkan(device);
        if (vk == null) return false;
        if (vk == testedDevice) return testedResult;
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
        testedDevice = vk;
        try {
            var spirv = compile("greenmarine:compute_self_test", "#version 450\nlayout(local_size_x=1) in;\n"
                + "layout(std430) buffer SelfTest { uint value; };\nvoid main() { value = 1u; }\n");
            create(vk, spirv, reflect(spirv)).release(vk);
            testedResult = true;
        } catch (RuntimeException | LinkageError failure) {
            testedResult = false;
            com.koper.greenmarine.GreenMarine.LOGGER.warn("GreenMarine compute path unavailable: {}", failure.toString());
        }
        return testedResult;
    }

    /** `#include <ns:path>` expansion with cycle/depth/size limits; includes are captured snapshots. */
    public static String expand(String source, Function<String, String> includes) {
        return expand(source, includes, new ArrayDeque<>(), 0);
    }
    private static String expand(String source, Function<String, String> includes, Deque<String> active, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Include depth exceeds 16");
        var matcher = INCLUDE.matcher(source); var output = new StringBuilder(); int start = 0;
        while (matcher.find()) {
            output.append(source, start, matcher.start());
            String id = matcher.group(1);
            if (active.contains(id)) throw new IllegalArgumentException("Cyclic include: " + id);
            String text = includes.apply(id);
            if (text == null) throw new IllegalArgumentException("Missing compute include: " + id);
            active.push(id); output.append(expand(text, includes, active, depth + 1)).append('\n'); active.pop();
            start = matcher.end();
            if (output.length() > MAX_SOURCE) throw new IllegalArgumentException("Expanded compute source exceeds 2 MiB");
        }
        output.append(source, start, source.length());
        if (output.length() > MAX_SOURCE) throw new IllegalArgumentException("Expanded compute source exceeds 2 MiB");
        return output.toString();
    }

    /** GLSL to Vulkan 1.2 SPIR-V; unbound resources get distinct automatic bindings in set 0. */
    public static byte[] compile(String name, String source) {
        long compiler = shaderc_compiler_initialize(), options = 0, result = 0;
        if (compiler == 0) throw new IllegalStateException("Shaderc compiler initialization failed");
        try {
            options = shaderc_compile_options_initialize();
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            shaderc_compile_options_set_generate_debug_info(options); // keeps OpName, bindings are matched by name
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, name, "main", options);
            if (result == 0) throw new IllegalStateException("Shaderc returned no result");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalArgumentException("Compute shader " + name + " failed:\n" + shaderc_result_get_error_message(result));
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null || bytes.remaining() == 0 || bytes.remaining() > 4 * 1024 * 1024) throw new IllegalStateException("Invalid compute SPIR-V size");
            byte[] copy = new byte[bytes.remaining()]; bytes.get(copy); return copy;
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    /** Buffers are named by block type (like MarineFrame), images and samplers by variable. */
    public static Reflection reflect(byte[] spirv) {
        if (spirv.length % 4 != 0 || spirv.length < 20) throw new IllegalArgumentException("Invalid SPIR-V size");
        var words = org.lwjgl.system.MemoryUtil.memAlloc(spirv.length);
        try (var stack = MemoryStack.stackPush()) {
            words.order(ByteOrder.LITTLE_ENDIAN).put(spirv).flip();
            PointerBuffer pointer = stack.mallocPointer(1);
            check(spvc_context_create(pointer), "context"); long context = pointer.get(0);
            try {
                check(spvc_context_parse_spirv(context, words.asIntBuffer(), spirv.length / 4, pointer), "parse"); long ir = pointer.get(0);
                check(spvc_context_create_compiler(context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer), "compiler"); long compiler = pointer.get(0);
                if (spvc_compiler_get_execution_model(compiler) != 5) throw new IllegalArgumentException("SPIR-V entry point is not a compute shader"); // ExecutionModelGLCompute
                check(spvc_compiler_create_shader_resources(compiler, pointer), "resources"); long resources = pointer.get(0);
                var slots = new ArrayList<Slot>(); var bindings = new HashSet<Integer>(); var names = new HashSet<String>();
                collect(compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Kind.UNIFORM_BUFFER, slots, stack);
                collect(compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Kind.STORAGE_BUFFER, slots, stack);
                collect(compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, Kind.STORAGE_IMAGE, slots, stack);
                collect(compiler, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, Kind.SAMPLED_TEXTURE, slots, stack);
                for (var type : new int[]{SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
                        SPVC_RESOURCE_TYPE_PUSH_CONSTANT, SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE, SPVC_RESOURCE_TYPE_SUBPASS_INPUT}) {
                    PointerBuffer list = stack.mallocPointer(1), count = stack.mallocPointer(1);
                    check(spvc_resources_get_resource_list_for_type(resources, type, list, count), "resource list");
                    if (count.get(0) != 0) throw new IllegalArgumentException("Unsupported compute resource kind " + type + " (use sampler2D, image2D, uniform or buffer blocks)");
                }
                if (slots.size() > MAX_BINDINGS) throw new IllegalArgumentException("At most " + MAX_BINDINGS + " compute bindings are supported");
                for (var slot : slots) {
                    if (!bindings.add(slot.binding())) throw new IllegalArgumentException("Duplicate compute binding " + slot.binding());
                    if (!names.add(slot.name())) throw new IllegalArgumentException("Duplicate compute resource name " + slot.name());
                }
                slots.sort(Comparator.comparingInt(Slot::binding));
                int x = spvc_compiler_get_execution_mode_argument_by_index(compiler, 17, 0); // ExecutionModeLocalSize
                int y = spvc_compiler_get_execution_mode_argument_by_index(compiler, 17, 1);
                int z = spvc_compiler_get_execution_mode_argument_by_index(compiler, 17, 2);
                return new Reflection(List.copyOf(slots), Math.max(1, x), Math.max(1, y), Math.max(1, z));
            } finally { spvc_context_destroy(context); }
        } finally { org.lwjgl.system.MemoryUtil.memFree(words); }
    }
    private static void collect(long compiler, long resources, int type, Kind kind, List<Slot> slots, MemoryStack stack) {
        PointerBuffer list = stack.mallocPointer(1), count = stack.mallocPointer(1);
        check(spvc_resources_get_resource_list_for_type(resources, type, list, count), "resource list");
        var reflected = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
        for (var resource : reflected) {
            if (spvc_compiler_get_decoration(compiler, resource.id(), 34) != 0) throw new IllegalArgumentException("Compute resources must use descriptor set 0: " + resource.nameString());
            if (kind == Kind.STORAGE_IMAGE || kind == Kind.SAMPLED_TEXTURE) {
                long handle = spvc_compiler_get_type_handle(compiler, resource.type_id());
                int dim = spvc_type_get_image_dimension(handle);
                if (dim != 1 || spvc_type_get_image_arrayed(handle) || spvc_type_get_image_multisampled(handle)) // SpvDim2D
                    throw new IllegalArgumentException("Only non-array 2D images are supported for now: " + resource.nameString());
            }
            String name = resource.nameString();
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Compute resources must be named");
            slots.add(new Slot(name, spvc_compiler_get_decoration(compiler, resource.id(), 33), kind)); // DecorationBinding
        }
    }
    private static void check(int result, String step) {
        if (result != SPVC_SUCCESS) throw new IllegalArgumentException("SPIR-V reflection failed at " + step + " (" + result + ")");
    }

    /** Destroyed through the host destruction queue, so in-flight frames finish first. */
    public static final class Pipeline implements Destroyable {
        private final VkDevice device;
        final long module, setLayout, layout, pipeline;
        final Reflection reflection;
        private Pipeline(VkDevice device, long module, long setLayout, long layout, long pipeline, Reflection reflection) {
            this.device = device; this.module = module; this.setLayout = setLayout; this.layout = layout; this.pipeline = pipeline; this.reflection = reflection;
        }
        public Reflection reflection() { return reflection; }
        @Override public void destroy() {
            vkDestroyPipeline(device, pipeline, null); vkDestroyPipelineLayout(device, layout, null);
            vkDestroyDescriptorSetLayout(device, setLayout, null); vkDestroyShaderModule(device, module, null);
        }
        public void release(VulkanDevice vk) { vk.createCommandEncoder().queueForDestroy(this); }
    }

    public static Pipeline create(VulkanDevice vk, byte[] spirv, Reflection reflection) {
        var device = vk.vkDevice(); long module = 0, setLayout = 0, layout = 0;
        try (var stack = MemoryStack.stackPush()) {
            var code = stack.malloc(spirv.length).put(spirv).flip();
            var longs = stack.mallocLong(1);
            var moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            vk("vkCreateShaderModule", vkCreateShaderModule(device, moduleInfo, null, longs)); module = longs.get(0);
            var bindings = VkDescriptorSetLayoutBinding.calloc(reflection.slots().size(), stack);
            for (int i = 0; i < reflection.slots().size(); i++) {
                var slot = reflection.slots().get(i);
                bindings.get(i).binding(slot.binding()).descriptorType(slot.kind().vk).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            var setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(bindings);
            vk("vkCreateDescriptorSetLayout", vkCreateDescriptorSetLayout(device, setInfo, null, longs)); setLayout = longs.get(0);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
            vk("vkCreatePipelineLayout", vkCreatePipelineLayout(device, layoutInfo, null, longs)); layout = longs.get(0);
            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(module).pName(stack.UTF8("main"));
            var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().stage(stage).layout(layout);
            vk("vkCreateComputePipelines", vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, longs));
            return new Pipeline(device, module, setLayout, layout, longs.get(0), reflection);
        } catch (RuntimeException | Error failure) {
            if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (module != 0) vkDestroyShaderModule(device, module, null);
            throw failure;
        }
    }

    /** Resources are in slot order; the host keeps them alive through its destruction queue. */
    public static void dispatch(CommandEncoder encoder, Pipeline pipeline, List<Bound> resources, int x, int y, int z) {
        if (!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder backend))
            throw new UnsupportedOperationException("Compute requires the Vulkan backend");
        if (frontend.isInRenderPass()) throw new IllegalStateException("Compute cannot be recorded inside a render pass");
        var slots = pipeline.reflection.slots();
        if (resources.size() != slots.size()) throw new IllegalArgumentException("Compute bindings incomplete");
        VkCommandBuffer command = backend.allocateAndBeginTransientCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            barrier(command, stack, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline);
            if (!slots.isEmpty()) {
                var writes = VkWriteDescriptorSet.calloc(slots.size(), stack);
                for (int i = 0; i < slots.size(); i++) {
                    var slot = slots.get(i); var write = writes.get(i).sType$Default().dstBinding(slot.binding()).descriptorType(slot.kind().vk);
                    switch (resources.get(i)) {
                        case BufferRange b -> write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(b.buffer()).offset(b.offset()).range(b.size()));
                        case StorageImage image -> write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                        case SampledImage image -> write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.view()).sampler(image.sampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                    }
                    write.descriptorCount(1);
                }
                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout, 0, writes);
            }
            vkCmdDispatch(command, x, y, z);
            barrier(command, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vk("vkEndCommandBuffer", vkEndCommandBuffer(command));
        }
        backend.execute(command);
    }
    private static void barrier(VkCommandBuffer command, MemoryStack stack, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        var memory = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier(command, srcStage, dstStage, 0, memory, null, null);
    }
    private static void vk(String call, int result) { if (result != VK_SUCCESS) throw new IllegalStateException(call + " failed: " + result); }
}
