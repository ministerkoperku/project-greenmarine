package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.nativebridge.NativeRayTracer;
import com.koper.greenmarine.mixin.*;
import com.koper.greenmarine.api.pack.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.nio.*;
import java.util.*;

/** Native ray queries and owned host textures; never owns the host Vulkan device or atlas. */
public final class RayWorld {
    private static NativeRayTracer nativeRt;
    private static boolean initialized,failed,ready,reported,surfaceInitialized,surfaceFailed,surfaceReady,frameReady,playerShadowTraced,surfaceLightsActive;
    private static String surfaceDetail="Surface rays have not submitted";
    private static GpuTexture surfaceColor,reflection,surfaceExtra;
    private static GpuTextureView surfaceColorView,reflectionView,surfaceExtraView;
    private static GpuBuffer surfaceMetadata;
    private static final ByteBuffer SURFACE_META=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private static int surfaceWidth,surfaceHeight;
    private static long surfaceFrames;
    private static GpuTimer surfaceGpu;
    private static final PerformanceSamples SURFACE_CPU=new PerformanceSamples();
    private static String detail="Hardware RT has not initialized";
    private static final WorldSceneScanner SCAN=new WorldSceneScanner();
    private record SceneInfo(int x,int y,int z,int triangles,long hash) {
        static SceneInfo of(TriangleScene mesh) {return new SceneInfo(mesh.x(),mesh.y(),mesh.z(),mesh.triangles(),mesh.hash());}
        float cameraX(double value) {return (float)(value-x);}
        float cameraY(double value) {return (float)(value-y);}
        float cameraZ(double value) {return (float)(value-z);}
    }
    private record Prepared(long epoch,long revision,SceneBounds bounds,SceneInfo scene,long token,int status,String error,long packNanos,long prepareNanos,boolean unchanged) {}
    private static final class Submission {
        final long serial;final GpuFence fence;final CommandEncoder encoder;final List<Object> references;
        boolean submitted;
        Submission(long serial,GpuFence fence,CommandEncoder encoder,Object... references) {
            this.serial=serial;this.fence=fence;this.encoder=encoder;this.references=List.of(references);
        }
    }
    public record DynamicMetrics(long generation,long submittedGeneration,long lastPublishNanos,long lastSubmitNanos,
            long requestedRevision,long publishedRevision,int pendingSections,int x,int y,int z,long packNanos,
            long prepareNanos,long publishNanos,long busyCount,long discarded,WorldSceneScanner.Metrics scanner,long[] nativeStats) {}
    private static SceneInfo scene;
    private static ScenePreparation<Prepared> preparation;
    private static Prepared prepared;
    private static Long requestedContent;
    private static final Deque<Submission> SUBMISSIONS=new ArrayDeque<>();
    private static long epoch,serial,generation,submittedGeneration,lastPublishNanos,lastSubmitNanos;
    private static int surfaceSkipStreak;
    private static long laggingSince;
    /**
     * Diagnostics for the owner's "shadows gone for a second while walking" report: logs once when the
     * camera nears the edge of the published RT scene (traced shadows fade out there) and once when a
     * publication catches up, with what held it back.
     */
    private static void laggingScene(double x,double y,double z) {
        if(scene==null)return;
        double edge=Math.min(Math.min(x-scene.x(),scene.x()+SceneBounds.WIDTH-x),Math.min(z-scene.z(),scene.z()+SceneBounds.WIDTH-z));
        edge=Math.min(edge,Math.min(y-scene.y(),scene.y()+SceneBounds.HEIGHT-y));
        if(edge<12 && laggingSince==0) {
            laggingSince=System.nanoTime();
            GreenMarine.LOGGER.info("RT scene lagging: camera {} blocks from the published scene edge; pending sections={}, preparing={}, prepared={}, last publish {} ms ago",
                String.format("%.1f",edge),SCAN.boundsReadyPending(),preparation!=null && preparation.busy(),prepared!=null,
                lastPublishNanos==0?-1:(System.nanoTime()-lastPublishNanos)/1_000_000);
        } else if(edge>=16 && laggingSince!=0) {
            GreenMarine.LOGGER.info("RT scene caught up after {} ms (publish {} ms total so far, prepare {} ms total, busy={})",
                (System.nanoTime()-laggingSince)/1_000_000,publishNanos/1_000_000,prepareNanos/1_000_000,busyCount);
            laggingSince=0;
        }
    }
    private static long requestedRevision=-1,publishedRevision=-1,packNanos,prepareNanos,publishNanos,busyCount,discarded;
    public static DynamicMetrics dynamicMetrics() {
        return new DynamicMetrics(generation,submittedGeneration,lastPublishNanos,lastSubmitNanos,requestedRevision,publishedRevision,
            SCAN.boundsReadyPending(),scene==null?0:scene.x(),scene==null?0:scene.y(),scene==null?0:scene.z(),packNanos,prepareNanos,publishNanos,
            busyCount,discarded,SCAN.metrics(),nativeRt!=null?nativeRt.asyncStats():new long[14]);
    }
    public static void invalidateBlock(BlockPos position) {SCAN.invalidateBlock(position);}
    public static void invalidateColumn(int x,int z) {SCAN.invalidateColumn(x,z);}
    public static void unloadColumn(int x,int z) {SCAN.unloadColumn(x,z);}
    public static void invalidateGeometry() {SCAN.invalidateAll();}

