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
    private static final DepthSnapshots DEPTHS=new DepthSnapshots();
    private record TargetKey(int width, int height, GpuFormat format) {}
    private record PipelineKey(String fragment, String vertex,List<GpuFormat> formats, boolean history, boolean lightingHistory, List<String> inputs, PackResources resources,Map<String,PackUniformBlock> uniforms) {}
    private record UniformBindings(Map<String,com.mojang.renderpearl.api.buffers.GpuBufferSlice> slices,Map<String,PackUniformBlock> definitions) {
        private static final UniformBindings NONE=new UniformBindings(Map.of(),Map.of());
    }
    private static final Map<TargetKey,TextureTarget[]> TARGETS = new HashMap<>();
    private static final Map<PipelineKey,CompiledRenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<String,TextureTarget> RETAINED = new HashMap<>();
    private static final SceneTargets NAMED=new SceneTargets();
    private static final PackUniforms UNIFORMS=new PackUniforms();
    private static final com.koper.greenmarine.client.iris.IrisFrameInputs HOST_INPUTS=new com.koper.greenmarine.client.iris.IrisFrameInputs();
    private static final PackInputState CUSTOM_INPUTS=new PackInputState();
    private static final PackFramePhases PHASES=new PackFramePhases();
    private static long frameEpoch;
    private static PhasedFrame frame;
    /** One immutable host snapshot and upload set survive every host boundary of this frame. */
    private static final class PhasedFrame {
        final GameRenderer renderer;
        final MarineHistoryFrame.Camera historyCamera;
        final com.koper.greenmarine.client.iris.IrisFrameInputs.Snapshot hostSnapshot;
        final Map<String,GpuTextureView> outputs=new HashMap<>();
        GpuTextureView postWorldOutput;
        long pixels,submissionNanos;
        PhasedFrame(GameRenderer renderer,MarineHistoryFrame.Camera camera,com.koper.greenmarine.client.iris.IrisFrameInputs.Snapshot snapshot) {
            this.renderer=renderer;historyCamera=camera;hostSnapshot=snapshot;
        }
    }
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

    /** Called after projection/ray preparation and before any host world draw. */
    public static void beginWorldFrame(GameRenderer renderer) {
        frame=null;
        if(!usable() || !renderer.gameRenderState().shouldRenderLevel || Minecraft.getInstance().level==null) {invalidateHistory();return;}
        var main=renderer.mainRenderTarget();
        if(main.width<=0 || main.height<=0 || main.getColorTextureView()==null || main.getDepthTextureView()==null) {invalidateHistory();return;}
        long started=System.nanoTime();
        try {
            var settings=ShaderRuntime.settings();var caps=ShaderRuntime.capabilities();var rays=ShaderRuntime.rays().effective();
            var lifetime=PHASES.begin(ShaderRuntime.active(),Minecraft.getInstance().level,main.width,main.height,++frameEpoch);
            boolean formatsChanged=sceneFormat!=main.getColorTexture().getFormat() || sceneDepthFormat!=main.getDepthTexture().getFormat();
            if(lifetime.reset() || formatsChanged) {
                closeTargets();PackCompute.close();UNIFORMS.reset();schedule=null;
                sceneWidth=main.width;sceneHeight=main.height;sceneFormat=main.getColorTexture().getFormat();sceneDepthFormat=main.getDepthTexture().getFormat();
                if(formatsChanged && !lifetime.reset()) {PHASES.reset();PHASES.begin(ShaderRuntime.active(),Minecraft.getInstance().level,main.width,main.height,frameEpoch);}
            }
            if(schedule==null || scheduledQuality!=settings.quality() || scheduledRays!=rays || !caps.equals(scheduledCaps)) {
                var nextSchedule=RenderSchedule.scene(ShaderRuntime.active().definition(),settings.quality(),caps,rays,main.width,main.height);
                if(schedule!=null && !schedule.equals(nextSchedule)) {
                    // A newly selected setup pass or target must initialize alongside its new consumers.
                    closeTargets();PackCompute.close();UNIFORMS.reset();PHASES.reset();
                    PHASES.begin(ShaderRuntime.active(),Minecraft.getInstance().level,main.width,main.height,frameEpoch);
                } else closeHistories();
                schedule=nextSchedule;
                retainedOutputs=RenderSchedule.retainedOutputs(schedule);
                scheduledQuality=settings.quality();scheduledRays=rays;scheduledCaps=caps;
            }
            if(schedule.isEmpty() && ShaderRuntime.active().definition().programs().isEmpty()) {invalidateHistory();return;}
            if(ShaderRuntime.active().definition().worldColor()!=null && schedule.stream().noneMatch(pass->pass.resolved().definition().stage()==PackPass.Stage.SCENE && (pass.resolved().definition().phase()==PackPass.Phase.COMPOSITE || pass.resolved().definition().phase()==PackPass.Phase.FINAL)))
                throw new IllegalArgumentException("Selected worldColor pipeline has no active post-world presentation pass");
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            PackCompute.prepare(ShaderRuntime.active().definition().resources(),encoder,main.width,main.height);
            PackCompute.beginFrame(encoder,frameEpoch);
            var targetDeclarations=ShaderRuntime.active().definition().targets();var requiredTargets=new HashSet<String>();
            var uniformResources=new HashSet<String>();
            for(var selected:schedule) {
                var definition=selected.resolved().definition();uniformResources.addAll(definition.uniforms().values());
                requiredTargets.addAll(definition.outputs().values());
                for(var producer:definition.inputs().values())if(targetDeclarations.containsKey(producer))requiredTargets.add(producer);
            }
            for(var program:ShaderRuntime.active().definition().programs()) {
                requiredTargets.addAll(program.outputs().values());uniformResources.addAll(program.uniforms().values());
                for(var producer:program.inputs().values())if(targetDeclarations.containsKey(producer))requiredTargets.add(producer);
            }
            if(ShaderRuntime.active().definition().worldColor()!=null)requiredTargets.add(ShaderRuntime.active().definition().worldColor());
            NAMED.ensure(targetDeclarations,requiredTargets,main.width,main.height,Minecraft.getInstance().level,encoder);
            NAMED.beginFrame(frameEpoch,encoder);
            var depthSources=EnumSet.noneOf(PackDepthSource.class);
            for(var selected:schedule)for(var producer:selected.resolved().definition().inputs().values()) {
                var source=PackDepthSource.named(producer);if(source!=null)depthSources.add(source);
            }
            for(var program:ShaderRuntime.active().definition().programs())for(var producer:program.inputs().values()) {
                var source=PackDepthSource.named(producer);if(source!=null)depthSources.add(source);
            }
            DEPTHS.prepare(depthSources,main,encoder);
            var uniformSources=new HashSet<String>();
            for(var name:uniformResources)uniformSources.addAll(ShaderRuntime.active().definition().resources().uniforms().get(name).sources().values());
            // Capture/upload once; every phase uses these exact values and immutable GPU slices.
            var inputConfig=ShaderRuntime.active().definition().uniformInputs();
            var snapshot=uniformSources.isEmpty()?null:HOST_INPUTS.capture(renderer,PROJECTION,ShaderRuntime.active(),System.nanoTime(),inputConfig.sunPathRotation());
            var hostValues=snapshot==null?Map.<String,Object>of():CUSTOM_INPUTS.resolve(inputConfig,Minecraft.getInstance().level,ShaderRuntime.active(),snapshot,uniformSources);
            UNIFORMS.prepare(ShaderRuntime.active().definition().resources(),uniformResources,encoder,hostValues);
            var camera=renderer.gameRenderState().levelRenderState.cameraRenderState;
            frame=new PhasedFrame(renderer,new MarineHistoryFrame.Camera(PROJECTION,camera.viewRotationMatrix,camera.pos.x,camera.pos.y,camera.pos.z,
                Minecraft.getInstance().level,settings.overridesFor(ShaderRuntime.active().definition())),snapshot);
            // SETUP pass-name snapshots remain readable on subsequent frames of this lifetime.
            for(var selected:schedule)if(selected.resolved().definition().phase()==PackPass.Phase.SETUP) {
                String name=selected.resolved().definition().name();var retained=RETAINED.get(name);
                if(retained!=null)frame.outputs.put(name,retained.getColorTextureView());
            }
            runPhase(PackPass.Phase.SETUP);runPhase(PackPass.Phase.BEGIN);
            // No GreenMarine shadow renderer exists yet. Keep the boundary explicit so a future
            // owned shadow pass can be inserted between BEGIN and SHADOWCOMP without moving phases.
            afterShadow();
            runPhase(PackPass.Phase.PREPARE);
            if(frame!=null)frame.submissionNanos+=System.nanoTime()-started;
        } catch(RuntimeException|LinkageError error) {fail(error);}
    }

    private static void afterShadow() {runPhase(PackPass.Phase.SHADOWCOMP);}

    /** True only while the actual host opaque/translucent boundary needs a separate pack pass. */
    public static boolean needsAfterOpaque() {
        return usable() && frame!=null && (DEPTHS.required() || com.koper.greenmarine.client.world.WorldPrograms.needsOpaqueBoundary() || schedule.stream().anyMatch(pass->pass.resolved().definition().phase()==PackPass.Phase.DEFERRED));
    }
    /** Must run after the solid RenderPass closes, before classic or OIT transparency starts. */
    public static void afterOpaque() {
        if(!usable() || frame==null)return;
        long started=System.nanoTime();
        try {
            DEPTHS.afterOpaque(frame.renderer.mainRenderTarget(),RenderSystem.getDevice().createCommandEncoder());
            com.koper.greenmarine.client.world.WorldPrograms.beforeDeferred();
            runPhase(PackPass.Phase.DEFERRED);
            com.koper.greenmarine.client.world.WorldPrograms.afterDeferred();
            frame.submissionNanos+=System.nanoTime()-started;
        }
        catch(RuntimeException|LinkageError error) {fail(error);}
    }

    private static void runPhase(PackPass.Phase phase) {
        if(frame==null || !PHASES.enter(phase))return;
        if(schedule.stream().noneMatch(pass->pass.resolved().definition().phase()==phase))return;
        var renderer=frame.renderer;var main=renderer.mainRenderTarget();
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        var camera=renderer.gameRenderState().levelRenderState.cameraRenderState;
        var rays=ShaderRuntime.rays().effective();
        GpuTextureView input=phase==PackPass.Phase.FINAL && frame.postWorldOutput!=null?frame.postWorldOutput:main.getColorTextureView(),last=null;
        String worldColor=ShaderRuntime.active().definition().worldColor();
        if(worldColor!=null && (phase==PackPass.Phase.DEFERRED || phase==PackPass.Phase.COMPOSITE || phase==PackPass.Phase.FINAL) && frame.postWorldOutput==null)input=NAMED.read(worldColor);
        var surfaceNormals=com.koper.greenmarine.client.world.WorldPrograms.surfaceNormalDepth();
        var voxelData=VoxelWorld.prepare(encoder,camera.pos.x,camera.pos.y,camera.pos.z,rays==RayMode.VOXEL);
        var voxelMaterials=VoxelWorld.materials(encoder);
        for (var pass : schedule) {
            if(pass.resolved().definition().phase()!=phase)continue;
            var definition=pass.resolved().definition(); PassHistory history=null;
            if(definition.stage()==PackPass.Stage.COMPUTE) {
                var computeInputs=new LinkedHashMap<String,GpuTextureView>();
                for(var binding:definition.inputs().entrySet()) computeInputs.put(binding.getKey(),inputView(frame.outputs,binding.getValue()));
                PackCompute.dispatch(encoder,ShaderRuntime.active(),definition,frameUniform(encoder,main.width,main.height,camera.viewRotationMatrix),
                    Map.of("ColorSampler",input,"SceneSampler",main.getColorTextureView(),"DepthSampler",main.getDepthTextureView(),"LightmapSampler",renderer.levelLightmap()),computeInputs,uniformBindings(definition).slices());
                continue;
            }
            List<GpuTextureView> attachments;
            if(!definition.outputs().isEmpty()) {
                if(definition.outputs().size()>RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments())
                    throw new IllegalArgumentException("Pass "+definition.name()+" exceeds the host colour attachment limit");
                attachments=NAMED.writes(definition.outputs(),encoder);last=attachments.getFirst();
            } else {
                TextureTarget target;
                if(definition.history()) {
                    history=HISTORIES.computeIfAbsent(definition.name(),name->new PassHistory(name,pass.width(),pass.height(),
                        RenderSchedule.format(definition,main.getColorTexture().getFormat()),main.getColorTexture().getFormat(),main.width,main.height,encoder,definition.lightingHistory(),main.getDepthTexture().getFormat()));
                    target=history.output();
                } else if(retainedOutputs.contains(definition.name())) {
                    target=RETAINED.computeIfAbsent(definition.name(),name->target("GreenMarine retained "+name,pass.width(),pass.height(),
                        RenderSchedule.format(definition,main.getColorTexture().getFormat()),main.getColorTexture().getFormat()));
                } else {
                    var pair = targets(pass.width(),pass.height(),RenderSchedule.format(definition,main.getColorTexture().getFormat()),main.getColorTexture().getFormat());
                    target = pair[0].getColorTexture() == input.texture() ? pair[1] : pair[0];
                }
                last=target.getColorTextureView();attachments=List.of(last);
            }
            var namedInputs=new LinkedHashMap<String,GpuTextureView>();
            for(var binding:definition.inputs().entrySet()) namedInputs.put(binding.getKey(),inputView(frame.outputs,binding.getValue()));
            draw(encoder,pass.resolved().fragment(),attachments,input,main.getDepthTextureView(),renderer.levelLightmap(),main.getColorTextureView(),pass.width(),pass.height(),camera.viewRotationMatrix,voxelData,voxelMaterials,history,frame.historyCamera,namedInputs,uniformBindings(definition),definition.vertex(),definition.vertices());
            if(!definition.outputs().isEmpty())NAMED.publish(definition.outputs());
            if(history!=null) history.submitted(frame.historyCamera,history.copySurfaces(encoder,surfaceNormals,com.koper.greenmarine.client.world.WorldPrograms.surfaceAlbedo(),main.getDepthTextureView()));
            if(retainedOutputs.contains(definition.name())) {
                if(!definition.outputs().isEmpty()) {
                    // A pass-name input denotes this exact result, even if later passes overwrite the named target twice.
                    var source=last;var snapshot=RETAINED.computeIfAbsent(definition.name(),name->target("GreenMarine retained "+name,
                        source.getWidth(0),source.getHeight(0),source.texture().getFormat(),source.texture().getFormat()));
                    if(!RenderSchedule.canCopy(source.getWidth(0),source.getHeight(0),source.texture().getFormat(),snapshot.width,snapshot.height,snapshot.getColorTexture().getFormat()))
                        throw new IllegalStateException("Retained target format/dimensions changed: "+definition.name());
                    encoder.copyTextureToTexture(source.texture(),snapshot.getColorTexture(),0,0,0,0,0,snapshot.width,snapshot.height);
                    frame.outputs.put(definition.name(),snapshot.getColorTextureView());
                } else frame.outputs.put(definition.name(),last);
            }
            input=last;frame.pixels+=(long)pass.width()*pass.height();
        }

        if(last!=null && (phase==PackPass.Phase.COMPOSITE || phase==PackPass.Phase.FINAL))frame.postWorldOutput=last;
        for(var pass:schedule)if(pass.resolved().definition().phase()==phase)ShaderRuntime.submitted(pass.resolved());
    }

    public static void renderScene(GameRenderer renderer) {
        if(!usable() || !renderer.gameRenderState().shouldRenderLevel || frame==null) {invalidateHistory();return;}
        long started=System.nanoTime();
        try {
            var main=renderer.mainRenderTarget();var settings=ShaderRuntime.settings();
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            if(settings.profiling() && gpuTimer==null) {
                try {gpuTimer=new GpuTimer();}
                catch(RuntimeException unavailable) {GreenMarine.LOGGER.warn("Unable to create GPU profiler: {}",unavailable.getMessage());}
            }
            if(!settings.profiling() && gpuTimer!=null) {gpuTimer.close();gpuTimer=null;}
            int measurement=sceneFrames>=40 && gpuTimer!=null?gpuTimer.begin(encoder):-1;
            DEPTHS.afterWorld(main,encoder);
            runPhase(PackPass.Phase.COMPOSITE);runPhase(PackPass.Phase.FINAL);
            var last=frame.postWorldOutput;
            // Earlier phases may populate named targets, but only a post-world scene result is presented.
            if(last!=null) {
                if(SceneTargets.integer(last.texture().getFormat()))throw new IllegalArgumentException("An unsigned final target needs an explicit floating-point presentation pass");
                if(RenderSchedule.canCopy(last.getWidth(0),last.getHeight(0),last.texture().getFormat(),main.width,main.height,main.getColorTexture().getFormat()))
                    encoder.copyTextureToTexture(last.texture(),main.getColorTexture(),0,0,0,0,0,main.width,main.height);
                else {
                    draw(encoder,"greenmarine:core/copy",main.getColorTextureView(),last,main.getDepthTextureView(),renderer.levelLightmap(),last,main.width,main.height,
                        renderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix);
                    frame.pixels+=(long)main.width*main.height;
                }
            }
            if(gpuTimer!=null)gpuTimer.end(encoder,measurement);
            // Host owns submission; transient uploads survive every phase in this command batch.
            frame.submissionNanos+=System.nanoTime()-started;
            sceneFrames++;submissionNanos+=frame.submissionNanos;shadedPixels+=frame.pixels;
            if(sceneFrames>40)CPU.add(frame.submissionNanos/1_000_000.0);
            if(!logged) {logged=true;GreenMarine.LOGGER.info("Shaderpack scene submitted: pack={}, backend={}, passes={}, pixels={}, size={}x{}",
                ShaderRuntime.active().definition().id(),ShaderRuntime.capabilities().backend(),schedule.size(),frame.pixels,main.width,main.height);}
            frame=null;
        } catch(RuntimeException|LinkageError error) {fail(error);}
    }

    private static GpuTextureView inputView(Map<String,GpuTextureView> outputs,String producer) {
        var depthSource=PackDepthSource.named(producer);
        if(depthSource!=null) {
            var depth=DEPTHS.read(depthSource);
            if(depth==null)throw new IllegalStateException("Depth snapshot was not selected for this frame: "+producer);
            return depth;
        }
        var view=outputs.get(producer);
        if(view==null)view=NAMED.read(producer);
        if(view==null)view=PackCompute.image(producer);
        if(view==null) throw new IllegalArgumentException("Scene pass output unavailable: "+producer);
        return view;
    }
    /** Shared scene/world feedback storage; returned views remain owned by this renderer. */
    public static List<GpuTextureView> worldTargetWrites(Map<Integer,String> outputs,CommandEncoder encoder) {return NAMED.writes(outputs,encoder);}
    public static void worldTargetPublish(Map<Integer,String> outputs) {NAMED.publish(outputs);}
    public static GpuTextureView worldInput(String producer) {
        return inputView(frame==null?Map.of():frame.outputs,producer);
    }
    public static com.mojang.renderpearl.api.buffers.GpuBufferSlice worldUniform(String resource) {return UNIFORMS.slice(resource);}
    public static PackUniformBlock worldUniformDefinition(String resource) {return ShaderRuntime.active().definition().resources().uniforms().get(resource);}

    private static UniformBindings uniformBindings(PackPass pass) {
        var result=new LinkedHashMap<String,com.mojang.renderpearl.api.buffers.GpuBufferSlice>();
        var definitions=new LinkedHashMap<String,PackUniformBlock>();
        pass.uniforms().forEach((binding,resource)->{
            result.put(binding,UNIFORMS.slice(resource));definitions.put(binding,ShaderRuntime.active().definition().resources().uniforms().get(resource));
        });
        return new UniformBindings(Map.copyOf(result),Map.copyOf(definitions));
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
        draw(encoder,fragment,List.of(output),color,depth,lightmapInput,scene,width,height,view,voxels,materials,history,historyCamera,namedInputs);
    }
    private static void draw(CommandEncoder encoder,String fragment,List<GpuTextureView> outputs,GpuTextureView color,
                             GpuTextureView depth,GpuTextureView lightmapInput,GpuTextureView scene,int width,int height,org.joml.Matrix4fc view,
                             GpuBuffer voxels,GpuTextureView materials,PassHistory history,MarineHistoryFrame.Camera historyCamera,Map<String,GpuTextureView> namedInputs) {
        draw(encoder,fragment,outputs,color,depth,lightmapInput,scene,width,height,view,voxels,materials,history,historyCamera,namedInputs,UniformBindings.NONE);
    }
    private static void draw(CommandEncoder encoder,String fragment,List<GpuTextureView> outputs,GpuTextureView color,
                             GpuTextureView depth,GpuTextureView lightmapInput,GpuTextureView scene,int width,int height,org.joml.Matrix4fc view,
                             GpuBuffer voxels,GpuTextureView materials,PassHistory history,MarineHistoryFrame.Camera historyCamera,Map<String,GpuTextureView> namedInputs,
                             UniformBindings uniformBindings) {
        draw(encoder,fragment,outputs,color,depth,lightmapInput,scene,width,height,view,voxels,materials,history,historyCamera,namedInputs,uniformBindings,null,3);
    }
    private static void draw(CommandEncoder encoder,String fragment,List<GpuTextureView> outputs,GpuTextureView color,
                             GpuTextureView depth,GpuTextureView lightmapInput,GpuTextureView scene,int width,int height,org.joml.Matrix4fc view,
                             GpuBuffer voxels,GpuTextureView materials,PassHistory history,MarineHistoryFrame.Camera historyCamera,Map<String,GpuTextureView> namedInputs,
                             UniformBindings uniformBindings,String vertex,int vertices) {
        var formats=outputs.stream().map(output->output.texture().getFormat()).toList();
        var compiled = compiled(fragment,vertex,formats,history!=null,history!=null && history.lightingHistory,namedInputs.keySet().stream().sorted().toList(),uniformBindings.definitions());
        var uniform = frameUniform(encoder,width,height,view);
        var rayData=voxels==null?null:com.koper.greenmarine.client.rt.RayWorld.bindings(encoder);
        var environment=voxels==null?null:com.koper.greenmarine.client.world.WorldPrograms.environmentBindings(encoder);
        var surfaceFrame=voxels==null?null:com.koper.greenmarine.client.world.WorldPrograms.surfaceBindings(encoder);
        var surfaceRayData=voxels==null?null:com.koper.greenmarine.client.rt.RayWorld.surfaceBindings(encoder);
        var heightField=voxels==null?null:com.koper.greenmarine.client.world.WorldPrograms.heightField(encoder);
        var historyData=history==null?null:historyUniform(encoder,history,historyCamera);
        var descriptor=com.mojang.renderpearl.api.commands.RenderPassDescriptor.builder(()->"GreenMarine shaderpack");
        for(var output:outputs)descriptor.withColorAttachment(output);
        try (var pass = encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(compiled); pass.setUniform("MarineFrame",uniform);
            pass.setUniform("ColorSampler",color,RenderSystem.getSamplerCache().getClampToEdge(SceneTargets.integer(color.texture().getFormat())?FilterMode.NEAREST:FilterMode.LINEAR));
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
                pass.setUniform("MarineHeightField",heightField,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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
            PackCompute.bindRaster(pass);
            uniformBindings.slices().forEach(pass::setUniform);
            for(var binding:namedInputs.entrySet()) pass.setUniform(binding.getKey(),binding.getValue(),RenderSystem.getSamplerCache().getClampToEdge(
                DEPTHS.owns(binding.getValue()) || SceneTargets.integer(binding.getValue().texture().getFormat())?FilterMode.NEAREST:FilterMode.LINEAR));
            pass.draw(vertices,1,0,0);
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
        return pipelineFor(fragment,format,history,lightingHistory,inputs,PackResources.NONE);
    }
    /** Pack buffers/images join the layout as raster storage; unused names cost nothing (only reflected ones are bound). */
    static RenderPipeline pipelineFor(String fragment,GpuFormat format,boolean history,boolean lightingHistory,List<String> inputs,PackResources resources) {
        return pipelineFor(fragment,List.of(format),history,lightingHistory,inputs,resources);
    }
    static RenderPipeline pipelineFor(String fragment,List<GpuFormat> formats,boolean history,boolean lightingHistory,List<String> inputs,PackResources resources) {
        return pipelineFor(fragment,formats,history,lightingHistory,inputs,resources,List.of());
    }
    static RenderPipeline pipelineFor(String fragment,List<GpuFormat> formats,boolean history,boolean lightingHistory,List<String> inputs,PackResources resources,List<String> uniforms) {
        return pipelineFor(fragment,formats,history,lightingHistory,inputs,resources,uniforms,null);
    }
    static RenderPipeline pipelineFor(String fragment,List<GpuFormat> formats,boolean history,boolean lightingHistory,List<String> inputs,PackResources resources,List<String> uniforms,String vertex) {
        formats=List.copyOf(formats);if(formats.isEmpty())throw new IllegalArgumentException("No colour attachments");
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
                .withUniform("RayReflection",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("RaySurfaceExtra",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("MarineHeightField",UniformType.COMBINED_IMAGE_SAMPLER);
        if(history) layout.withUniform("MarineHistoryFrame",UniformType.UNIFORM_BUFFER)
            .withUniform("HistorySampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("HistoryNormalDepth",UniformType.COMBINED_IMAGE_SAMPLER);
        if(lightingHistory) layout.withUniform("HistoryAlbedoMaterial",UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("HistorySceneDepth",UniformType.COMBINED_IMAGE_SAMPLER);
        for(var input:inputs) layout.withUniform(input,UniformType.COMBINED_IMAGE_SAMPLER);
        uniforms=uniforms.stream().sorted().toList();
        for(var name:uniforms)layout.withUniform(name,UniformType.UNIFORM_BUFFER);
        var storage=new LinkedHashMap<String,RasterStorage.Kind>();
        boolean vulkan=RenderSystem.tryGetDevice()!=null && ComputeBackend.vulkan(RenderSystem.getDevice())!=null;
        if(vulkan) {
            for(var name:resources.buffers().keySet()) { layout.withUniform(name,UniformType.UNIFORM_BUFFER); storage.put(name,RasterStorage.Kind.BUFFER); }
            for(var name:resources.images().keySet()) { layout.withUniform(name,UniformType.COMBINED_IMAGE_SAMPLER); storage.put(name,RasterStorage.Kind.IMAGE); }
        }
        var location=Identifier.fromNamespaceAndPath("greenmarine","pack/"+Integer.toUnsignedString(fragment.hashCode())+"/"+Integer.toUnsignedString(formats.hashCode())+(history?"/history":"")+(lightingHistory?"/surfaces":"")+(inputs.isEmpty()?"":"/inputs/"+Integer.toUnsignedString(inputs.hashCode()))
            +(storage.isEmpty()?"":"/storage/"+Integer.toUnsignedString(storage.hashCode()))+(uniforms.isEmpty()?"":"/uniforms/"+Integer.toUnsignedString(uniforms.hashCode()))+(vertex==null?"":"/vertex/"+Integer.toUnsignedString(vertex.hashCode())));
        RasterStorage.declare(location.toString(),storage);
        var builder=RenderPipeline.builder().withLocation(location)
            .withVertexShader(vertex==null?Identifier.fromNamespaceAndPath("greenmarine","pack/screenquad"):Identifier.parse(vertex)).withFragmentShader(Identifier.parse(fragment))
            .withBindGroupLayout(layout.build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        for(int i=0;i<formats.size();i++)builder.withColorTargetState(i,new ColorTargetState(Optional.empty(),formats.get(i),ColorTargetState.WRITE_ALL));
        return builder.build();
    }
    private static CompiledRenderPipeline compiled(String fragment,String vertex,List<GpuFormat> formats,boolean history,boolean lightingHistory,List<String> inputs,Map<String,PackUniformBlock> uniforms) {
        var resources=ShaderRuntime.active().definition().resources();
        return PIPELINES.computeIfAbsent(new PipelineKey(fragment,vertex,formats,history,lightingHistory,inputs,resources,uniforms), key -> {
            try (var source = new PackShaders(ShaderRuntime.active(),uniforms)) {
                var pending = RenderSystem.getDevice().compilePipeline(pipelineFor(fragment,formats,history,lightingHistory,inputs,resources,uniforms.keySet().stream().sorted().toList(),vertex),source,Runnable::run).join();
                var compiled = pending.finishCompile();
                if (compiled == null) throw new IllegalArgumentException("Unable to compile pack shader: "+fragment);
                return compiled;
            }
        });
    }
    private static void fail(Throwable error) { com.koper.greenmarine.client.world.WorldPrograms.cancelTargets();frame=null; failed = true; lightmapReady = false; closeTargets(); schedule=null; ShaderRuntime.renderFailure(error); GreenMarine.LOGGER.error("Shaderpack rendering disabled until pack switch/reload",error); }
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
    private static void closeTargets() { DEPTHS.close(); NAMED.close(); closeHistories(); closeRetained(); TARGETS.values().forEach(pair -> { for (var target : pair) target.destroyBuffers(); }); TARGETS.clear(); }
    public static void close() {
        frame=null;PHASES.reset();frameEpoch=0;UNIFORMS.reset();HOST_INPUTS.reset();CUSTOM_INPUTS.reset();PackCompute.close(); closeTargets(); PIPELINES.values().forEach(CompiledRenderPipeline::close); PIPELINES.clear();
        if (lightmap != null) lightmap.destroyBuffers(); lightmap = null;
        lightmapReady = failed = logged = false; lightmapTick = -1; sceneWidth = sceneHeight = 0;
        sceneFrames = submissionNanos = shadedPixels = 0;
        schedule=null; sceneFormat=sceneDepthFormat=null; scheduledCaps=null; CPU.clear();
        if(gpuTimer!=null) gpuTimer.close(); gpuTimer=null;
    }
}
