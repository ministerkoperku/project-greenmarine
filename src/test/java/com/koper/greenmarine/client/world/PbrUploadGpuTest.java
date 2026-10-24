package com.koper.greenmarine.client.world;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in host Vulkan checks; no Minecraft world, hardware RT or installed instance required. */
@EnabledIfSystemProperty(named="greenmarine.test.gpu", matches="true")
class PbrUploadGpuTest {
    private static VulkanBackend backend;
    private static GpuDevice device;
    @BeforeAll static void start() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();NativeLibrariesBootstrap.loadLibraries();
        assertTrue(org.lwjgl.sdl.SDLInit.SDL_Init(org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO));
        RenderSystem.initRenderThread();backend=new VulkanBackend();backend.loadLibrary();
        device=backend.createDevice(new GpuDebugOptions(1,false,true,false));RenderSystem.initRenderer(device);
    }
    @AfterAll static void stop() {
        if(device!=null)device.close();if(backend!=null)backend.unloadLibrary();org.lwjgl.sdl.SDLInit.SDL_Quit();
    }
    @Test void rgbaAtlasUploadSurvivesFirstSubmission() {
        var encoder=device.createCommandEncoder();
        try(var image=new NativeImage(32,32,false);
            var texture=device.createTexture("LabPBR upload test",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,32,32,1,1);
            var buffer=device.createBuffer(()->"LabPBR readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,32*32*4)) {
            image.fillRect(0,0,32,32,0xFF55E7D1);encoder.writeToTexture(texture,image);image.close();
            encoder.copyTextureToBuffer(texture,buffer,0,()->{},0);finish();
            try(var mapped=buffer.map(true,false)) {
                for(int pixel=0;pixel<32*32;pixel++) {
                    assertEquals(0x55,Byte.toUnsignedInt(mapped.data().get(pixel*4)),"red pixel "+pixel);
                    assertEquals(0xE7,Byte.toUnsignedInt(mapped.data().get(pixel*4+1)),"green pixel "+pixel);
                    assertEquals(0xD1,Byte.toUnsignedInt(mapped.data().get(pixel*4+2)),"blue pixel "+pixel);
                }
            }
        }
    }
    @Test void blackSpecularTexelsAreMaterialsOnlyWhereTheMapExists() throws Exception {
        byte[] data=decode("vec4(p.present?1:0,p.roughness,p.metal?1:0,p.emission)");
        assertEquals(255,Byte.toUnsignedInt(data[0]),"black mapped texel must override the builtin material");
        assertEquals(0,Byte.toUnsignedInt(data[4]),"unmapped texel must keep the builtin material");
        assertEquals(255,Byte.toUnsignedInt(data[8]),"metal map present");
        assertEquals(16,Byte.toUnsignedInt(data[9]),1,"perceptual smoothness becomes squared roughness");
        assertEquals(255,Byte.toUnsignedInt(data[10]),"metal threshold");
        assertEquals(255,Byte.toUnsignedInt(data[11]),"alpha 254 is full emission");
    }
    @Test void fresnelUsesTheMapF0AndAlbedoForMetals() throws Exception {
        byte[] data=decode("vec4(marinePbrFresnel(p,vec3(.2,.4,.6),1.0),1)");
        assertEquals(0,Byte.toUnsignedInt(data[0]),"zero dielectric F0");
        assertEquals(51,Byte.toUnsignedInt(data[8]),1,"metal red F0 from albedo");
        assertEquals(102,Byte.toUnsignedInt(data[9]),1,"metal green F0 from albedo");
        assertEquals(153,Byte.toUnsignedInt(data[10]),1,"metal blue F0 from albedo");
        assertEquals(229,Byte.toUnsignedInt(data[12]),1,"dielectric F0 is linear, not a scaled default");
        data=decode("vec4(marinePbrFresnel(p,vec3(.2,.4,.6),0.0),1)");
        assertEquals(255,Byte.toUnsignedInt(data[0]),"grazing reflectance reaches one even at zero F0");
    }
    @Test void absentNormalMapsKeepFlatNormalsAndFullAmbientLight() throws Exception {
        byte[] data=decode("vec4(marinePbrNormal(gl_FragCoord.xy/vec2(2)).zw,marinePbrNormalsPresent(gl_FragCoord.xy/vec2(2))?1:0,1)");
        assertEquals(255,Byte.toUnsignedInt(data[0]),"flat Z when only specular is mapped");
        assertEquals(255,Byte.toUnsignedInt(data[1]),"unmapped normal must not darken ambient");
        assertEquals(0,Byte.toUnsignedInt(data[2]),"specular-only texel keeps procedural relief");
        assertEquals(0,Byte.toUnsignedInt(data[6]),"unmapped texel keeps procedural relief");
        assertEquals(255,Byte.toUnsignedInt(data[10]),"mapped normal replaces procedural relief");
    }
    @Test void inferredMaterialsRespectExplicitMapsProfilesAndMatteBounds() throws Exception {
        byte[] data=decode("marineInferredMaterial(gl_FragCoord.xy/vec2(2),vec4(.96,0,0,1),vec3(0))*vec4(1,1,1,1.0/255.0)",true);
        assertEquals(2,Byte.toUnsignedInt(data[11]),"inferred metal class");
        assertEquals(102,Byte.toUnsignedInt(data[8]),1,"inferred metal roughness");
        assertEquals(178,Byte.toUnsignedInt(data[9]),1,"inferred metal reflectance");
        assertEquals(1,Byte.toUnsignedInt(data[15]),"explicit specular map leaves original class 1");
        assertEquals(245,Byte.toUnsignedInt(data[12]),1,"explicit map bypasses inference");
        assertEquals(245,Byte.toUnsignedInt(data[4]),1,"absent maps keep builtin defaults");
        data=decode("marineInferredMaterial(gl_FragCoord.xy/vec2(2),vec4(.08,.25,.98,5),vec3(0))",true);
        assertEquals(20,Byte.toUnsignedInt(data[8]),1,"builtin glass roughness preserved");
        assertEquals(255,Byte.toUnsignedInt(data[10]),6,"builtin glass transmission preserved");
        data=decode("vec4(marineInferredMaterial(gl_FragCoord.xy/vec2(2),vec4(.96,0,0,1),vec3(0)).x,marineInferredMaterial(gl_FragCoord.xy/vec2(2),vec4(.96,0,0,1),vec3(1)).x,p.present?1:0,1)",true);
        assertTrue(Byte.toUnsignedInt(data[0])>=191,"matte lower roughness bound");
        assertTrue(Math.abs(Byte.toUnsignedInt(data[0])-Byte.toUnsignedInt(data[1]))<=32,"bounded albedo variation");
        assertEquals(0,Byte.toUnsignedInt(data[10]),"inferred properties are not reported as authored LabPBR");
    }
    @Test void addonPixelsDecodeIndependentlyOfAuthoredMapsAndPreserveEmission() throws Exception {
        byte[] data=decode("marineAddonProperties(gl_FragCoord.xy/vec2(2))*vec4(1,1,1,1.0/255.0)",2);
        assertEquals(204,Byte.toUnsignedInt(data[0]),1,"screen roughness");
        assertEquals(13,Byte.toUnsignedInt(data[1]),1,"screen reflection");
        assertEquals(31,Byte.toUnsignedInt(data[2]),1,"screen emission");
        assertEquals(9,Byte.toUnsignedInt(data[3]),"opaque coated screen class");
        assertEquals(0,Byte.toUnsignedInt(data[7]),"mask hole retains fallback");
        assertEquals(153,Byte.toUnsignedInt(data[9]),1,"metal response scaled to .6");
        assertEquals(2,Byte.toUnsignedInt(data[11]),"metal class");
        assertEquals(0,Byte.toUnsignedInt(data[15]),"authored specular map wins even with addon bit");
    }
    private byte[] decode(String expression) throws Exception { return decode(expression,0); }
    private byte[] decode(String expression,boolean inferredFixture) throws Exception {return decode(expression,inferredFixture?1:0);}
    private byte[] decode(String expression,int fixture) throws Exception {
        boolean inferredFixture=fixture==1;
        var encoder=device.createCommandEncoder();int sampled=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST;
        try(var image=new NativeImage(2,2,true);
            var spec=device.createTexture("LabPBR spec",sampled,GpuFormat.RGBA8_UNORM,2,2,1,1);
            var normal=device.createTexture("LabPBR normal",sampled,GpuFormat.RGBA8_UNORM,2,2,1,1);
            var mask=device.createTexture("LabPBR presence",sampled,GpuFormat.R8_UNORM,2,2,1,1);
            var specView=device.createTextureView(spec);var normalView=device.createTextureView(normal);var maskView=device.createTextureView(mask);
            var target=device.createTexture("LabPBR result",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,2,2,1,1);
            var targetView=device.createTextureView(target);
            var buffer=device.createBuffer(()->"LabPBR decoded",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,16)) {
            image.setPixel(1,1,0xFF80E500);
            image.setPixel(0,1,0xFEC0E740); // smooth metal, porous, fully emissive
            if(inferredFixture) {
                image.setPixel(0,0,0x01E60A00); // matte .90 roughness, low reflectance, class 1
                image.setPixel(0,1,0x0266B200); // .40 roughness, .70 reflectance, class 2
            }
            if(fixture==2) {
                image.setPixel(0,0,0x09CC0D1F); // matte emissive screen
                image.setPixel(0,1,0x023B9900); // metal .23 rough, .6 reflection
            }
            encoder.writeToTexture(spec,image);encoder.writeToTexture(normal,image);
            var bytes=ByteBuffer.allocateDirect(4);bytes.put(fixture==2?new byte[]{8,0,8,9}:inferredFixture?new byte[]{4,0,4,5}:new byte[]{1,0,3,1}).flip();
            encoder.writeToTexture(mask,bytes,0,0,0,0,2,2);
            var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:test_pbr"))
                .withVertexShader(Identifier.parse("greenmarine:test_pbr")).withFragmentShader(Identifier.parse("greenmarine:test_pbr"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("MarinePbrSpecular",UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("MarinePbrNormal",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("MarinePbrPresence",UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
            try(var source=new Sources(expression);var compiled=device.compilePipeline(pipeline,source,Runnable::run).join().finishCompile();
                var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty())) {
                assertNotNull(compiled,"Production LabPBR shader must compile");
                try(var pass=encoder.createRenderPass(()->"PBR decode",targetView,Optional.empty())) {
                    pass.setPipeline(compiled);pass.setUniform("MarinePbrSpecular",specView,sampler);pass.setUniform("MarinePbrNormal",normalView,sampler);
                    pass.setUniform("MarinePbrPresence",maskView,sampler);pass.draw(3,1,0,0);
                }
                encoder.copyTextureToBuffer(target,buffer,0,()->{},0);finish();
                try(var mapped=buffer.map(true,false)) {
                    byte[] data=new byte[16];mapped.data().get(data);return data;
                }
            }
        }
    }
    private static void finish() {
        var encoder=device.createCommandEncoder();
        try(var fence=encoder.createFence()) {encoder.submit();assertTrue(fence.awaitCompletion(10_000_000_000L));}
    }
    private static final class Sources implements ShaderSource {
        private final List<CachedIncludeSource> includes=new ArrayList<>();
        private final String expression;
        Sources(String expression) {this.expression=expression;}
        public String getShader(Identifier id,ShaderType type) {
            return type==ShaderType.VERTEX?"#version 450\nvoid main(){vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2-1,0,1);}":
                "#version 450\n#include <greenmarine:labpbr.glsl>\nlayout(location=0) out vec4 color;void main(){MarinePbr p=marinePbrMaterial(gl_FragCoord.xy/vec2(2));color="+expression+";}";
        }
        public CachedIncludeSource getInclude(Identifier id) {
            try(var input=PbrUploadGpuTest.class.getResourceAsStream("/assets/greenmarine/shaders/include/"+id.getPath())) {
                var source=CachedIncludeSource.create(id,new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8));includes.add(source);return source;
            }catch(Exception error){throw new IllegalStateException(error);}
        }
        public void close() {includes.forEach(CachedIncludeSource::close);}
    }
}
