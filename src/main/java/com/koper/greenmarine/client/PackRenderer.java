package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.nio.*;
import java.util.*;

public final class PackRenderer {
    private record TargetKey(int width, int height, GpuFormat format) {}
    private record PipelineKey(String fragment, GpuFormat format, boolean history, boolean lightingHistory, List<String> inputs) {}
    private static final Map<TargetKey,TextureTarget[]> TARGETS = new HashMap<>();
    private static final Map<PipelineKey,CompiledRenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<String,TextureTarget> RETAINED = new HashMap<>();
    private static Set<String> retainedOutputs = Set.of();
    private static final Map<String,PassHistory> HISTORIES = new HashMap<>();
    private static final ByteBuffer FRAME = ByteBuffer.allocateDirect(MarineFrame.BYTES).order(ByteOrder.nativeOrder());
    private static final ByteBuffer HISTORY_FRAME = ByteBuffer.allocateDirect(MarineHistoryFrame.BYTES).order(ByteOrder.nativeOrder());
    private static final Matrix4f PROJECTION = new Matrix4f(), IDENTITY = new Matrix4f();
    private static TextureTarget lightmap;
    private static boolean failed, lightmapReady, logged;
    private static long lightmapTick = -1;
    private static int sceneWidth, sceneHeight;
    private static long sceneFrames, submissionNanos, shadedPixels;
    private static final PerformanceSamples CPU = new PerformanceSamples();
    private static GpuTimer gpuTimer;
    private static List<RenderSchedule.Pass> schedule;
    private static Quality scheduledQuality;
    private static com.koper.greenmarine.api.pack.RayMode scheduledRays;
    private static RenderCapabilities scheduledCaps;
    private static GpuFormat sceneFormat, sceneDepthFormat;

    private static final class PassHistory implements AutoCloseable {
        private final TextureTarget[] colors = new TextureTarget[2];
        private TextureTarget normalDepth, albedoMaterial;
        private GpuTexture sceneDepth;
        private GpuTextureView sceneDepthView;
        private final MarineHistoryFrame frame = new MarineHistoryFrame();
        private int previous;
        private final boolean lightingHistory;

