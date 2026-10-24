package com.koper.greenmarine.development;

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
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Vulkan pixel evidence for the supported Iris fullscreen subset; no Minecraft world required. */
@EnabledIfSystemProperty(named="greenmarine.development.gpu", matches="true")
class IrisConversionGpuTest {
    private static VulkanBackend backend;
    private static GpuDevice device;
    @TempDir Path temp;

    @BeforeAll static void start() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        NativeLibrariesBootstrap.loadLibraries();
        assertTrue(org.lwjgl.sdl.SDLInit.SDL_Init(org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO));
        RenderSystem.initRenderThread();
        backend = new VulkanBackend(); backend.loadLibrary();
        device = backend.createDevice(new GpuDebugOptions(1, false, true, false));
        RenderSystem.initRenderer(device);
    }
    @AfterAll static void stop() {
        try { if (device != null) device.close(); }
        finally {
            try { if (backend != null) backend.unloadLibrary(); }
            finally { org.lwjgl.sdl.SDLInit.SDL_Quit(); }
        }
    }
    @Test void convertedLegacyFullscreenEffectProducesExpectedVulkanPixels() throws Exception {
        Path original = temp.resolve("iris/shaders"); Files.createDirectories(original);
        Files.writeString(original.resolve("final.fsh"), """
            #version 120
            uniform sampler2D colortex0;
            varying vec2 texcoord;
            void main() {
                gl_FragColor = texture2D(colortex0, texcoord) * vec4(1.0, 0.5, 0.25, 1.0);
            }
            """);
        Path destination = temp.resolve("workspace/converted");
        var report = IrisImporter.convert(original.getParent(), destination);
        assertTrue(report.runnable(), report.diagnostics().toString());
        String fragment = Files.readString(destination.resolve("assets/iris_imported/shaders/core/converted.fsh"));
        var encoder = device.createCommandEncoder();
        try (var image = new NativeImage(2, 2, false);
             var input = device.createTexture("Iris conversion input", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                 GpuFormat.RGBA8_UNORM, 2, 2, 1, 1);
             var inputView = device.createTextureView(input);
             var target = device.createTexture("Iris conversion result", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                 GpuFormat.RGBA8_UNORM, 2, 2, 1, 1);
             var targetView = device.createTextureView(target);
             var readback = device.createBuffer(() -> "Iris conversion pixels", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            image.fillRect(0, 0, 2, 2, 0xFF3366CC);
            encoder.writeToTexture(input, image);
            var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("iris_imported:conversion_gpu_test"))
                .withVertexShader(Identifier.parse("iris_imported:conversion_gpu_test"))
                .withFragmentShader(Identifier.parse("iris_imported:conversion_gpu_test"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
            try (var source = new ConvertedSources(fragment);
                 var compiled = device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
                 var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty())) {
                assertNotNull(compiled, "Converted Iris shader must compile in the host Vulkan pipeline");
                try (var pass = encoder.createRenderPass(() -> "Converted Iris fullscreen effect", targetView, Optional.empty())) {
                    pass.setPipeline(compiled);
                    pass.setUniform("ColorSampler", inputView, sampler);
                    pass.draw(3, 1, 0, 0);
                }
                encoder.copyTextureToBuffer(target, readback, 0, () -> {}, 0);
                finish();
                try (var mapped = readback.map(true, false)) {
                    for (int pixel = 0; pixel < 4; pixel++) {
                        for (int channel = 0; channel < 3; channel++)
                            assertEquals(51, Byte.toUnsignedInt(mapped.data().get(pixel * 4 + channel)), 1,
                                "Converted Iris RGB at pixel " + pixel + ", channel " + channel);
                        assertEquals(255, Byte.toUnsignedInt(mapped.data().get(pixel * 4 + 3)), "Alpha at pixel " + pixel);
                    }
                }
            }
        }
    }
    private static void finish() {
        var encoder = device.createCommandEncoder();
        try (var fence = encoder.createFence()) {
            encoder.submit();
            assertTrue(fence.awaitCompletion(10_000_000_000L), "Converted Iris GPU submission must finish before readback");
        }
    }
    private static final class ConvertedSources implements ShaderSource {
        private final String fragment;
        ConvertedSources(String fragment) { this.fragment = fragment; }
        @Override public String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX ? """
                #version 450
                layout(location=0) out vec2 texCoord;
                void main() {
                    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                    texCoord = uv;
                    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                }
                """ : fragment;
        }
        @Override public CachedIncludeSource getInclude(Identifier id) {
            throw new IllegalStateException("Converted candidate should already have expanded includes: " + id);
        }
        @Override public void close() { /* No include sources are allocated. */ }
    }
}
