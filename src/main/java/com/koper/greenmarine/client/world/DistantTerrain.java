package com.koper.greenmarine.client.world;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.resources.Identifier;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Optional Distant Horizons LODs under Solstice: DH applies its LOD colour without host depth, so the
 * world programs never shade it. This pass relights those pixels from DH's own depth with the Solstice
 * sun, sky and height fog, while DH's fog and LOD shading are overridden through its API (not saved).
 * Everything is reflective; without DH nothing here loads a DH class.
 */
final class DistantTerrain implements AutoCloseable {
    static final boolean LOADED=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static final String FRAGMENT="greenmarine:core/solstice_distant";
    private static Bridge bridge;
    private static boolean bridgeFailed;
    private static final DistantRenderState STATE=new DistantRenderState();
    private final ByteBuffer projection=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private CompiledRenderPipeline compiled;
    private PackCatalog.LoadedPack owner;
    private GpuFormat format;
    private GpuBuffer frame;
    private GpuTexture color;
    private GpuTextureView colorView;

    static boolean shades(PackCatalog.LoadedPack pack) {
        return pack.definition().programs().stream().anyMatch(p->"greenmarine:core/solstice_terrain".equals(p.fragment()));
    }

    /** DH's own fog and LOD shading would double ours; hand them back as soon as we stop shading. */
    static boolean override(boolean requested) {
        if(!LOADED || bridge()==null)return false;
        try {return STATE.update(requested,requested && bridge.renderingEnabled(),bridge::override);}
        catch(ReflectiveOperationException|RuntimeException error) {disable(error);return false;}
    }