        PassHistory(String name,int width,int height,GpuFormat format,GpuFormat fallback,int sceneWidth,int sceneHeight,CommandEncoder encoder,boolean lightingHistory,GpuFormat depthFormat) {
            this.lightingHistory=lightingHistory;
            try {
                colors[0]=target("GreenMarine history "+name+" A",width,height,format,fallback);
                // Match the actual first allocation, including an HDR allocation fallback.
                colors[1]=target("GreenMarine history "+name+" B",width,height,colors[0].getColorTexture().getFormat(),colors[0].getColorTexture().getFormat());
                normalDepth=target("GreenMarine history "+name+" normal/depth",sceneWidth,sceneHeight,GpuFormat.RGBA32_FLOAT,GpuFormat.RGBA32_FLOAT);
                for(var color:colors) encoder.clearColorTexture(color.getColorTexture(),new Vector4f());
                encoder.clearColorTexture(normalDepth.getColorTexture(),new Vector4f());
                if(lightingHistory) {
                    albedoMaterial=target("GreenMarine history "+name+" albedo/material",sceneWidth,sceneHeight,GpuFormat.RGBA16_FLOAT,GpuFormat.RGBA16_FLOAT);
                    var device=RenderSystem.getDevice();
                    sceneDepth=device.createTexture("GreenMarine history "+name+" scene depth",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT,depthFormat,sceneWidth,sceneHeight,1,1);
                    sceneDepthView=device.createTextureView(sceneDepth);
                    encoder.clearColorTexture(albedoMaterial.getColorTexture(),new Vector4f());
                    encoder.clearDepthTexture(sceneDepth,0);
                }
            } catch(RuntimeException | LinkageError error) { close(); throw error; }
        }
        TextureTarget output() { return colors[1-previous]; }
        GpuTextureView color() { return colors[previous].getColorTextureView(); }
        GpuTextureView normals() { return normalDepth.getColorTextureView(); }
        boolean copyNormals(CommandEncoder encoder,GpuTextureView source) {
            if(source==null || !RenderSchedule.canCopy(source.texture().getWidth(0),source.texture().getHeight(0),source.texture().getFormat(),
                normalDepth.width,normalDepth.height,normalDepth.getColorTexture().getFormat())) return false;
            encoder.copyTextureToTexture(source.texture(),normalDepth.getColorTexture(),0,0,0,0,0,normalDepth.width,normalDepth.height);
            return true;
        }
        boolean copySurfaces(CommandEncoder encoder,GpuTextureView normals,GpuTextureView material,GpuTextureView depth) {
            if(!copyNormals(encoder,normals))return false;
            if(!lightingHistory)return true;
            if(material==null || depth==null || !RenderSchedule.canCopy(material.getWidth(0),material.getHeight(0),material.texture().getFormat(),
                albedoMaterial.width,albedoMaterial.height,albedoMaterial.getColorTexture().getFormat())
                || !RenderSchedule.canCopy(depth.getWidth(0),depth.getHeight(0),depth.texture().getFormat(),sceneDepth.getWidth(0),sceneDepth.getHeight(0),sceneDepth.getFormat()))return false;
            encoder.copyTextureToTexture(material.texture(),albedoMaterial.getColorTexture(),0,0,0,0,0,albedoMaterial.width,albedoMaterial.height);
            encoder.copyTextureToTexture(depth.texture(),sceneDepth,0,0,0,0,0,sceneDepth.getWidth(0),sceneDepth.getHeight(0));
            return true;
        }
        void submitted(MarineHistoryFrame.Camera camera,boolean normals) { previous=1-previous; frame.submitted(camera,normals); }
        @Override public void close() {
            for(var color:colors) if(color!=null) color.destroyBuffers();
            if(normalDepth!=null) normalDepth.destroyBuffers();
            if(albedoMaterial!=null) albedoMaterial.destroyBuffers();
            if(sceneDepthView!=null) sceneDepthView.close();
            if(sceneDepth!=null) sceneDepth.close();
            frame.invalidate();
        }
    }
    private record HistorySubmission(PassHistory history, boolean normals) {}
    private PackRenderer() {}

    public static void captureProjection(Matrix4f projection) { PROJECTION.set(projection); }
    public static void invalidateLightmap() { lightmapReady = false; }
    public static GpuTextureView lightmapView(GpuTextureView vanilla) {
        return usable() && lightmapReady ? lightmap.getColorTextureView() : vanilla;
    }
    private static boolean usable() {
        return !failed && GreenMarineClient.enabled() && ShaderRuntime.active() != null
            && ShaderRuntime.script() != null && !ShaderRuntime.script().failed();
    }

