package com.koper.greenmarine.client.world;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.WorldPipeline;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.renderer.chunk.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.Identifier;
import org.joml.*;
import java.nio.*;
import java.util.*;

/** Pack-owned world pipeline variants and per-frame material inputs. */
public final class WorldPrograms {
    public static final int SURFACE_BYTES=128;
    private static final Map<String,RenderPipeline> HOST=new HashMap<>();
    private static final Map<String,CompiledRenderPipeline> VARIANTS=new HashMap<>();
    private static final Set<CompiledRenderPipeline> OWNED=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final OpaqueScene OPAQUE=new OpaqueScene();
    private static final SurfaceScene MATERIALS=new SurfaceScene();
    private static final BackgroundSky BACKGROUND=new BackgroundSky();
    private static final DistantTerrain DISTANT=new DistantTerrain();
    private static final HeightField HEIGHTS=new HeightField();
    private static final PbrAtlas PBR=new PbrAtlas();
    private static final Map<String,CompiledRenderPipeline> CAPTURE_VARIANTS=new HashMap<>();
    private static final WeatherMemory WEATHER=new WeatherMemory();
    private static final ByteBuffer ENVIRONMENT=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
    private static final ByteBuffer FRAME=ByteBuffer.allocateDirect(MarineFrame.BYTES).order(ByteOrder.nativeOrder());
    private static final ByteBuffer SURFACE=ByteBuffer.allocateDirect(SURFACE_BYTES).order(ByteOrder.nativeOrder());
    private static GpuBuffer frame,surface,surfaceOpaque,environment,ray,voxel,surfaceRay,materialProfiles,materialExtension;
    private static com.mojang.renderpearl.api.textures.GpuTextureView voxelMaterials,heightField,pbrSpecular,pbrNormal;
    private static boolean prepared,failed,pendingDraw,logged,captureMode,captureWater,surfaceReady,captureUnsupported;
    private static boolean earlyMaterials,backgroundDrawn;
    private static final boolean DISTANT_HORIZONS=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static long draws,captures,bindingEpoch,bindingUpdates,bindingSkips;
    private static GpuTimer mainGpu;
    private static int mainMeasurement=-1;
    private static long mainFrames;
    private static final PerformanceSamples COPY_CPU=new PerformanceSamples();
    private WorldPrograms() {}
    public static boolean enabled() {return !failed && prepared && GreenMarineClient.enabled() && ShaderRuntime.active()!=null
        && !ShaderRuntime.active().definition().programs().isEmpty() && ShaderRuntime.script()!=null && !ShaderRuntime.script().failed();}
    public static long draws() {return draws;}
    public static long bindingUpdates() {return bindingUpdates;}
    public static long bindingSkips() {return bindingSkips;}
    public static long captures() {return captures;}
    public static int variants() {return VARIANTS.size();}
    public static PerformanceSamples.Snapshot copyCpu() {return COPY_CPU.snapshot();}
    public static PerformanceSamples.Snapshot mainGpu() {return mainGpu==null?new PerformanceSamples.Snapshot(0,0,0,0,0):mainGpu.snapshot();}
    public static void beginMain() {
        backgroundDrawn=false;
        mainMeasurement=-1;
        if(!ShaderRuntime.settings().profiling()) {if(mainGpu!=null)mainGpu.close();mainGpu=null;return;}
        if(mainGpu==null) {
            try {mainGpu=new GpuTimer();}
            catch(RuntimeException unavailable) {GreenMarine.LOGGER.warn("World GPU profiling unavailable: {}",unavailable.getMessage());return;}
        }
        if(++mainFrames>40)mainMeasurement=mainGpu.begin(RenderSystem.getDevice().createCommandEncoder());
    }
    public static void endMain() {if(mainGpu!=null)mainGpu.end(RenderSystem.getDevice().createCommandEncoder(),mainMeasurement);}
    public static void prepare(GameRenderer renderer,Matrix4fc projection) {
        bindingEpoch++;earlyMaterials=false;
        prepared=false;surfaceReady=false;captureUnsupported=surfaceProgram()!=null && renderer.gameRenderState().levelRenderState.renderWireframeTerrain;RayWorld.beginSurfaceFrame();
        if(renderer.gameRenderState().shouldRenderLevel) {writeEnvironment(renderer);writeSurface(renderer,projection);}
        if(failed || !GreenMarineClient.enabled() || ShaderRuntime.active()==null || ShaderRuntime.active().definition().programs().isEmpty()
            || ShaderRuntime.script()==null || ShaderRuntime.script().failed() || !renderer.gameRenderState().shouldRenderLevel) return;
        try {
            var main=renderer.mainRenderTarget();if(main.width<=0 || main.height<=0 || main.getDepthTexture()==null)return;
            var client=Minecraft.getInstance();var camera=renderer.gameRenderState().levelRenderState.cameraRenderState;
            com.koper.greenmarine.api.external.ExternalRenderers.captureCamera(projection,camera.viewRotationMatrix,camera.pos.x,camera.pos.y,camera.pos.z,
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            if(frame==null)frame=RenderSystem.getDevice().createBuffer(()->"GreenMarine world MarineFrame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,MarineFrame.BYTES);
            if(surface==null)surface=RenderSystem.getDevice().createBuffer(()->"GreenMarine MarineSurfaceFrame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,SURFACE_BYTES);
            OPAQUE.prepare(main,encoder);
            if(surfaceProgram()!=null) MATERIALS.prepare(main,encoder);
            if(surfaceOpaque==null)surfaceOpaque=RenderSystem.getDevice().createBuffer(()->"GreenMarine opaque surface frame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,SURFACE_BYTES);
            environmentBindings(encoder);surfaceRay=RayWorld.surfaceBindings(encoder);
            if(materialProfiles==null || materialExtension==null) {
                var atlas=client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
                var missing=atlas.getSprite(Identifier.parse("minecraft:missingno"));
                java.util.function.Function<String,float[]> rectangles=id -> {
                    var sprite=atlas.getSprite(Identifier.parse(id));
                    return sprite==missing?null:new float[]{sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1()};
                };
                if(materialProfiles==null) {
                    var profiles=ByteBuffer.allocateDirect(com.koper.greenmarine.api.render.BlockMaterials.FRAME_BYTES).order(ByteOrder.nativeOrder());
                    com.koper.greenmarine.api.render.BlockMaterials.writeAtlas(profiles,rectangles);
                    materialProfiles=RenderSystem.getDevice().createBuffer(()->"GreenMarine block materials",GpuBuffer.USAGE_UNIFORM,profiles);
                }
                if(materialExtension==null) {
                    var profiles=ByteBuffer.allocateDirect(com.koper.greenmarine.api.render.BlockMaterials.EXTENSION_FRAME_BYTES).order(ByteOrder.nativeOrder());
                    com.koper.greenmarine.api.render.BlockMaterials.writeAtlasExtension(profiles,rectangles);
                    materialExtension=RenderSystem.getDevice().createBuffer(()->"GreenMarine block material extension",GpuBuffer.USAGE_UNIFORM,profiles);
                }
            }
            MarineFrame.write(FRAME,main.width,main.height,GreenMarineClient.seconds(client.getDeltaTracker().getGameTimeDeltaPartialTick(false)),
                client.level==null?0:java.lang.Math.floorMod(client.level.getDefaultClockTime(),24000)/24000f,RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
                ShaderRuntime.settings().quality(),ShaderRuntime.rays().effective(),projection,camera.viewRotationMatrix,ShaderRuntime.script());
            FRAME.position(0).limit(MarineFrame.BYTES);encoder.writeToBuffer(frame.slice(),FRAME);
            SURFACE.position(0).limit(SURFACE_BYTES);encoder.writeToBuffer(surface.slice(),SURFACE);encoder.writeToBuffer(surfaceOpaque.slice(),SURFACE.rewind());ray=RayWorld.bindings(encoder);
            voxel=VoxelWorld.prepare(encoder,camera.pos.x,camera.pos.y,camera.pos.z,ShaderRuntime.rays().effective()==RayMode.VOXEL);voxelMaterials=VoxelWorld.materials(encoder);heightField=HEIGHTS.bind(encoder);pbrSpecular=PBR.specular(encoder);pbrNormal=PBR.normal(encoder);
            prepared=true;
        } catch(RuntimeException|LinkageError error) {fail(error);}
    }
    private static float wrapped(double value) {return (float)(value-java.lang.Math.floor(value/4096)*4096);}
    private static float parameter(String name,float fallback) {var pack=ShaderRuntime.active();var script=ShaderRuntime.script();return pack!=null && script!=null && pack.definition().parameters().stream().anyMatch(p->p.name().equals(name))?script.value(name):fallback;}
    /** Before the scene passes: Solstice lighting for Distant Horizons LODs (no-op without DH). */
    public static void renderDistant(GameRenderer renderer) {
        boolean active=DistantTerrain.LOADED && enabled() && DistantTerrain.shades(ShaderRuntime.active());
        DistantTerrain.override(active);
        if(!active || !renderer.gameRenderState().shouldRenderLevel)return;
        try {DISTANT.render(renderer.mainRenderTarget(),RenderSystem.getDevice().createCommandEncoder(),ShaderRuntime.active(),frame,surface,environment,renderer.levelLightmap(),heightField);}
        catch(RuntimeException|LinkageError error) {fail(error);}
    }
    public static void finishWorld() {prepared=false;}
    /** Column heights for sun shadows beyond the traced scene; only while a Solstice terrain program is active. */
    public static void tickHeightField(net.minecraft.client.Minecraft client) {
        // Not enabled(): that also requires a frame in preparation, which is never true during a client tick.
        if(!failed && GreenMarineClient.enabled() && ShaderRuntime.active()!=null && DistantTerrain.shades(ShaderRuntime.active()))HEIGHTS.tick(client);
    }
    public static CompiledRenderPipeline select(CompiledRenderPipeline original) {
        if(!enabled() || !(original instanceof FrontendRenderPipeline front) || OWNED.contains(original))return original;
        var variants=captureMode?CAPTURE_VARIANTS:VARIANTS;var cached=variants.get(front.name());if(cached!=null)return cached;
        if(HOST.isEmpty()) {for(var pipeline:RenderPipelines.requiredPipelines())HOST.put(pipeline.getLocation().toString(),pipeline);for(var pipeline:RenderPipelines.optionalPipelines())HOST.put(pipeline.getLocation().toString(),pipeline);}
        var sodiumHost=SodiumCompatibility.find(front.name());
        var host=sodiumHost!=null?sodiumHost:HOST.get(front.name());
        if(host==null) {
            if(captureMode)throw new IllegalStateException("Unsupported terrain material capture pipeline: "+front.name());
            return original;
        }
        boolean sodium=sodiumHost!=null;
        var target=sodium?PackProgram.Target.TERRAIN:target(host);if(target==null)return original;
        if(captureMode && target!=PackProgram.Target.TERRAIN) throw new IllegalStateException("Non-terrain draw in surface replay");
        var program=ShaderRuntime.active().definition().programs().stream().filter(p->p.target()==target).findFirst().orElse(null);
        if(program==null)return original;
        if(sodium && !SodiumCompatibility.supports(program)) {
            if(captureMode)throw new IllegalStateException("Unsupported Sodium terrain pack program: "+program.fragment());
            return original;
        }
        try {
            return variants.computeIfAbsent(front.name(),key->{
                var location=Identifier.fromNamespaceAndPath("greenmarine",(captureMode?"surface/":"world/")+Integer.toUnsignedString(key.hashCode()));
                var compatibleHost=sodium?SodiumCompatibility.prepare(host):host;
                var definition=captureMode?WorldPipeline.capture(compatibleHost,program,location):WorldPipeline.decorate(compatibleHost,program,location);
                try(var source=new WorldShaders(ShaderRuntime.active(),sodium)) {
                    var compiled=RenderSystem.getDevice().compilePipeline(definition,source,Runnable::run).join().finishCompile();
                    if(compiled==null)throw new IllegalArgumentException("Unable to compile world program: "+key);
                    OWNED.add(compiled);return compiled;
                }
            });
        } catch(RuntimeException|LinkageError error) {fail(error);return original;}
    }
    public static PackProgram.Target target(RenderPipeline pipeline) {
        var shader=pipeline.getShaders().get(ShaderType.FRAGMENT);if(shader==null || !shader.getNamespace().equals("minecraft"))return null;
        String name=shader.getPath();
        if(pipeline.getLocation().getPath().contains("weather"))return PackProgram.Target.WEATHER;
        if(name.equals("core/terrain"))return PackProgram.Target.TERRAIN;
        if(name.equals("core/block"))return PackProgram.Target.BLOCK;
        if(name.startsWith("core/entity"))return PackProgram.Target.ENTITY;
        if(name.equals("core/sky"))return PackProgram.Target.SKY;
        if(name.contains("clouds"))return PackProgram.Target.CLOUDS;
        if(name.equals("core/weather"))return PackProgram.Target.WEATHER;
        if(name.equals("core/particle"))return PackProgram.Target.PARTICLES;
        return null;
    }
    public static void bind(RenderPass pass,CompiledRenderPipeline pipeline,WorldBindingCache cache) {
        if(!enabled() || !OWNED.contains(pipeline)) {cache.invalidate();return;}
        int flags=(captureMode?1:0)|(captureWater?2:0)|(surfaceReady?4:0);
        if(cache.matches(pipeline,bindingEpoch,flags)) {bindingSkips++;return;}
        bind(pass,pipeline);
        cache.commit(pipeline,bindingEpoch,flags);
    }
    public static void bind(RenderPass pass,CompiledRenderPipeline pipeline) {
        if(!enabled() || !OWNED.contains(pipeline))return;
        bindingUpdates++;
        pass.setUniform("MarineFrame",frame);pass.setUniform("MarineSurfaceFrame",captureMode && !captureWater?surfaceOpaque:surface);
        pass.setUniform("MarineEnvironment",environment);pass.setUniform("RaySurfaceFrame",surfaceRay);pass.setUniform("RayFrame",ray);pass.setUniform("VoxelFrame",voxel);
        pass.setUniform("MarineMaterialFrame",materialProfiles);pass.setUniform("MarineMaterialExtension",materialExtension);
        var linear=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("MarineOpaqueColor",OPAQUE.color(),linear);pass.setUniform("MarineOpaqueDepth",OPAQUE.depth(),nearest);
        pass.setUniform("RayLighting",RayWorld.lighting(),linear);pass.setUniform("RayNormalDepth",RayWorld.normals(),nearest);pass.setUniform("RayAlbedo",RayWorld.albedo(),linear);pass.setUniform("VoxelMaterials",voxelMaterials,nearest);pass.setUniform("MarineHeightField",heightField,nearest);pass.setUniform("MarinePbrSpecular",pbrSpecular,nearest);pass.setUniform("MarinePbrNormal",pbrNormal,nearest);
        pass.setUniform("MarineSurfaceNormalDepth",!captureMode && surfaceReady?MATERIALS.normal():RayWorld.normals(),nearest);
        pass.setUniform("MarineSurfaceAlbedo",!captureMode && surfaceReady?MATERIALS.albedo():RayWorld.albedo(),nearest);
        pass.setUniform("RaySurfaceLighting",RayWorld.surfaceLighting(),linear);pass.setUniform("RayReflection",RayWorld.reflections(),linear);pass.setUniform("RaySurfaceExtra",RayWorld.surfaceExtra(),linear);
    }
    public static void drawn(CompiledRenderPipeline pipeline) {if(!captureMode && enabled() && OWNED.contains(pipeline)) {draws++;pendingDraw=true;}}
    public static void submitted() {
        if(!pendingDraw)return;pendingDraw=false;ShaderRuntime.submittedWorld();
        if(!logged){logged=true;GreenMarine.LOGGER.info("World shader programs submitted: pack={}, backend={}, variants={}",ShaderRuntime.active().definition().id(),ShaderRuntime.capabilities().backend(),VARIANTS.size());}
    }
    public static void captureOpaque(RenderTarget main) {
        if(!enabled())return;long started=System.nanoTime();
        try {var encoder=RenderSystem.getDevice().createCommandEncoder();
            if(!backgroundDrawn)drawBackground(main,encoder);
            OPAQUE.capture(main,encoder);
            SURFACE.putFloat(0,1);SURFACE.position(0).limit(SURFACE_BYTES);encoder.writeToBuffer(surface.slice(),SURFACE);captures++;COPY_CPU.add((System.nanoTime()-started)/1e6);
        } catch(RuntimeException|LinkageError error) {fail(error);}
    }
    private static void drawBackground(RenderTarget main,CommandEncoder encoder) {
        var pack=ShaderRuntime.active();backgroundDrawn=true;
        var background=pack.definition().programs().stream().filter(program->program.target()==PackProgram.Target.BACKGROUND).findFirst();
        if(background.isPresent()) {
            BACKGROUND.render(main,encoder,pack,background.get(),frame,surface,environment);draws++;pendingDraw=true;
        }
    }
    /**
     * Distant Horizons applies its LOD colour before solid terrain without writing host depth, so the
     * depth-equal-far background would paint over every LOD. With DH present the sky goes first instead.
     */
    public static void backgroundBeforeDistantTerrain(RenderTarget main) {
        if(!DISTANT_HORIZONS || !enabled() || backgroundDrawn)return;
        try {drawBackground(main,RenderSystem.getDevice().createCommandEncoder());}
        catch(RuntimeException|LinkageError error) {fail(error);}
    }
    private static PackProgram surfaceProgram() {
        return ShaderRuntime.active()==null?null:ShaderRuntime.active().definition().programs().stream().filter(p->p.surface()!=null).findFirst().orElse(null);
    }
    public static boolean surfaceReady() {return surfaceReady;}
    public static boolean surfaceCaptureUnavailable() {return captureUnsupported;}
    public static GpuTextureView surfaceNormalDepth() {return surfaceReady?MATERIALS.normal():null;}
    public static GpuTextureView surfaceAlbedo() {return surfaceReady?MATERIALS.albedo():null;}
    public static void captureMaterials(RenderTarget main,GpuBufferSlice fog,ChunkSectionsToRender chunks,GpuSampler sampler,boolean wireframe) {
        if(earlyMaterials)return;
        captureMaterials(main,fog,chunks,sampler,wireframe,false);
    }
    public static void captureEarlyMaterials(RenderTarget main,GpuBufferSlice fog,ChunkSectionsToRender chunks,GpuSampler sampler,boolean wireframe) {
        var program=surfaceProgram();if(program==null || !program.surfaceBeforeTerrain())return;
        captureMaterials(main,fog,chunks,sampler,wireframe,true);
        earlyMaterials=surfaceReady;
    }
    private static void captureMaterials(RenderTarget main,GpuBufferSlice fog,ChunkSectionsToRender chunks,GpuSampler sampler,boolean wireframe,boolean terrainOnly) {
        if(!enabled() || surfaceProgram()==null || wireframe)return;
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        try {
            captureMode=true;captureWater=false;
            if(terrainOnly) {
                SURFACE.putFloat(0,1);SURFACE.position(0).limit(SURFACE_BYTES);
                encoder.writeToBuffer(surface.slice(),SURFACE);
            }
            var atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getTextureView();
            try(var pass=MATERIALS.begin(main,encoder,terrainOnly)) {
                RenderSystem.bindDefaultUniforms(pass);pass.setUniform("Fog",fog);
                chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE,pass,sampler,atlas,wireframe);
                captureWater=true;chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT,pass,sampler,atlas,wireframe);
                // Last in the pass: external renderers may leave Vulkan state dirty.
                if(com.koper.greenmarine.api.external.ExternalRenderers.hasMaterialContributors())
                    com.koper.greenmarine.api.external.ExternalRenderers.drawMaterials(vulkanCommandBuffer(pass),main.width,main.height,
                        com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(main.getDepthTexture().getFormat()));
            }
            captureMode=false;surfaceReady=true;
            RayWorld.renderSurface(MATERIALS.normal(),MATERIALS.albedo(),main.width,main.height);
            surfaceRay=RayWorld.surfaceBindings(encoder);environmentBindings(encoder);bindingEpoch++;
        } catch(RuntimeException|LinkageError error) {surfaceReady=false;fail(error);}
        finally {
            captureMode=captureWater=false;
            if(terrainOnly && surface!=null) {
                SURFACE.putFloat(0,0);SURFACE.position(0).limit(SURFACE_BYTES);
                encoder.writeToBuffer(surface.slice(),SURFACE);
            }
        }
    }
    private static long vulkanCommandBuffer(RenderPass pass) {
        if(!(pass instanceof com.koper.greenmarine.mixin.ExternalPassAccessor front)
            || !(front.greenmarine$backend() instanceof com.koper.greenmarine.mixin.ExternalVulkanPassAccessor vulkan))return 0;
        var command=vulkan.greenmarine$commandBuffer();return command==null?0:command.address();
    }
    public static GpuBuffer surfaceBindings(CommandEncoder encoder) {
        if(surface==null)surface=RenderSystem.getDevice().createBuffer(()->"GreenMarine MarineSurfaceFrame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,SURFACE_BYTES);
        SURFACE.position(0).limit(SURFACE_BYTES);encoder.writeToBuffer(surface.slice(),SURFACE);return surface;
    }
    public static GpuBuffer environmentBindings(CommandEncoder encoder) {
        if(environment==null)environment=RenderSystem.getDevice().createBuffer(()->"GreenMarine environment",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,128);
        ENVIRONMENT.putFloat(44,surfaceReady?1:0);ENVIRONMENT.position(0).limit(128);encoder.writeToBuffer(environment.slice(),ENVIRONMENT);return environment;
    }
    private static void writeSurface(GameRenderer renderer,Matrix4fc projection) {
        var client=Minecraft.getInstance();var camera=renderer.gameRenderState().levelRenderState.cameraRenderState;
            for(int i=0;i<SURFACE_BYTES;i+=4) SURFACE.putFloat(i,0);
            var sky=renderer.gameRenderState().levelRenderState.skyRenderState;
            SURFACE.putFloat(4,1-sky.rainBrightness);
            SURFACE.putFloat(8,RayWorld.surfaceLightsActive()?1:0);
            SURFACE.putFloat(16,wrapped(camera.pos.x)).putFloat(20,wrapped(camera.pos.y)).putFloat(24,wrapped(camera.pos.z));
            var sun=RaySun.sample(sky.sunAngle,sky.rainBrightness,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,parameter("sunStrength",1));
            sun.get(32,SURFACE);
            float daylight=java.lang.Math.clamp(sun.y,0,1);
            SURFACE.putFloat(48,.04f+.31f*daylight).putFloat(52,.06f+.47f*daylight).putFloat(56,.12f+.65f*daylight).putFloat(60,sky.rainBrightness);
            var atlas=client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
            int offset=64;for(String name:List.of("water_still","water_flow","water_overlay")) {
                var sprite=atlas.getSprite(Identifier.fromNamespaceAndPath("minecraft","block/"+name));
                SURFACE.putFloat(offset,sprite.getU0()).putFloat(offset+4,sprite.getV0()).putFloat(offset+8,sprite.getU1()).putFloat(offset+12,sprite.getV1());offset+=16;
            }
            var eye=new Matrix4f(projection).invert().transform(new Vector4f(0,0,1,0));
            var viewEye=new Vector3f(eye.x,eye.y,eye.z).div(eye.w);camera.viewRotationMatrix.transpose(new Matrix4f()).transformDirection(viewEye);
            SURFACE.putFloat(112,viewEye.x).putFloat(116,viewEye.y).putFloat(120,viewEye.z);
    }
    private static void writeEnvironment(GameRenderer renderer) {
        for(int i=0;i<128;i+=4)ENVIRONMENT.putFloat(i,0);
        var client=Minecraft.getInstance();var level=client.level;if(level==null)return;
        var state=renderer.gameRenderState().levelRenderState;var camera=state.cameraRenderState;var sky=state.skyRenderState;
        float rain=level.getRainLevel(state.worldPartialTicks);long time=level.getDefaultClockTime();
        boolean underwater=camera.fogType==net.minecraft.world.level.material.FogType.WATER;
        ENVIRONMENT.putFloat(0,(float)camera.pos.y).putFloat(4,rain).putFloat(8,WEATHER.sample(state.gameTime,rain,client.isPaused())).putFloat(12,underwater?1:0);
        float temperature=level.getBiomeManager().getBiome(camera.blockPos).value().getBaseTemperature();
        ENVIRONMENT.putFloat(16,temperature).putFloat(20,level.getPrecipitationAt(camera.blockPos)==net.minecraft.world.level.biome.Biome.Precipitation.SNOW?1:0)
            .putFloat(24,client.options.hideLightningFlash().get()?0:java.lang.Math.max(sky.endFlashIntensity,((com.koper.greenmarine.mixin.ClientLevelAccessor)level).greenmarine$skyFlashTime()>0?1:0)).putFloat(28,level.getThunderLevel(state.worldPartialTicks));
        if(sky.skyColor!=null)sky.skyColor.get(32,ENVIRONMENT);
        if(camera.fogData!=null && camera.fogData.color!=null)camera.fogData.color.get(48,ENVIRONMENT);
        ENVIRONMENT.putFloat(64,sky.moonPhase==null?0:sky.moonPhase.index()).putFloat(68,sky.moonAngle).putFloat(72,java.lang.Math.floorDiv(time,24000)).putFloat(76,sky.sunAngle);
        float moonX=(float)-java.lang.Math.sin(sky.moonAngle),moonY=(float)java.lang.Math.cos(sky.moonAngle);
        ENVIRONMENT.putFloat(80,moonX).putFloat(84,moonY).putFloat(88,0).putFloat(92,java.lang.Math.max(moonY,0)*sky.rainBrightness);
        boolean overworld=sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD;
        ENVIRONMENT.putFloat(96,overworld?1:0).putFloat(100,level.dimension()==net.minecraft.world.level.Level.NETHER?1:0)
            .putFloat(104,level.dimension()==net.minecraft.world.level.Level.END?1:0).putFloat(108,overworld?1:0);
        ENVIRONMENT.putFloat(112,GreenMarineClient.seconds(state.worldPartialTicks)).putFloat(116,java.lang.Math.floorMod(time,24000)/24000f)
            .putFloat(120,draws).putFloat(124,client.isPaused()?1:0);
    }
    private static void fail(Throwable error) {failed=true;prepared=false;pendingDraw=false;ShaderRuntime.renderFailure(error);GreenMarine.LOGGER.error("World shader programs disabled until pack switch/reload",error);}
    public static void reset() {
        bindingEpoch++;bindingUpdates=bindingSkips=0;
        BACKGROUND.close();DISTANT.close();HEIGHTS.close();HEIGHTS.reset();PBR.close();
        prepared=failed=pendingDraw=logged=captureMode=captureWater=surfaceReady=captureUnsupported=false;WEATHER.reset();if(mainGpu!=null)mainGpu.close();mainGpu=null;mainFrames=0;mainMeasurement=-1;VARIANTS.values().forEach(CompiledRenderPipeline::close);VARIANTS.clear();CAPTURE_VARIANTS.values().forEach(CompiledRenderPipeline::close);CAPTURE_VARIANTS.clear();OWNED.clear();OPAQUE.close();MATERIALS.close();
        if(frame!=null)frame.close();if(surface!=null)surface.close();if(surfaceOpaque!=null)surfaceOpaque.close();if(environment!=null)environment.close();if(materialProfiles!=null)materialProfiles.close();if(materialExtension!=null)materialExtension.close();materialProfiles=materialExtension=null;frame=surface=surfaceOpaque=environment=ray=voxel=surfaceRay=null;voxelMaterials=null;draws=captures=0;COPY_CPU.clear();
    }
}
