package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import java.lang.foreign.Arena;
import java.nio.*;
import java.util.*;

/** Render-thread std140 uploads. Returned slices belong to the host's current submission batch.
 * CPU storage is bounded by 64 blocks of 64 KiB and is reusable immediately after uploadGpu copies it.
 */
public final class PackUniforms implements AutoCloseable {
    private static final int MAX_BLOCKS=64,MAX_BYTES=64*1024;
    // Renderpearl omits max UBO size. Non-Vulkan backends use the 16 KiB portable minimum;
    // they may support larger blocks, but accepting those requires a backend-specific query.
    private static final int PORTABLE_UNIFORM_RANGE=16*1024;
    private static final class Scratch implements AutoCloseable {
        final Arena arena;
        final ByteBuffer data;
        Scratch(int size) {
            arena=Arena.ofConfined();
            try {data=arena.allocate(size,16).asByteBuffer().order(ByteOrder.nativeOrder());}
            catch(RuntimeException|Error failure) {arena.close();throw failure;}
        }
        @Override public void close() {arena.close();}
    }
    private final Map<String,Scratch> scratch=new LinkedHashMap<>();
    private Map<String,GpuBufferSlice> slices=Map.of();
    private boolean closed;
    private Object rangeDevice;
    private long cachedUniformRange;

    static void validateRanges(Map<String,ByteBuffer> selected,long maxUniformRange) {
        if(maxUniformRange<1)throw new IllegalArgumentException("Invalid host uniform buffer range: "+maxUniformRange);
        for(var entry:selected.entrySet())if(entry.getValue().remaining()>maxUniformRange)
            throw new IllegalArgumentException("Uniform resource "+entry.getKey()+" needs "+entry.getValue().remaining()
                +" bytes; host uniform buffer range is "+maxUniformRange);
    }

    private long uniformRange(GpuDevice device) {
        var vk=ComputeBackend.vulkan(device);
        Object identity=vk==null?device:vk;
        if(rangeDevice==identity)return cachedUniformRange;
        long range=PORTABLE_UNIFORM_RANGE;
        if(vk!=null)try(var stack=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceProperties.calloc(stack);
            org.lwjgl.vulkan.VK12.vkGetPhysicalDeviceProperties(vk.vkDevice().getPhysicalDevice(),properties);
            range=Integer.toUnsignedLong(properties.limits().maxUniformBufferRange());
        }
        if(range<1)throw new IllegalStateException("Host device reports an invalid uniform buffer range");
        cachedUniformRange=range;rangeDevice=identity;
        return range;
    }

    /** All selected blocks validate and serialize before the first host upload is attempted.
     * A preparation failure unpublishes the previous frame's slices; it cannot bind stale values.
     */
    public Map<String,GpuBufferSlice> prepare(PackResources resources,Set<String> usedResources,CommandEncoder encoder,Map<String,?> values) {
        var staged=stage(resources,usedResources,values);
        if(staged.isEmpty())return slices;
        Objects.requireNonNull(encoder,"Uniform upload encoder");
        var device=RenderSystem.getDevice();
        validateRanges(staged,uniformRange(device));
        long alignment=device.getDeviceInfo().limits().minUniformOffsetAlignment();
        var uploaded=new LinkedHashMap<String,GpuBufferSlice>();
        for(var entry:staged.entrySet())
            uploaded.put(entry.getKey(),encoder.transientMemory().uploadGpu(entry.getValue(),alignment,GpuBuffer.USAGE_UNIFORM));
        slices=Collections.unmodifiableMap(uploaded);
        return slices;
    }

    /** Portable staging half of prepare; these borrowed CPU views expire at the next staging/reset. */
    Map<String,ByteBuffer> stage(PackResources resources,Set<String> usedResources,Map<String,?> values) {
        requireOpen();slices=Map.of();
        Objects.requireNonNull(resources,"Uniform resources");Objects.requireNonNull(usedResources,"Selected uniform resources");
        if(usedResources.size()>MAX_BLOCKS)throw new IllegalArgumentException("At most 64 uniform resources can be selected");
        for(var name:usedResources)if(!resources.uniforms().containsKey(name))
            throw new IllegalArgumentException("Unknown uniform resource: "+name);
        var selected=new LinkedHashMap<String,PackUniformBlock>();
        for(var entry:resources.uniforms().entrySet())if(usedResources.contains(entry.getKey())) {
            int size=entry.getValue().layout().byteSize();
            if(size<16 || size>MAX_BYTES)throw new IllegalArgumentException("Uniform resource exceeds 64 KiB: "+entry.getKey());
            selected.put(entry.getKey(),entry.getValue());
        }
        // Removed/renamed declarations cannot accumulate native storage across reloads or passes.
        var retained=scratch.entrySet().iterator();
        while(retained.hasNext()) {var entry=retained.next();if(!selected.containsKey(entry.getKey())) {entry.getValue().close();retained.remove();}}
        var staged=new LinkedHashMap<String,ByteBuffer>();
        for(var entry:selected.entrySet()) {
            String name=entry.getKey();int size=entry.getValue().layout().byteSize();
            var storage=scratch.get(name);
            if(storage!=null && storage.data.capacity()!=size) {storage.close();scratch.remove(name);storage=null;}
            if(storage==null) {storage=new Scratch(size);scratch.put(name,storage);}
            entry.getValue().write(storage.data,values);
            staged.put(name,storage.data.asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
        }
        return Collections.unmodifiableMap(staged);
    }

    public GpuBufferSlice slice(String resource) {
        requireOpen();var slice=slices.get(resource);
        if(slice==null)throw new IllegalArgumentException("Uniform resource was not prepared for this frame: "+resource);
        return slice;
    }

    /** Resolve every binding before mutating the pass, then bind the prepared frame's immutable slices. */
    public void bindRaster(RenderPass pass,Map<String,String> blockToResource) {
        requireOpen();Objects.requireNonNull(pass,"Uniform render pass");
        var bindings=new LinkedHashMap<String,GpuBufferSlice>();
        for(var entry:blockToResource.entrySet())bindings.put(entry.getKey(),slice(entry.getValue()));
        for(var entry:bindings.entrySet())pass.setUniform(entry.getKey(),entry.getValue());
    }

    /** Host uploads already copied the CPU bytes; no GPU wait is needed to release scratch storage. */
    public void reset() {
        slices=Map.of();for(var storage:scratch.values())storage.close();scratch.clear();
        rangeDevice=null;cachedUniformRange=0;
    }
    private void requireOpen() {if(closed)throw new IllegalStateException("Pack uniform storage is closed");}
    @Override public void close() {if(!closed) {reset();closed=true;}}
}
