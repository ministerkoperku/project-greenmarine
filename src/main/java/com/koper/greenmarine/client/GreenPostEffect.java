package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

public final class GreenPostEffect {
    private static TextureTarget scratch;
    private static RenderPipeline pipeline;
    private static final ByteBuffer UNIFORMS = ByteBuffer.allocateDirect(WorldLightFrame.BYTES).order(ByteOrder.nativeOrder());
    private static final Matrix4f WORLD_PROJECTION = new Matrix4f();
    private static final Matrix4f CLIP = new Matrix4f();
    private static boolean failed, logged;
    private static boolean voxelPipeline;
    private static GpuTimer gpuTimer;
    private static final PerformanceSamples CPU = new PerformanceSamples();
    private static long frames;

    private GreenPostEffect() {}

    public static void captureProjection(Matrix4f matrix) { WORLD_PROJECTION.set(matrix); }

    public static void render(GameRenderer renderer) {
        VulkanHost.attachIfReady();
        if (failed || !GreenMarineClient.enabled() || !renderer.gameRenderState().shouldRenderLevel) return;
        var engine = GreenMarineClient.engine();
        var main = renderer.mainRenderTarget();
        if (main.width <= 0 || main.height <= 0 || main.getColorTextureView() == null || main.getDepthTextureView() == null) return;
        try {
            long started=System.nanoTime();
            var settings = GreenMarineClient.config();
            float time = GreenMarineClient.seconds(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
            float pulse = PulseSampler.sample(engine,time * settings.pulseSpeed(), 1);
            var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
            UNIFORMS.clear();
            var gpu = RenderSystem.getDevice();
            WorldLightFrame.writeCamera(UNIFORMS, WORLD_PROJECTION, camera.viewRotationMatrix,
                main.width, main.height, gpu.getDeviceInfo().isZZeroToOne());
            UNIFORMS.putFloat(16, settings.bloomThreshold()).putFloat(20, settings.bloomStrength())
                .putFloat(24, settings.bloomRadius()).putFloat(28, 0);
            int lightCount = WorldLightFrame.writeLights(UNIFORMS, GreenMarine.LIGHTS.snapshot(),
                camera.pos.x, camera.pos.y, camera.pos.z, pulse,CLIP.set(WORLD_PROJECTION).mul(camera.viewRotationMatrix));
            if (lightCount == 0 && settings.bloomStrength() == 0) return;
            ensureTarget(main);
            boolean voxels=ShaderRuntime.rays().effective()==com.koper.greenmarine.api.pack.RayMode.VOXEL;
            if(voxelPipeline!=voxels) {
                pipeline=pipelineFor(main.getColorTexture().getFormat(),voxels); voxelPipeline=voxels;
                frames=0; CPU.clear(); if(gpuTimer!=null) gpuTimer.close(); gpuTimer=null;
            }
            UNIFORMS.position(WorldLightFrame.BYTES).flip();
            var encoder = gpu.createCommandEncoder();
            boolean profiling=ShaderRuntime.settings().profiling();
            if(!profiling && gpuTimer!=null) resetProfiling();
            if(profiling && gpuTimer==null) {
                try { gpuTimer=new GpuTimer(); }
                catch(RuntimeException unavailable) { GreenMarine.LOGGER.warn("Point-light GPU profiling unavailable: {}",unavailable.getMessage()); }
            }
            int measurement=profiling && frames>=40 && gpuTimer!=null?gpuTimer.begin(encoder):-1;
            var data = encoder.transientMemory().uploadGpu(UNIFORMS, gpu.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
            GpuBuffer voxelData=voxels?VoxelWorld.prepare(encoder,camera.pos.x,camera.pos.y,camera.pos.z):null;
            try (var pass = encoder.createRenderPass(() -> "GreenMarine world lights", scratch.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                pass.setUniform("GreenFx", data);
                pass.setUniform("ColorSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                if(voxels) pass.setUniform("VoxelFrame",voxelData);
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToTexture(scratch.getColorTexture(), main.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            if(profiling && gpuTimer!=null) gpuTimer.end(encoder,measurement);
            encoder.submit();
            frames++; if(frames>40) CPU.add((System.nanoTime()-started)/1_000_000.0);
            if (!logged) {
                logged = true;
                GreenMarine.LOGGER.info("GreenMarine world-light pass submitted: {}x{}, lights={}, native pulse={}, backend={}",
                    main.width, main.height, lightCount, pulse, gpu.getDeviceInfo().backendName());
            }
        } catch (RuntimeException | LinkageError error) {
            failed = true;
            GreenMarine.LOGGER.error("GreenMarine world lighting disabled until resource reload", error);
        }
    }

    private static void ensureTarget(RenderTarget main) {
        if (scratch != null && scratch.getColorTexture().getFormat() != main.getColorTexture().getFormat()) close();
        if (scratch == null) {
            scratch = new TextureTarget("GreenMarine world lighting", main.width, main.height, main.getColorTexture().getFormat(), null);
            pipeline = pipelineFor(main.getColorTexture().getFormat());
        } else if (scratch.width != main.width || scratch.height != main.height) {
            scratch.resize(main.width, main.height);
        }
    }

    static RenderPipeline pipelineFor(GpuFormat format) {
        return pipelineFor(format,false);
    }
    private static RenderPipeline pipelineFor(GpuFormat format,boolean voxels) {
        var layout=BindGroupLayout.builder().withUniform("GreenFx",UniformType.UNIFORM_BUFFER)
            .withUniform("ColorSampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("DepthSampler",UniformType.COMBINED_IMAGE_SAMPLER);
        if(voxels) layout.withUniform("VoxelFrame",UniformType.UNIFORM_BUFFER);
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("greenmarine", voxels?"pipeline/glow_voxel":"pipeline/glow"))
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("greenmarine", "core/glow"))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if(voxels) builder.withShaderDefine("GREENMARINE_VOXEL");
        return builder.build();
    }

    public static void close() {
        if (scratch != null) scratch.destroyBuffers();
        scratch = null;
        pipeline = null;
        failed = false;
        logged = false;
        voxelPipeline=false;
        resetProfiling();
    }
    public static void resetProfiling() {
        frames=0; CPU.clear(); if(gpuTimer!=null) gpuTimer.close(); gpuTimer=null;
    }
    public static PerformanceSamples.Snapshot cpuTiming() { return CPU.snapshot(); }
    public static PerformanceSamples.Snapshot gpuTiming() { return gpuTimer==null?new PerformanceSamples.Snapshot(0,0,0,0,0):gpuTimer.snapshot(); }
}