    public static void renderLightmap(GpuTextureView vanilla) {
        if (!usable() || Minecraft.getInstance().level == null) return;
        if (lightmapReady && lightmapTick == ShaderRuntime.script().ticks()) return;
        var settings = ShaderRuntime.settings(); var caps = ShaderRuntime.capabilities(); var rays = ShaderRuntime.rays().effective();
        var selected = ShaderRuntime.active().definition().passes().stream()
            .filter(pass -> pass.stage() == PackPass.Stage.LIGHTMAP)
            .map(pass -> RenderPolicy.pass(pass,settings.quality(),caps,rays)).flatMap(Optional::stream).findFirst();
        if (selected.isEmpty()) { lightmapReady = false; return; }
        try {
            if (lightmap == null) lightmap = new TextureTarget("GreenMarine pack lightmap",16,16,GpuFormat.RGBA8_UNORM,null);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            draw(encoder, selected.get().fragment(),lightmap.getColorTextureView(),vanilla,vanilla,vanilla,vanilla,16,16,IDENTITY);
            ShaderRuntime.submitted(selected.get()); lightmapReady = true; lightmapTick = ShaderRuntime.script().ticks();
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    public static void renderScene(GameRenderer renderer) {
        if (!usable() || !renderer.gameRenderState().shouldRenderLevel) { invalidateHistory(); return; }
        var main = renderer.mainRenderTarget();
        if (main.width <= 0 || main.height <= 0 || main.getColorTextureView() == null || main.getDepthTextureView() == null) { invalidateHistory(); return; }
        var settings = ShaderRuntime.settings();
        var caps=ShaderRuntime.capabilities(); var rays=ShaderRuntime.rays().effective();
        if (sceneWidth != main.width || sceneHeight != main.height || sceneFormat != main.getColorTexture().getFormat() || sceneDepthFormat != main.getDepthTexture().getFormat()) {
            closeTargets(); sceneWidth=main.width; sceneHeight=main.height; sceneFormat=main.getColorTexture().getFormat(); sceneDepthFormat=main.getDepthTexture().getFormat(); schedule=null;
        }
        long started = System.nanoTime();
        try {
            if (schedule==null || scheduledQuality!=settings.quality() || scheduledRays!=rays || !caps.equals(scheduledCaps)) {
                closeHistories(); closeRetained();
                schedule=RenderSchedule.scene(ShaderRuntime.active().definition(),settings.quality(),caps,rays,main.width,main.height);
                retainedOutputs=RenderSchedule.retainedOutputs(schedule);
                scheduledQuality=settings.quality(); scheduledRays=rays; scheduledCaps=caps;
            }
            if (schedule.isEmpty()) { invalidateHistory(); return; }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            if (settings.profiling() && gpuTimer==null) {
                try { gpuTimer=new GpuTimer(); }
                catch(RuntimeException unavailable) { GreenMarine.LOGGER.warn("Unable to create GPU profiler: {}",unavailable.getMessage()); }
            }
            if(!settings.profiling() && gpuTimer!=null) {gpuTimer.close();gpuTimer=null;}
            int measurement=sceneFrames>=40 && gpuTimer!=null?gpuTimer.begin(encoder):-1;
            GpuTextureView input = main.getColorTextureView(); TextureTarget last = null;
            var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
            var historyCamera = new MarineHistoryFrame.Camera(PROJECTION,camera.viewRotationMatrix,camera.pos.x,camera.pos.y,camera.pos.z,
                Minecraft.getInstance().level,settings.overridesFor(ShaderRuntime.active().definition()));
            var historySubmissions = new ArrayList<HistorySubmission>();
            var surfaceNormals = com.koper.greenmarine.client.world.WorldPrograms.surfaceNormalDepth();
            var voxelData=VoxelWorld.prepare(encoder,camera.pos.x,camera.pos.y,camera.pos.z,rays==com.koper.greenmarine.api.pack.RayMode.VOXEL);
            var voxelMaterials=VoxelWorld.materials(encoder);
            long pixels = 0;
            var outputs=new HashMap<String,GpuTextureView>();
            PackCompute.prepare(ShaderRuntime.active().definition().resources(),encoder,main.width,main.height);
            for (var pass : schedule) {
                var definition=pass.resolved().definition(); PassHistory history=null;
                if(definition.stage()==PackPass.Stage.COMPUTE) {
                    var computeInputs=new LinkedHashMap<String,GpuTextureView>();
                    for(var binding:definition.inputs().entrySet()) computeInputs.put(binding.getKey(),inputView(outputs,binding.getValue()));
                    PackCompute.dispatch(encoder,ShaderRuntime.active(),definition,frameUniform(encoder,main.width,main.height,camera.viewRotationMatrix),
                        Map.of("ColorSampler",input,"SceneSampler",main.getColorTextureView(),"DepthSampler",main.getDepthTextureView(),"LightmapSampler",renderer.levelLightmap()),computeInputs);
                    continue;
                }
                if(definition.history()) {
                    history=HISTORIES.computeIfAbsent(definition.name(),name->new PassHistory(name,pass.width(),pass.height(),
                        RenderSchedule.format(definition,main.getColorTexture().getFormat()),main.getColorTexture().getFormat(),main.width,main.height,encoder,definition.lightingHistory(),main.getDepthTexture().getFormat()));
                    last=history.output();
                } else if(retainedOutputs.contains(definition.name())) {
                    last=RETAINED.computeIfAbsent(definition.name(),name->target("GreenMarine retained "+name,pass.width(),pass.height(),
                        RenderSchedule.format(definition,main.getColorTexture().getFormat()),main.getColorTexture().getFormat()));
                } else {
                    var pair = targets(pass.width(),pass.height(),RenderSchedule.format(definition,main.getColorTexture().getFormat()),main.getColorTexture().getFormat());
                    last = pair[0].getColorTexture() == input.texture() ? pair[1] : pair[0];
                }
                var namedInputs=new LinkedHashMap<String,GpuTextureView>();
                for(var binding:definition.inputs().entrySet()) namedInputs.put(binding.getKey(),inputView(outputs,binding.getValue()));
                draw(encoder,pass.resolved().fragment(),last.getColorTextureView(),input,main.getDepthTextureView(),renderer.levelLightmap(),main.getColorTextureView(),pass.width(),pass.height(),camera.viewRotationMatrix,voxelData,voxelMaterials,history,historyCamera,namedInputs);
                if(history!=null) historySubmissions.add(new HistorySubmission(history,history.copySurfaces(encoder,surfaceNormals,com.koper.greenmarine.client.world.WorldPrograms.surfaceAlbedo(),main.getDepthTextureView())));
                if(retainedOutputs.contains(definition.name())) outputs.put(definition.name(),last.getColorTextureView());
                input = last.getColorTextureView(); pixels += (long)pass.width() * pass.height();
            }
            if (last == null) throw new IllegalStateException("A shaderpack needs at least one selected scene pass after its compute passes");
            if (RenderSchedule.canCopy(last.width,last.height,last.getColorTexture().getFormat(),main.width,main.height,main.getColorTexture().getFormat())) {
                encoder.copyTextureToTexture(last.getColorTexture(),main.getColorTexture(),0,0,0,0,0,main.width,main.height);
            } else {
                draw(encoder,"greenmarine:core/copy",main.getColorTextureView(),input,main.getDepthTextureView(),renderer.levelLightmap(),input,main.width,main.height,camera.viewRotationMatrix);
                pixels += (long)main.width * main.height;
            }
            if (gpuTimer!=null) gpuTimer.end(encoder,measurement);
            // No submit here: blaze3d's submit() waits for the submission two back, so a mid-frame submit
            // made the CPU wait for this frame's own GPU work instead of overlapping with it.
            for(var submission:historySubmissions) submission.history().submitted(historyCamera,submission.normals());
            for(var pass:schedule) ShaderRuntime.submitted(pass.resolved()); sceneFrames++; submissionNanos += System.nanoTime() - started; shadedPixels += pixels;
            if(sceneFrames>40) CPU.add((System.nanoTime()-started)/1_000_000.0);
            if (!logged) {
                logged = true; GreenMarine.LOGGER.info("Shaderpack scene submitted: pack={}, backend={}, passes={}, pixels={}, size={}x{}",
                    ShaderRuntime.active().definition().id(),ShaderRuntime.capabilities().backend(),schedule.size(),pixels,main.width,main.height);
            }
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    private static GpuTextureView inputView(Map<String,GpuTextureView> outputs,String producer) {
        var view=outputs.containsKey(producer)?outputs.get(producer):PackCompute.image(producer);
        if(view==null) throw new IllegalArgumentException("Scene pass output unavailable: "+producer);
        return view;
    }
    private static com.mojang.renderpearl.api.buffers.GpuBufferSlice frameUniform(CommandEncoder encoder,int width,int height,org.joml.Matrix4fc view) {
        var client = Minecraft.getInstance();
        float day = client.level == null ? 0 : Math.floorMod(client.level.getDefaultClockTime(),24000) / 24000f;
        MarineFrame.write(FRAME,width,height,GreenMarineClient.seconds(client.getDeltaTracker().getGameTimeDeltaPartialTick(false)),day,
            ShaderRuntime.capabilities().backend().equalsIgnoreCase("Vulkan") || RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
            ShaderRuntime.settings().quality(),ShaderRuntime.rays().effective(),PROJECTION,view,ShaderRuntime.script());
        FRAME.position(0).limit(MarineFrame.BYTES);
        return encoder.transientMemory().uploadGpu(FRAME,RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(),GpuBuffer.USAGE_UNIFORM);
    }
    private static TextureTarget[] targets(int width,int height,GpuFormat format,GpuFormat fallback) {
        return TARGETS.computeIfAbsent(new TargetKey(width,height,format), key -> {
            var first=target("GreenMarine pack A",width,height,format,fallback);
            try { return new TextureTarget[]{first,target("GreenMarine pack B",width,height,format,fallback)}; }
            catch(RuntimeException | LinkageError error) { first.destroyBuffers(); throw error; }
        });
    }
    private static TextureTarget target(String name,int width,int height,GpuFormat format,GpuFormat fallback) {
        try { return new TextureTarget(name,width,height,format,null); }
        catch(RuntimeException unavailable) {
            if(format!=GpuFormat.RGBA16_FLOAT || fallback==format) throw unavailable;
            GreenMarine.LOGGER.warn("Float pass target unavailable; using {}: {}",fallback,unavailable.getMessage());
            return new TextureTarget(name,width,height,fallback,null);
        }
    }

    private static void draw(CommandEncoder encoder,String fragment,GpuTextureView output,GpuTextureView color,
                             GpuTextureView depth,GpuTextureView lightmapInput,GpuTextureView scene,int width,int height,org.joml.Matrix4fc view) {
        draw(encoder,fragment,output,color,depth,lightmapInput,scene,width,height,view,null,null);
    }
    private static void draw(CommandEncoder encoder,String fragment,GpuTextureView output,GpuTextureView color,
                             GpuTextureView depth,GpuTextureView lightmapInput,GpuTextureView scene,int width,int height,org.joml.Matrix4fc view,
                             com.mojang.renderpearl.api.buffers.GpuBuffer voxels,GpuTextureView materials) {
        draw(encoder,fragment,output,color,depth,lightmapInput,scene,width,height,view,voxels,materials,null,null,Map.of());
    }
    private static void draw(CommandEncoder encoder,String fragment,GpuTextureView output,GpuTextureView color,
                             GpuTextureView depth,GpuTextureView lightmapInput,GpuTextureView scene,int width,int height,org.joml.Matrix4fc view,
                             GpuBuffer voxels,GpuTextureView materials,PassHistory history,MarineHistoryFrame.Camera historyCamera,Map<String,GpuTextureView> namedInputs) {
        var compiled = compiled(fragment,output.texture().getFormat(),history!=null,history!=null && history.lightingHistory,namedInputs.keySet().stream().sorted().toList());
        var uniform = frameUniform(encoder,width,height,view);
        var rayData=voxels==null?null:com.koper.greenmarine.client.rt.RayWorld.bindings(encoder);
        var environment=voxels==null?null:com.koper.greenmarine.client.world.WorldPrograms.environmentBindings(encoder);
        var surfaceFrame=voxels==null?null:com.koper.greenmarine.client.world.WorldPrograms.surfaceBindings(encoder);
        var surfaceRayData=voxels==null?null:com.koper.greenmarine.client.rt.RayWorld.surfaceBindings(encoder);
        var historyData=history==null?null:historyUniform(encoder,history,historyCamera);
        try (var pass = encoder.createRenderPass(() -> "GreenMarine shaderpack",output,Optional.empty())) {
            pass.setPipeline(compiled); pass.setUniform("MarineFrame",uniform);
            pass.setUniform("ColorSampler",color,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("DepthSampler",depth,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("LightmapSampler",lightmapInput,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("SceneSampler",scene,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if(voxels!=null) {
                var surfaceNormals=com.koper.greenmarine.client.world.WorldPrograms.surfaceNormalDepth();
                var surfaceAlbedo=com.koper.greenmarine.client.world.WorldPrograms.surfaceAlbedo();
                pass.setUniform("MarineEnvironment",environment);
                pass.setUniform("MarineSurfaceFrame",surfaceFrame);
                pass.setUniform("MarineSurfaceNormalDepth",surfaceNormals==null?com.koper.greenmarine.client.rt.RayWorld.normals():surfaceNormals,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("MarineSurfaceAlbedo",surfaceAlbedo==null?com.koper.greenmarine.client.rt.RayWorld.albedo():surfaceAlbedo,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("RaySurfaceFrame",surfaceRayData);
                pass.setUniform("RaySurfaceLighting",com.koper.greenmarine.client.rt.RayWorld.surfaceLighting(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("RayReflection",com.koper.greenmarine.client.rt.RayWorld.reflections(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("RaySurfaceExtra",com.koper.greenmarine.client.rt.RayWorld.surfaceExtra(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("RayFrame",rayData);
                pass.setUniform("RayLighting",com.koper.greenmarine.client.rt.RayWorld.lighting(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("RayNormalDepth",com.koper.greenmarine.client.rt.RayWorld.normals(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("RayAlbedo",com.koper.greenmarine.client.rt.RayWorld.albedo(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("VoxelFrame",voxels);
                pass.setUniform("VoxelMaterials",materials,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            if(history!=null) {
                pass.setUniform("MarineHistoryFrame",historyData);
                pass.setUniform("HistorySampler",history.color(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("HistoryNormalDepth",history.normals(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                if(history.lightingHistory) {
                    pass.setUniform("HistoryAlbedoMaterial",history.albedoMaterial.getColorTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.setUniform("HistorySceneDepth",history.sceneDepthView,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                }
            }
            for(var binding:namedInputs.entrySet()) pass.setUniform(binding.getKey(),binding.getValue(),RenderSystem.getSamplerCache().getClampToEdge(
                binding.getValue().texture().getFormat().name().endsWith("_UINT")?FilterMode.NEAREST:FilterMode.LINEAR));
            pass.draw(3,1,0,0);
        }
    }

    private static com.mojang.renderpearl.api.buffers.GpuBufferSlice historyUniform(CommandEncoder encoder,PassHistory history,MarineHistoryFrame.Camera camera) {
        history.frame.write(HISTORY_FRAME,camera); HISTORY_FRAME.position(0).limit(MarineHistoryFrame.BYTES);
        return encoder.transientMemory().uploadGpu(HISTORY_FRAME,RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(),GpuBuffer.USAGE_UNIFORM);
    }

    static RenderPipeline pipelineFor(String fragment,GpuFormat format) {
        return pipelineFor(fragment,format,false);
    }
    static RenderPipeline pipelineFor(String fragment,GpuFormat format,boolean history) {
        return pipelineFor(fragment,format,history,List.of());
    }
    static RenderPipeline pipelineFor(String fragment,GpuFormat format,boolean history,List<String> inputs) {
        return pipelineFor(fragment,format,history,false,inputs);
    }
    static RenderPipeline pipelineFor(String fragment,GpuFormat format,boolean history,boolean lightingHistory,List<String> inputs) {
        inputs=inputs.stream().sorted().toList();
        var layout=BindGroupLayout.builder().withUniform("MarineFrame",UniformType.UNIFORM_BUFFER)
                .withUniform("ColorSampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("DepthSampler",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("LightmapSampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("SceneSampler",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("RayFrame",UniformType.UNIFORM_BUFFER).withUniform("RayLighting",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("RayNormalDepth",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("RayAlbedo",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("VoxelFrame",UniformType.UNIFORM_BUFFER).withUniform("VoxelMaterials",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("MarineEnvironment",UniformType.UNIFORM_BUFFER)
                .withUniform("MarineSurfaceFrame",UniformType.UNIFORM_BUFFER)
                .withUniform("MarineSurfaceNormalDepth",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("MarineSurfaceAlbedo",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("RaySurfaceFrame",UniformType.UNIFORM_BUFFER).withUniform("RaySurfaceLighting",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("RayReflection",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("RaySurfaceExtra",UniformType.COMBINED_IMAGE_SAMPLER);
        if(history) layout.withUniform("MarineHistoryFrame",UniformType.UNIFORM_BUFFER)
            .withUniform("HistorySampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("HistoryNormalDepth",UniformType.COMBINED_IMAGE_SAMPLER);
        if(lightingHistory) layout.withUniform("HistoryAlbedoMaterial",UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("HistorySceneDepth",UniformType.COMBINED_IMAGE_SAMPLER);
        for(var input:inputs) layout.withUniform(input,UniformType.COMBINED_IMAGE_SAMPLER);
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("greenmarine","pack/"+Integer.toUnsignedString(fragment.hashCode())+"/"+format.ordinal()+(history?"/history":"")+(lightingHistory?"/surfaces":"")+(inputs.isEmpty()?"":"/inputs/"+Integer.toUnsignedString(inputs.hashCode()))))
            .withVertexShader(Identifier.fromNamespaceAndPath("greenmarine","pack/screenquad")).withFragmentShader(Identifier.parse(fragment))
            .withBindGroupLayout(layout.build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),format,ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
    private static CompiledRenderPipeline compiled(String fragment,GpuFormat format,boolean history,boolean lightingHistory,List<String> inputs) {
        return PIPELINES.computeIfAbsent(new PipelineKey(fragment,format,history,lightingHistory,inputs), key -> {
            try (var source = new PackShaders(ShaderRuntime.active())) {
                var pending = RenderSystem.getDevice().compilePipeline(pipelineFor(fragment,format,history,lightingHistory,inputs),source,Runnable::run).join();
                var compiled = pending.finishCompile();
                if (compiled == null) throw new IllegalArgumentException("Unable to compile pack shader: "+fragment);
                return compiled;
            }
        });
    }
    private static void fail(Throwable error) { failed = true; lightmapReady = false; closeTargets(); schedule=null; ShaderRuntime.renderFailure(error); GreenMarine.LOGGER.error("Shaderpack rendering disabled until pack switch/reload",error); }
    public static long sceneFrames() { return sceneFrames; }
    public static double averageSubmissionMicros() { return sceneFrames == 0 ? 0 : submissionNanos / (sceneFrames * 1000.0); }
    public static long shadedPixels() { return shadedPixels; }
    public static boolean failed() { return failed; }
    public static PerformanceSamples.Snapshot cpuTiming() { return CPU.snapshot(); }
    public static PerformanceSamples.Snapshot gpuTiming() { return gpuTimer==null?new PerformanceSamples.Snapshot(0,0,0,0,0):gpuTimer.snapshot(); }
    public static void invalidateLightingHistory() { HISTORIES.values().stream().filter(history->!history.lightingHistory).forEach(history->history.frame.invalidate()); }
    public static void invalidateHistory() { HISTORIES.values().forEach(history->history.frame.invalidate()); }
    private static void closeHistories() { HISTORIES.values().forEach(PassHistory::close); HISTORIES.clear(); }
    private static void closeRetained() { RETAINED.values().forEach(TextureTarget::destroyBuffers); RETAINED.clear(); retainedOutputs=Set.of(); }
    private static void closeTargets() { closeHistories(); closeRetained(); TARGETS.values().forEach(pair -> { for (var target : pair) target.destroyBuffers(); }); TARGETS.clear(); }
    public static void close() {
        PackCompute.close(); closeTargets(); PIPELINES.values().forEach(CompiledRenderPipeline::close); PIPELINES.clear();
        if (lightmap != null) lightmap.destroyBuffers(); lightmap = null;
        lightmapReady = failed = logged = false; lightmapTick = -1; sceneWidth = sceneHeight = 0;
        sceneFrames = submissionNanos = shadedPixels = 0;
        schedule=null; sceneFormat=sceneDepthFormat=null; scheduledCaps=null; CPU.clear();
        if(gpuTimer!=null) gpuTimer.close(); gpuTimer=null;
    }
}