    private static final Matrix4f PROJECTION=new Matrix4f(), INVERSE=new Matrix4f();
    private static final ByteBuffer FRAME=ByteBuffer.allocateDirect(320).order(ByteOrder.nativeOrder());
    private static final ByteBuffer META=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
    private static final Vector4f SUN=new Vector4f(0,1,0,0);
    private static float ambient=.45f;
    private static GpuTexture color,normal,albedo;
    private static GpuTextureView colorView,normalView,albedoView;
    private static final GpuTexture[] EMPTY=new GpuTexture[3];
    private static final GpuTextureView[] EMPTY_VIEW=new GpuTextureView[3];
    private static GpuBuffer metadata;
    private static int width,height;
    private static long frames,scanNanos,buildNanos;
    private static GpuTimer gpu;
    private static final PerformanceSamples CPU=new PerformanceSamples();
    private RayWorld() {}
    public static boolean available() {return initialized && !failed && GreenMarineClient.engine()!=null && GreenMarineClient.engine().available();}
    public static boolean ready() {return ready && available();}
    public static String detail() {return detail;}
    public static long frames() {return frames;}
    public static int triangles() {return scene==null?0:scene.triangles();}
    public static long scanNanos() {return scanNanos;}
    public static long buildNanos() {return buildNanos;}
    public static PerformanceSamples.Snapshot cpuTiming() {return CPU.snapshot();}
    public static PerformanceSamples.Snapshot gpuTiming() {return gpu==null?new PerformanceSamples.Snapshot(0,0,0,0,0):gpu.snapshot();}
    public static void captureProjection(Matrix4f projection) {PROJECTION.set(projection);}
    private static void initialize() {
        if(initialized || failed) return;
        var device=RenderSystem.tryGetDevice();var engine=GreenMarineClient.engine();
        if(device==null || engine==null) return;
        if(!(device instanceof FrontendGpuDeviceAccessor front) || !(front.greenmarine$backend() instanceof VulkanDevice vk)) {
            detail="OpenGL: hardware RT unavailable; portable shader fallback";return;
        }
        if(!engine.available()) {detail=engine.status()+"; hardware RT unavailable";return;}
        if(!RtFeatures.enabled(((VulkanDeviceAccessor)vk).greenmarine$enabledFeatures())) {detail="Host device has not enabled the complete ray-query feature set";return;}
        VulkanHost.attachIfReady();nativeRt=engine.rayTracer();
        if(!nativeRt.available() || nativeRt.initialize(true)!=0) {failure(nativeRt.error());return;}
        initialized=true;detail="Hardware ray-query pipeline initialized; building world scene";
        GreenMarine.LOGGER.info("Hardware RT pipeline initialized on the enabled Minecraft Vulkan device (Ray Query, BLAS/TLAS)");
    }
    public static void tick(Minecraft client) {
        if(client.level!=null && client.player!=null && GreenMarineClient.enabled())initialize();
    }
    /** Extract live models only here; immutable packing/native allocation run on the worker. */
    private static void updateScene(Minecraft client) {
        var camera=client.gameRenderer.mainCamera().position();
        var bounds=SceneBounds.around((int)Math.floor(camera.x),(int)Math.floor(camera.y),(int)Math.floor(camera.z));
        SCAN.retarget(bounds,camera.x,camera.y,camera.z);
        long started=System.nanoTime();SCAN.step(client,8192,1_000_000);scanNanos+=System.nanoTime()-started;
        laggingScene(camera.x,camera.y,camera.z);
        if(nativeRt.asyncAvailable()) {
            if(preparation==null)preparation=new ScenePreparation<>();
            if(prepared==null && !preparation.busy() && requestedRevision==SCAN.revision())return;
            var snapshot=SCAN.publicationSnapshot(scene==null?null:new SceneBounds(scene.x(),scene.y(),scene.z()));
            if(prepared==null) {
                prepared=preparation.poll();
                if(prepared!=null){packNanos+=prepared.packNanos();prepareNanos+=prepared.prepareNanos();buildNanos+=prepared.prepareNanos();}
            }
            if(prepared!=null) {
                var next=prepared;
                if(next.epoch()!=epoch || snapshot==null || !next.bounds().equals(snapshot.bounds())) {
                    if(next.token()!=0)nativeRt.discardPreparedScene(next.token());discarded++;prepared=null;requestedContent=null;
                } else if(next.status()==1) {busyCount++;requestedRevision=-1;requestedContent=null;prepared=null;}
                else if(next.status()<0)throw new IllegalStateException(next.error());
                else if(next.unchanged()) {publishedRevision=next.revision();prepared=null;}
                else {
                    long publishing=System.nanoTime();
                    int code=next.scene().triangles()==0?nativeRt.resetSceneAsync():nativeRt.publishScene(next.token());
                    publishNanos+=System.nanoTime()-publishing;
                    if(code<0)throw new IllegalStateException(nativeRt.asyncError());
                    if(code==1)busyCount++;
                    else {scene=next.scene();generation++;publishedRevision=next.revision();lastPublishNanos=System.nanoTime();PackRenderer.invalidateLightingHistory();
                        prepared=null;}
                }
            }
            if(snapshot!=null && prepared==null && !preparation.busy() && requestedRevision!=SCAN.revision()) {
                long content=snapshot.contentHash();
                if(requestedContent!=null && requestedContent==content){requestedRevision=snapshot.revision();publishedRevision=requestedRevision;return;}
                requestedContent=content;
                var previous=scene;long jobEpoch=epoch;var bridge=nativeRt;
                requestedRevision=snapshot.revision();
                preparation.start(()->{
                    long packing=System.nanoTime();var mesh=snapshot.assemble();var info=SceneInfo.of(mesh);long packed=System.nanoTime()-packing;
                    boolean unchanged=info.equals(previous);
                    if(unchanged)return new Prepared(jobEpoch,snapshot.revision(),snapshot.bounds(),info,0,0,"",packed,0,true);
                    long preparing=System.nanoTime();long token=mesh.triangles()==0?0:bridge.prepareScene(mesh.data(),mesh.triangles());
                    int status=mesh.triangles()==0?0:bridge.prepareStatus();String error=bridge.prepareError();
                    return new Prepared(jobEpoch,snapshot.revision(),snapshot.bounds(),info,token,status,error,packed,System.nanoTime()-preparing,false);
                });
            }
        } else if(SCAN.boundsReadyPending()==0 && requestedRevision!=SCAN.revision()) {
            // Older native libraries retain their established synchronous ABI.
            var next=SCAN.completed();var info=SceneInfo.of(next);requestedRevision=SCAN.revision();
            if(!info.equals(scene)) {
                long build=System.nanoTime();int code=next.triangles()==0?nativeRt.resetScene():nativeRt.scene(next.data(),next.triangles());
                buildNanos+=System.nanoTime()-build;if(code!=0)throw new IllegalStateException(nativeRt.error());
                scene=info;generation++;publishedRevision=requestedRevision;lastPublishNanos=System.nanoTime();
            }
        }
    }
    private static void pollSubmissions(boolean wait) {
        while(!SUBMISSIONS.isEmpty()) {
            var submission=SUBMISSIONS.peekFirst();
            if(!submission.submitted) {
                // Still in the host's open batch (or its submit threw); a fence on it cannot be awaited.
                submission.encoder.submit();submission.submitted=true;
            }
            if(!submission.fence.awaitCompletion(wait?GpuFence.NO_TIMEOUT:0))break;
            if(nativeRt.submissionCompleted(submission.serial)!=0)throw new IllegalStateException(nativeRt.asyncError());
            submission.fence.close();SUBMISSIONS.removeFirst();
        }
    }
    /** Every queued recording went out with the host's submit. */
    public static void hostSubmitted() {for(var submission:SUBMISSIONS)submission.submitted=true;}
    /** Resource replacement only; steady rendering polls fences with a zero timeout. */
    public static void awaitSurfaceResources() {pollSubmissions(true);}
    /** Null until the native RT module is loaded. */
    public static com.koper.greenmarine.nativebridge.NativeRayTracer tracer() {return nativeRt;}
    private static long externalFrames;
    private static String externalDetail="";
    /** Hands this recording's external renderer instances to the native module. */
    private static void externalFrame(Minecraft client) {
        if(!nativeRt.externalAvailable() || scene==null)return;
        var camera=client.gameRenderer.mainCamera().position();
        var collected=com.koper.greenmarine.api.external.ExternalRenderers.collectRayGeometry(
            new com.koper.greenmarine.api.external.ExternalRenderers.RayFrame(camera.x,camera.y,camera.z,
                client.getDeltaTracker().getGameTimeDeltaPartialTick(false),++externalFrames),scene.x(),scene.y(),scene.z());
        int code=nativeRt.externalFrame(collected.tokens(),collected.transforms(),collected.count());
        if(code==-23) {
            // Device changed under a cached token: re-upload next frame, trace without externals now.
            com.koper.greenmarine.api.external.ExternalRenderers.invalidate(collected);
            code=nativeRt.externalFrame(new long[0],new float[0],0);
        }
        externalDetail=code!=0?"external geometry rejected ("+code+")"
            :collected.count()==0?"":collected.count()+" external instances"+(collected.dropped()>0?", "+collected.dropped()+" over budget":"");
    }
    /** Raw views for external forward shading; see ExternalRenderers.SurfaceOutputs. */
    public static com.koper.greenmarine.api.external.ExternalRenderers.SurfaceOutputs externalOutputs() {
        var normal=com.koper.greenmarine.client.world.WorldPrograms.surfaceNormalDepth();
        var albedo=com.koper.greenmarine.client.world.WorldPrograms.surfaceAlbedo();
        if(!surfaceReady || surfaceColorView==null || reflectionView==null || normal==null || albedo==null
            || !(surfaceColorView instanceof VulkanGpuTextureView))
            return com.koper.greenmarine.api.external.ExternalRenderers.SurfaceOutputs.UNAVAILABLE;
        return new com.koper.greenmarine.api.external.ExternalRenderers.SurfaceOutputs(true,
            ((VulkanGpuTextureView)surfaceColorView).vkImageView(),((VulkanGpuTextureView)reflectionView).vkImageView(),surfaceWidth,surfaceHeight,
            ((VulkanGpuTextureView)normal).vkImageView(),((VulkanGpuTextureView)albedo).vkImageView());
    }
    private static long beginSubmission() {
        if(!nativeRt.asyncAvailable())return 0;
        long next=++serial;int code=nativeRt.beginSubmission(next);
        if(code<0)throw new IllegalStateException(nativeRt.asyncError());
        if(code==1){busyCount++;return -1;}return next;
    }
    private static Submission queued(CommandEncoder encoder,long lease,GpuFence fence,Object... references) {
        if(lease==0)return null;
        var submission=new Submission(lease,fence,encoder,references);
        SUBMISSIONS.addLast(submission);
        if(nativeRt.submissionSubmitted(lease)!=0)throw new IllegalStateException(nativeRt.asyncError());
        return submission;
    }
    private static void submit(CommandEncoder encoder,Submission submission) {
        // Rides the frame's own host submit (hostSubmitted); see PackRenderer.renderScene.
        submittedGeneration=generation;lastSubmitNanos=System.nanoTime();
    }
    public static void renderFrame(GameRenderer renderer) {
        ready=frameReady=false;
        try {pollSubmissions(false);}catch(RuntimeException error){failure("RT fence completion failed: "+error.getMessage());}
        if(ShaderRuntime.active()!=null && available() && GreenMarineClient.enabled() && ShaderRuntime.rays().effective()==RayMode.HARDWARE
                && Minecraft.getInstance().level!=null)try {updateScene(Minecraft.getInstance());}
        catch(RuntimeException|LinkageError error){failure("World RT geometry failed: "+error.getMessage());}
        if(ShaderRuntime.active()==null || !available() || !GreenMarineClient.enabled() || ShaderRuntime.rays().effective()!=RayMode.HARDWARE || scene==null || scene.triangles()==0 || !renderer.gameRenderState().shouldRenderLevel) return;
        var main=renderer.mainRenderTarget();if(main.width<=0 || main.height<=0) return;
        int w=Math.max(1,(main.width+1)/2),h=Math.max(1,(main.height+1)/2);long started=System.nanoTime();
        var encoder=RenderSystem.getDevice().createCommandEncoder();int measurement=-1;long lease=0;boolean executed=false;GpuFence fence=null;
        try {
            prepareTraceFrame(renderer,w,h);frameReady=true;
            if(ShaderRuntime.active().definition().programs().stream().anyMatch(PackProgram::surfaceOnly))return;
            if(width!=w || height!=h) {
                pollSubmissions(true);closeTargets();color=texture("RT lighting",GpuFormat.RGBA16_FLOAT,w,h);colorView=RenderSystem.getDevice().createTextureView(color);
                normal=texture("RT normal/depth",GpuFormat.RGBA32_FLOAT,w,h);normalView=RenderSystem.getDevice().createTextureView(normal);
                albedo=texture("RT albedo",GpuFormat.RGBA16_FLOAT,w,h);albedoView=RenderSystem.getDevice().createTextureView(albedo);
                encoder.clearColorTexture(color,new Vector4f());encoder.clearColorTexture(normal,new Vector4f());encoder.clearColorTexture(albedo,new Vector4f());width=w;height=h;
            }
            if(ShaderRuntime.settings().profiling() && gpu==null) gpu=new GpuTimer();
            if(!ShaderRuntime.settings().profiling() && gpu!=null) {gpu.close();gpu=null;}
            lease=beginSubmission();if(lease<0)return;
            if(gpu!=null && frames>=40) measurement=gpu.begin(encoder);
            var atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getTextureView();
            var backend=(VulkanCommandEncoder)((FrontendCommandEncoder)encoder).backend();var command=backend.allocateAndBeginTransientCommandBuffer();
            int result=nativeRt.record(command.address(),FRAME,w,h,((VulkanGpuTexture)color).vkImage(),((VulkanGpuTexture)normal).vkImage(),((VulkanGpuTexture)albedo).vkImage(),((VulkanGpuTextureView)atlas).vkImageView());
            if(result!=0) {failure(nativeRt.error());return;}
            int ended=org.lwjgl.vulkan.VK12.vkEndCommandBuffer(command);
            if(ended!=0) {failure("Unable to finish host RT command buffer: "+ended);return;}
            // The fence snapshots this batch before host submit advances its index.
            if(lease>0)fence=encoder.createFence();
            backend.execute(command);executed=true;
            var submission=queued(encoder,lease,fence,color,normal,albedo,atlas);lease=0;
            if(gpu!=null) gpu.end(encoder,measurement);
            submit(encoder,submission);frames++;ready=true;detail="Hardware RT active: textured model BLAS/TLAS, sun shadows and diffuse rays";
            if(frames>40) CPU.add((System.nanoTime()-started)/1_000_000.0);
            if(!reported) {reported=true;GreenMarine.LOGGER.info("Hardware world rays submitted: triangles={}, size={}x{}, BLAS/TLAS=true, atlas=true",scene.triangles(),w,h);}
        } catch(RuntimeException|LinkageError error) {failure("Hardware RT submission failed: "+error.getMessage());}
        finally {if(lease>0 && !executed){nativeRt.submissionCancel(lease);if(fence!=null)fence.close();}}
    }
    private static void prepareTraceFrame(GameRenderer renderer,int w,int h) {
            var camera=renderer.gameRenderState().levelRenderState.cameraRenderState;
            RayCamera.write(FRAME,PROJECTION,camera.viewRotationMatrix);
            FRAME.putFloat(256,scene.cameraX(camera.pos.x)).putFloat(260,scene.cameraY(camera.pos.y)).putFloat(264,scene.cameraZ(camera.pos.z)).putFloat(268,0);
            FRAME.putFloat(272,w).putFloat(276,h).putFloat(280,RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()?1:0).putFloat(284,0);
            var sky=renderer.gameRenderState().levelRenderState.skyRenderState;
            SUN.set(RaySun.shadowLight(sky.sunAngle,sky.rainBrightness,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,parameter("sunStrength",2f)));
            ambient=RaySun.ambient(sky.sunAngle,sky.rainBrightness,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD);
            SUN.get(288,FRAME);
            FRAME.putFloat(304,ambient).putFloat(308,parameter("giStrength",.7f)).putFloat(312,scene.x()).putFloat(316,scene.z());FRAME.position(0).limit(320);
    }
    public static void beginSurfaceFrame() {surfaceReady=false;}
    public static boolean surfaceUnavailable() {return surfaceFailed;}
    /** The native module traces block/dynamic light shadows; the terrain shader then hands the lightmap term over. */
    public static boolean surfaceLightsActive() {return surfaceLightsActive && available() && !surfaceFailed;}
    /** The last surface dispatch cast the local player's sun shadow, so the vanilla blob would double it. */
    public static boolean playerShadowTraced() {return playerShadowTraced && !surfaceFailed && GreenMarineClient.enabled()
        && ShaderRuntime.active()!=null && ShaderRuntime.rays().effective()==RayMode.HARDWARE;}
    public static boolean surfaceReady() {return surfaceReady && available() && !surfaceFailed;}
    public static String surfaceDetail() {return surfaceDetail;}
    public static long surfaceFrames() {return surfaceFrames;}
    public static PerformanceSamples.Snapshot surfaceCpuTiming() {return SURFACE_CPU.snapshot();}
    public static PerformanceSamples.Snapshot surfaceGpuTiming() {return surfaceGpu==null?new PerformanceSamples.Snapshot(0,0,0,0,0):surfaceGpu.snapshot();}
    public static GpuTextureView surfaceLighting() {return surfaceReady()?surfaceColorView:EMPTY_VIEW[0];}
    public static GpuTextureView reflections() {return surfaceReady()?reflectionView:EMPTY_VIEW[2];}
    /** x = ambient occlusion, y = sunlit camera-ray fraction, z = marched length (0 = none), w = 1 when written; unavailable is all zero. */
    public static GpuTextureView surfaceExtra() {return surfaceReady() && surfaceExtraView!=null?surfaceExtraView:EMPTY_VIEW[0];}
    public static GpuBuffer surfaceBindings(CommandEncoder encoder) {
        if(EMPTY_VIEW[0]==null)bindings(encoder);
        if(surfaceMetadata==null)surfaceMetadata=RenderSystem.getDevice().createBuffer(()->"GreenMarine RaySurfaceFrame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
        SURFACE_META.putFloat(0,surfaceReady()?1:0).putFloat(4,surfaceReady()?surfaceWidth:1).putFloat(8,surfaceReady()?surfaceHeight:1).putFloat(12,96);
        SURFACE_META.position(0).limit(16);encoder.writeToBuffer(surfaceMetadata.slice(),SURFACE_META);return surfaceMetadata;
    }
    public static void renderSurface(GpuTextureView normalDepth,GpuTextureView material,int viewportWidth,int viewportHeight) {
        // Cleared up front: any skipped dispatch (busy slots during a scene rebuild, no frame) must hand
        // the vanilla blob back, or the player stands there with no shadow at all until the next trace.
        boolean hadPlayerShadow=playerShadowTraced;
        surfaceReady=false;playerShadowTraced=false;
        if(!available() || surfaceFailed || !frameReady || ShaderRuntime.rays().effective()!=RayMode.HARDWARE)return;
        long started=System.nanoTime();long lease=0;boolean executed=false;GpuFence fence=null;
        try {
            if(!surfaceInitialized) {
                if(!nativeRt.surfaceAvailable() || nativeRt.initializeSurface(true)!=0)throw new IllegalStateException(nativeRt.surfaceError());
                surfaceInitialized=true;
            }
            var size=SurfaceRayResolution.forPack(ShaderRuntime.active().definition().id(),ShaderRuntime.settings().quality(),viewportWidth,viewportHeight);
            int w=size.width(),h=size.height();
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            if(w!=surfaceWidth || h!=surfaceHeight) {
                pollSubmissions(true);closeSurfaceTargets();surfaceColor=texture("surface ray lighting",GpuFormat.RGBA16_FLOAT,w,h);reflection=texture("traced surface reflection",GpuFormat.RGBA16_FLOAT,w,h);
                surfaceColorView=RenderSystem.getDevice().createTextureView(surfaceColor);reflectionView=RenderSystem.getDevice().createTextureView(reflection);
                encoder.clearColorTexture(surfaceColor,new Vector4f());encoder.clearColorTexture(reflection,new Vector4f());surfaceWidth=w;surfaceHeight=h;
                if(nativeRt.extraAvailable()) {
                    surfaceExtra=texture("surface ray occlusion and scattering",GpuFormat.RGBA16_FLOAT,w,h);
                    surfaceExtraView=RenderSystem.getDevice().createTextureView(surfaceExtra);encoder.clearColorTexture(surfaceExtra,new Vector4f(1,0,0,0));
                }
                GreenMarine.LOGGER.info("Surface RT target: pack={}, quality={}, output={}x{}, capture={}x{}",
                    ShaderRuntime.active().definition().id(),ShaderRuntime.settings().quality(),w,h,viewportWidth,viewportHeight);
            }
            if(ShaderRuntime.settings().profiling() && surfaceGpu==null)surfaceGpu=new GpuTimer();
            if(!ShaderRuntime.settings().profiling() && surfaceGpu!=null) {surfaceGpu.close();surfaceGpu=null;}
            FRAME.putFloat(268,indoor()).putFloat(272,w).putFloat(276,h).putFloat(284,volumetricSteps());FRAME.position(0).limit(320);
            lease=beginSubmission();
            if(lease<0){
                surfaceDetail="RT submission slots busy; raster surface fallback";
                if(++surfaceSkipStreak==30 || hadPlayerShadow && surfaceSkipStreak==1)
                    GreenMarine.LOGGER.info("Surface RT skipped {} frame(s) in a row: submission slots busy (scene publications={}, pending sections={})",
                        surfaceSkipStreak,generation,SCAN.boundsReadyPending());
                return;
            }
            if(surfaceSkipStreak>=30)GreenMarine.LOGGER.info("Surface RT resumed after {} skipped frames",surfaceSkipStreak);
            surfaceSkipStreak=0;
            int measure=surfaceGpu!=null && surfaceFrames>=40?surfaceGpu.begin(encoder):-1;
            var atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getTextureView();
            externalFrame(Minecraft.getInstance());
            if(nativeRt.surfaceLightsAvailable() && scene!=null) {
                int lights=SurfaceLights.collect(Minecraft.getInstance(),scene.x(),scene.y(),scene.z());
                surfaceLightsActive=nativeRt.surfaceLights(SurfaceLights.data(),lights)==0;
            } else surfaceLightsActive=false;
            // Extended modules take 64 boxes plus the combined accessory atlas; legacy keeps 16 boxes/one skin.
            boolean extendedActors=nativeRt.extendedActorsAvailable();
            var player=(extendedActors || nativeRt.actorsAvailable()) && scene!=null
                ?PlayerReflection.snapshot(Minecraft.getInstance(),scene.x(),scene.y(),scene.z(),extendedActors):null;
            var skin=player!=null && player.boxes()>0 && player.skin()!=null?player.skin():atlas;
            var backend=(VulkanCommandEncoder)((FrontendCommandEncoder)encoder).backend();var command=backend.allocateAndBeginTransientCommandBuffer();
            int result=player!=null && extendedActors && surfaceExtra!=null
                ?nativeRt.recordSurfaceExtra(command.address(),FRAME,w,h,((VulkanGpuTexture)surfaceColor).vkImage(),((VulkanGpuTexture)reflection).vkImage(),
                    ((VulkanGpuTexture)surfaceExtra).vkImage(),((VulkanGpuTextureView)atlas).vkImageView(),((VulkanGpuTextureView)normalDepth).vkImageView(),
                    ((VulkanGpuTextureView)material).vkImageView(),player.data(),((VulkanGpuTextureView)skin).vkImageView())
                :player==null
                ?nativeRt.recordSurface(command.address(),FRAME,w,h,((VulkanGpuTexture)surfaceColor).vkImage(),((VulkanGpuTexture)reflection).vkImage(),
                    ((VulkanGpuTextureView)atlas).vkImageView(),((VulkanGpuTextureView)normalDepth).vkImageView(),((VulkanGpuTextureView)material).vkImageView())
                :extendedActors?nativeRt.recordSurfaceActorsExtended(command.address(),FRAME,w,h,((VulkanGpuTexture)surfaceColor).vkImage(),((VulkanGpuTexture)reflection).vkImage(),
                    ((VulkanGpuTextureView)atlas).vkImageView(),((VulkanGpuTextureView)normalDepth).vkImageView(),((VulkanGpuTextureView)material).vkImageView(),
                    player.data(),((VulkanGpuTextureView)skin).vkImageView())
                :nativeRt.recordSurfaceActors(command.address(),FRAME,w,h,((VulkanGpuTexture)surfaceColor).vkImage(),((VulkanGpuTexture)reflection).vkImage(),
                    ((VulkanGpuTextureView)atlas).vkImageView(),((VulkanGpuTextureView)normalDepth).vkImageView(),((VulkanGpuTextureView)material).vkImageView(),
                    player.data(),((VulkanGpuTextureView)skin).vkImageView());
            if(result!=0)throw new IllegalStateException(nativeRt.surfaceError());
            int ended=org.lwjgl.vulkan.VK12.vkEndCommandBuffer(command);if(ended!=0)throw new IllegalStateException("Unable to finish surface rays: "+ended);
            if(lease>0)fence=encoder.createFence();
            backend.execute(command);executed=true;
            var submission=queued(encoder,lease,fence,surfaceColor,reflection,Objects.requireNonNullElse(surfaceExtra,surfaceColor),atlas,normalDepth,material,skin);lease=0;
            if(surfaceGpu!=null)surfaceGpu.end(encoder,measure);
            submit(encoder,submission);surfaceFrames++;surfaceReady=true;playerShadowTraced=player!=null && player.boxes()>0;
            surfaceDetail="Hardware surface RT active: raster materials, sun/GI and offscreen model reflections; "
                +(player==null?"local player extension unavailable":PlayerReflection.detail())
                +(externalDetail.isEmpty()?"":"; "+externalDetail);
            if(surfaceFrames>40)SURFACE_CPU.add((System.nanoTime()-started)/1e6);
        } catch(RuntimeException|LinkageError error) {
            surfaceFailed=true;playerShadowTraced=false;surfaceDetail="Surface RT unavailable: "+error.getMessage()+"; raster surface fallback";GreenMarine.LOGGER.error(surfaceDetail,error);
        } finally {if(lease>0 && !executed){nativeRt.submissionCancel(lease);if(fence!=null)fence.close();}}
    }
    private static void closeSurfaceTargets() {
        if(surfaceColorView!=null)surfaceColorView.close();if(reflectionView!=null)reflectionView.close();if(surfaceExtraView!=null)surfaceExtraView.close();
        if(surfaceColor!=null)surfaceColor.close();if(reflection!=null)reflection.close();if(surfaceExtra!=null)surfaceExtra.close();
        surfaceColorView=reflectionView=surfaceExtraView=null;surfaceColor=reflection=surfaceExtra=null;surfaceWidth=surfaceHeight=0;
    }
    /** Shadow rays per camera ray for volumetric sunlight; packs opt in with a positive `volumetric` parameter. */
    private static float indoor;
    /** 0 under open sky, 1 with no sky light at the camera; eased so doorways do not pop. */
    private static float indoor() {
        var client=Minecraft.getInstance();
        if(client.level==null)return indoor;
        var camera=client.gameRenderer.mainCamera().position();
        // Only real enclosure counts: under trees or in tall grass sky light is 12..14, which made
        // sunlit outdoor air dense and the fog vanish a few blocks higher.
        int sky=client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,BlockPos.containing(camera));
        float target=1f-Math.clamp((sky-3)/7f,0f,1f);
        indoor+=(target-indoor)*.05f;
        return Math.clamp(indoor,0f,1f);
    }
    private static int volumetricSteps() {
        if(parameter("volumetric",0f)<=0f)return 0;
        return switch(ShaderRuntime.settings().quality()) {case LOW->4;case MEDIUM->6;case HIGH->8;case ULTRA->12;};
    }
    private static float parameter(String name,float fallback) {
        var pack=ShaderRuntime.active();var script=ShaderRuntime.script();
        return pack!=null && script!=null && pack.definition().parameters().stream().anyMatch(p->p.name().equals(name))?script.value(name):fallback;
    }
    private static GpuTexture texture(String label,GpuFormat format,int w,int h) {
        return RenderSystem.getDevice().createTexture("GreenMarine "+label,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,format,w,h,1,1);
    }
    public static GpuTextureView lighting() {return ready()?colorView:EMPTY_VIEW[0];}
    public static GpuTextureView normals() {return ready()?normalView:EMPTY_VIEW[1];}
    public static GpuTextureView albedo() {return ready()?albedoView:EMPTY_VIEW[2];}
    public static GpuBuffer bindings(CommandEncoder encoder) {
        for(int i=0;i<EMPTY.length;i++) if(EMPTY[i]==null) {
            EMPTY[i]=texture("empty world ray data "+i,i==1?GpuFormat.RGBA32_FLOAT:GpuFormat.RGBA16_FLOAT,1,1);
            EMPTY_VIEW[i]=RenderSystem.getDevice().createTextureView(EMPTY[i]);encoder.clearColorTexture(EMPTY[i],new Vector4f());
        }
        if(metadata==null) metadata=RenderSystem.getDevice().createBuffer(()->"GreenMarine RayFrame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,80);
        META.putFloat(0,ready()?1:0).putFloat(4,ready()?width:1).putFloat(8,ready()?height:1).putFloat(12,ready()?scene.triangles():0);
        var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        float x=scene==null?0:scene.cameraX(camera.x),y=scene==null?0:scene.cameraY(camera.y),z=scene==null?0:scene.cameraZ(camera.z);
        META.putFloat(16,-x).putFloat(20,-y).putFloat(24,-z).putFloat(28,0);
        META.putFloat(32,SceneBounds.WIDTH-x).putFloat(36,SceneBounds.HEIGHT-y).putFloat(40,SceneBounds.WIDTH-z).putFloat(44,0);
        SUN.get(48,META);META.putFloat(64,ambient).putFloat(68,parameter("giStrength",.7f))
            .putFloat(72,lastPublishNanos==0?1:(float)Math.clamp((System.nanoTime()-lastPublishNanos)/1e9,0,1)).putFloat(76,scene==null?0:1);
        META.position(0).limit(80);
        encoder.writeToBuffer(metadata.slice(),META);return metadata;
    }
    private static void failure(String reason) {failed=true;ready=false;detail=reason;GreenMarine.LOGGER.error("Hardware RT disabled: {}",reason);ShaderRuntime.frameStatus();}
    private static void closeTargets() {
        if(colorView!=null) colorView.close();if(normalView!=null) normalView.close();if(albedoView!=null) albedoView.close();
        if(color!=null) color.close();if(normal!=null) normal.close();if(albedo!=null) albedo.close();
        colorView=normalView=albedoView=null;color=normal=albedo=null;width=height=0;
    }
    public static void reset() {
        epoch++;
        if(preparation!=null) {
            preparation.close();
            try {var result=preparation.poll();if(result!=null && result.token()!=0)nativeRt.discardPreparedScene(result.token());}
            catch(RuntimeException error){GreenMarine.LOGGER.error("Scene preparation failed during reset",error);}
            preparation=null;
        }
        if(prepared!=null && prepared.token()!=0)nativeRt.discardPreparedScene(prepared.token());prepared=null;
        pollSubmissions(true);PlayerReflection.close();SCAN.reset();requestedRevision=publishedRevision=-1;requestedContent=null;
        generation=submittedGeneration=lastPublishNanos=lastSubmitNanos=packNanos=prepareNanos=publishNanos=busyCount=discarded=0;
        if(nativeRt!=null && initialized) nativeRt.resetScene();
        laggingSince=0;surfaceSkipStreak=0;
        scene=null;ready=reported=surfaceReady=surfaceFailed=frameReady=playerShadowTraced=surfaceLightsActive=false;SurfaceLights.reset();surfaceFrames=0;SURFACE_CPU.clear();closeSurfaceTargets();
        if(surfaceMetadata!=null)surfaceMetadata.close();surfaceMetadata=null;if(surfaceGpu!=null)surfaceGpu.close();surfaceGpu=null;surfaceDetail="Surface rays have not submitted";failed=false;frames=scanNanos=buildNanos=0;CPU.clear();closeTargets();
        detail=initialized?"Hardware pipeline initialized; waiting for a world scene":"Hardware RT has not initialized";
        if(gpu!=null) gpu.close();gpu=null;
        if(metadata!=null) metadata.close();metadata=null;
        for(int i=0;i<EMPTY.length;i++) {if(EMPTY_VIEW[i]!=null) EMPTY_VIEW[i].close();if(EMPTY[i]!=null) EMPTY[i].close();EMPTY_VIEW[i]=null;EMPTY[i]=null;}
    }
    public static void shutdown() {reset();initialized=surfaceInitialized=false;nativeRt=null;}
}