    void render(RenderTarget main,CommandEncoder encoder,PackCatalog.LoadedPack pack,GpuBuffer marineFrame,GpuBuffer surface,
                GpuBuffer environment,GpuTextureView lightmap,GpuTextureView heightField) {
        if(!LOADED)return;
        if(bridge()==null) {report("Distant Horizons bridge not ready");return;}
        GpuTextureView depth;float[] clip;
        try {
            if(!bridge.renderingEnabled()) {override(false);return;}
            depth=bridge.depth();clip=bridge.clip();
        }
        catch(ReflectiveOperationException|RuntimeException error) {disable(error);return;}
        if(depth==null || depth.getWidth(0)!=main.width || depth.getHeight(0)!=main.height) {
            report("Distant Horizons depth unavailable or mismatched: "+(depth==null?"null":depth.getWidth(0)+"x"+depth.getHeight(0))+" vs "+main.width+"x"+main.height);
            return;
        }
        report("Distant Horizons LOD lighting active: depth "+depth.getWidth(0)+"x"+depth.getHeight(0)+" "+depth.texture().getFormat());
        var targetFormat=main.getColorTexture().getFormat();
        if(compiled==null || owner!=pack || targetFormat!=format) {
            closePipeline();
            try(var sources=new WorldShaders(pack)) {
                compiled=RenderSystem.getDevice().compilePipeline(pipeline(targetFormat),sources,Runnable::run).join().finishCompile();
                if(compiled==null)throw new IllegalArgumentException("Unable to compile "+FRAGMENT);
                owner=pack;format=targetFormat;
            }
        }
        if(color==null || color.getWidth(0)!=main.width || color.getHeight(0)!=main.height || color.getFormat()!=targetFormat) {
            closeColor();
            color=RenderSystem.getDevice().createTexture("GreenMarine distant terrain colour",
                GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,targetFormat,main.width,main.height,1,1);
            colorView=RenderSystem.getDevice().createTextureView(color);
        }
        if(frame==null)frame=RenderSystem.getDevice().createBuffer(()->"GreenMarine DistantFrame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
        projection.putFloat(0,clip[0]).putFloat(4,clip[1]).putFloat(8,clip[2]).putFloat(12,clip[3]);projection.position(0).limit(16);
        encoder.writeToBuffer(frame.slice(),projection);
        encoder.copyTextureToTexture(main.getColorTexture(),color,0,0,0,0,0,main.width,main.height);
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try(var pass=encoder.createRenderPass(()->"GreenMarine distant lighting",main.getColorTextureView(),Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("MarineFrame",marineFrame);pass.setUniform("MarineSurfaceFrame",surface);
            pass.setUniform("MarineEnvironment",environment);pass.setUniform("DistantFrame",frame);
            pass.setUniform("ColorSampler",colorView,nearest);pass.setUniform("SceneSampler",colorView,nearest);pass.setUniform("DepthSampler",main.getDepthTextureView(),nearest);
            pass.setUniform("DistantDepth",depth,nearest);pass.setUniform("LightmapSampler",lightmap,nearest);
            pass.setUniform("MarineHeightField",heightField,nearest);
            pass.draw(3,1,0,0);
        }
    }

    private static RenderPipeline pipeline(GpuFormat format) {
        return RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:world_distant"))
            .withVertexShader(Identifier.parse("greenmarine:core/solstice_background")).withFragmentShader(Identifier.parse(FRAGMENT))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("MarineFrame",UniformType.UNIFORM_BUFFER)
                .withUniform("MarineSurfaceFrame",UniformType.UNIFORM_BUFFER).withUniform("MarineEnvironment",UniformType.UNIFORM_BUFFER)
                .withUniform("DistantFrame",UniformType.UNIFORM_BUFFER)
                .withUniform("ColorSampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("SceneSampler",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DepthSampler",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DistantDepth",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("LightmapSampler",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("MarineHeightField",UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),format,ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }

    private static Bridge bridge() {
        if(bridge==null && !bridgeFailed) {
            try {bridge=new Bridge();}
            catch(IllegalStateException early) {return null;}
            catch(ReflectiveOperationException|LinkageError error) {bridgeFailed=true;GreenMarine.LOGGER.warn("Distant Horizons bridge unavailable: {}",error.toString());}
        }
        return bridge;
    }
    private static String reported;
    /** Logs each distinct state once, so a silent no-op is visible in the log. */
    private static void report(String state) {
        if(state.equals(reported))return;reported=state;GreenMarine.LOGGER.info(state);
    }
    private static void disable(Throwable error) {
        if(bridge!=null) {
            try {STATE.update(false,false,bridge::override);}
            catch(ReflectiveOperationException|RuntimeException cleanup) {error.addSuppressed(cleanup);}
        }
        bridgeFailed=true;bridge=null;
        GreenMarine.LOGGER.warn("Distant Horizons LOD lighting disabled: {}",error.toString());
    }

    private void closePipeline() {if(compiled!=null)compiled.close();compiled=null;owner=null;format=null;}
    private void closeColor() {if(colorView!=null)colorView.close();if(color!=null)color.close();colorView=null;color=null;}
    @Override public void close() {
        override(false);
        closePipeline();closeColor();if(frame!=null)frame.close();frame=null;
    }

    /** DH API 3.x by name: render proxy textures, RenderUtil clip planes (as RenderUtil.setDhProjectionMatrix), config overrides. */
    private static final class Bridge {
        private final Object renderProxy,enableFog,lodShading,shadingDisabled,renderingEnabled;
        private final Method depthWrapper,depthDirection,depthRange,near,heightNear,far,setValue,clearValue,getValue;
        private final java.lang.reflect.Field payload;
        private Method textureView;
        Bridge() throws ReflectiveOperationException {
            var delayed=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            renderProxy=delayed.getField("renderProxy").get(null);
            Object configs=delayed.getField("configs").get(null);
            if(renderProxy==null || configs==null)throw new IllegalStateException("Distant Horizons API not initialized yet");
            var proxy=Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy");
            depthWrapper=proxy.getMethod("getDhDepthTextureBlazeWrapper");depthDirection=proxy.getMethod("getDepthDirection");
            depthRange=proxy.getMethod("getDepthRange");
            payload=Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult").getField("payload");
            var util=Class.forName("com.seibel.distanthorizons.core.util.RenderUtil");
            near=util.getMethod("getNearClipPlaneInBlocks");heightNear=util.getMethod("getHeightBasedNearClipOverrideBlockDistance");
            far=util.getMethod("getFarClipPlaneDistanceInBlocks");
            Object graphics=Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig").getMethod("graphics").invoke(configs);
            var graphicsType=Class.forName("com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig");
            renderingEnabled=graphicsType.getMethod("renderingEnabled").invoke(graphics);
            Object fog=graphicsType.getMethod("fog").invoke(graphics);
            enableFog=Class.forName("com.seibel.distanthorizons.api.interfaces.config.client.IDhApiFogConfig").getMethod("enableDhFog").invoke(fog);
            lodShading=graphicsType.getMethod("lodShading").invoke(graphics);
            @SuppressWarnings({"unchecked","rawtypes"})
            Object disabled=Enum.valueOf((Class)Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiLodShading"),"DISABLED");
            shadingDisabled=disabled;
            var value=Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
            setValue=value.getMethod("setValue",Object.class);clearValue=value.getMethod("clearValue");
            getValue=value.getMethod("getValue");
        }
        boolean renderingEnabled() throws ReflectiveOperationException {
            return Boolean.TRUE.equals(getValue.invoke(renderingEnabled));
        }
        GpuTextureView depth() throws ReflectiveOperationException {
            Object wrapper=payload.get(depthWrapper.invoke(renderProxy));
            if(wrapper==null)return null;
            if(textureView==null)textureView=wrapper.getClass().getMethod("getTextureView");
            return (GpuTextureView)textureView.invoke(wrapper);
        }
        /** m22, m23, depth range, direction: view distance = m23/(ndc+m22). */
        float[] clip() throws ReflectiveOperationException {
            float n=(float)near.invoke(null),f=(float)far.invoke(null);
            if((float)heightNear.invoke(null)==-1f)n=Math.min(n,7.5f);
            return DistantProjection.clip(n,f,String.valueOf(depthRange.invoke(renderProxy)),String.valueOf(depthDirection.invoke(renderProxy)));
        }
        void override(boolean active) throws ReflectiveOperationException {
            if(active) {setValue.invoke(enableFog,Boolean.FALSE);setValue.invoke(lodShading,shadingDisabled);}
            else {
                try {clearValue.invoke(enableFog);}
                finally {clearValue.invoke(lodShading);}
            }
        }
    }
}
