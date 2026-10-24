package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Compute/storage end to end on the host device: SSBO chain, graph storage images, pack compute passes. */
final class ComputeProbe {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final JsonObject RESULT = new JsonObject();
    private static final String PACK = "probe:compute_pack";
    private static final int N = 16;
    private static final AtomicReference<Object> api = new AtomicReference<>(), pack = new AtomicReference<>();
    private static MarineRenderer.Registration observer;
    private static AutoCloseable registration;
    private static ShaderSettings saved;
    private static int ticks;
    private static boolean done;
    private static final String HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n";

    static void tick(Minecraft client, Path output) {
        if (done) return;
        try {
            if (client.player == null || client.level == null) return;
            if (saved == null) {
                saved = ShaderRuntime.settings();
                RESULT.add("computeCapabilities", GSON.toJsonTree(MarineRenderer.computeCapabilities()));
                RESULT.add("packCapabilities", GSON.toJsonTree(ShaderRuntime.capabilities()));
                if (!MarineRenderer.computeCapabilities().compute()) throw new IllegalStateException("Compute path did not initialize");
                registration = GreenMarine.PACKS.register(packFixture());
                observer = MarineRenderer.register("probe:compute", MarineRenderer.Stage.AFTER_PACKS, ComputeProbe::session);
                if (!GreenMarineClient.enabled()) GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings(PACK, Quality.HIGH, RayMode.OFF, false));
                return;
            }
            if (++ticks > 1200) throw new IllegalStateException("Compute probe timed out");
            if (PackRenderer.failed()) throw new IllegalStateException("Pack renderer failed: " + ShaderRuntime.status());
            if (MarineRenderer.status().stream().anyMatch(s -> s.id().equals("probe:compute") && s.failed()))
                throw new IllegalStateException("Compute contribution failed (see log)");
            for (var ref : List.of(api, pack)) if (ref.get() instanceof Throwable error) throw new IllegalStateException("GPU check failed", error);
            if (api.get() instanceof JsonObject a && pack.get() instanceof JsonObject p) {
                RESULT.add("api", a); RESULT.add("pack", p);
                RESULT.addProperty("packComputeDispatches", PackCompute.dispatches());
                RESULT.addProperty("sceneFrames", PackRenderer.sceneFrames());
                RESULT.addProperty("status", "complete");
                finish(client, output, null);
            }
        } catch (Throwable error) { finish(client, output, error); }
    }

    private static PackCatalog.LoadedPack packFixture() {
        var resources = new PackResources(Map.of("Lut", new PackResources.Image(PackResources.Format.RGBA8, 8, 8, 0)),
            Map.of("Counter", new PackResources.Buffer(16)));
        var count = new PackPass("count", PackPass.Stage.COMPUTE, "probe:compute/count", null, PackPass.Requirement.NONE,
            Quality.LOW, 1, null, PackPass.Format.SCENE, false, Map.of(), false, "image:Lut");
        var show = new PackPass("show", PackPass.Stage.SCENE, "probe:compute_show", null, PackPass.Requirement.COMPUTE,
            Quality.LOW, 1, null, PackPass.Format.SCENE, false, Map.of("LutTex", "Lut"));
        var definition = new ShaderPack(PACK, "Compute probe", null, List.of(), List.of(count, show), List.of(), List.of(), resources);
        String compute = """
            #version 450
            #include <probe:compute_common.glsl>
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(std140) uniform MarineFrame { vec4 Frame; };
            layout(std430) buffer Counter { uint frames; uint width; uint pad0; uint pad1; };
            layout(rgba8) uniform writeonly image2D Lut;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p == ivec2(0)) { frames += 1u; width = uint(Frame.x); }
                imageStore(Lut, p, lutColor(p));
            }
            """;
        String showSource = HEADER + "uniform sampler2D LutTex;uniform sampler2D ColorSampler;\n"
            + "layout(location=0) in vec2 texCoord;layout(location=0) out vec4 fragColor;\n"
            + "void main(){fragColor=texCoord.x<.25&&texCoord.y<.25?texture(LutTex,texCoord*4.):texture(ColorSampler,texCoord);}";
        return new PackCatalog.LoadedPack(definition, "function update(frame) end", Map.of(
            "compute|probe:compute/count", compute, "include|probe:compute_common.glsl",
            "vec4 lutColor(ivec2 p) { return vec4(vec2(p) / 7.0, 0.5, 1.0); }\n", "probe:compute_show", showSource));
    }

    private static java.util.function.Consumer<FrameContext> session(RenderSession session) {
        // 1) SSBO chain: fill writes packed colors, paint unpacks them into a storage image.
        var fill = session.compute(new ComputeProgram(Identifier.parse("probe:fill"), """
            #version 450
            layout(local_size_x = 4, local_size_y = 4) in;
            layout(std430) buffer Cells { uint cells[]; };
            void main() { uvec2 p = gl_GlobalInvocationID.xy;
                cells[p.y * 16u + p.x] = packUnorm4x8(vec4(float(p.x) / 15.0, float(p.y) / 15.0, 0.25, 1.0)); }
            """, Map.of()));
        var paint = session.compute(new ComputeProgram(Identifier.parse("probe:paint"), """
            #version 450
            layout(local_size_x = 4, local_size_y = 4) in;
            layout(std430) readonly buffer Cells { uint cells[]; };
            layout(rgba8) uniform writeonly image2D Painted;
            void main() { ivec2 p = ivec2(gl_GlobalInvocationID.xy); imageStore(Painted, p, unpackUnorm4x8(cells[p.y * 16 + p.x])); }
            """, Map.of()));
        var cells = session.storageBuffer("probe cells", N * N * 4L, 0);
        var painted = session.storageTexture("probe painted", TextureSpec.fixed(GpuFormat.RGBA8_UNORM, N, N), N, N);
        // 2) Graph: compute writes a graph storage texture, a raster pass samples it into a plain target.
        var invert = session.compute(new ComputeProgram(Identifier.parse("probe:invert"), """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            uniform sampler2D Source;
            layout(rgba8) uniform writeonly image2D Inverted;
            void main() { ivec2 p = ivec2(gl_GlobalInvocationID.xy); vec4 c = texelFetch(Source, p, 0); imageStore(Inverted, p, vec4(1.0 - c.rgb, 1.0)); }
            """, Map.of()));
        var vertex = Identifier.parse("probe:compute_quad"); var fragment = Identifier.parse("probe:compute_sample");
        var pipeline = RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Inverted", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var sample = session.compile(new ShaderProgram(pipeline, Map.of(
            vertex, HEADER + "void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}",
            fragment, HEADER + "uniform sampler2D Inverted;layout(location=0)out vec4 color;void main(){color=texelFetch(Inverted,ivec2(gl_FragCoord.xy),0);}"),
            Map.of(), Map.of()));
        var sampled = session.texture("probe sampled", TextureSpec.fixed(GpuFormat.RGBA8_UNORM, N, N), N, N);
        var graph = session.graph(RenderGraph.builder().importTexture("painted").importTexture("sampled")
            .storageTexture("inverted", TextureSpec.fixed(GpuFormat.RGBA8_UNORM, N, N))
            .pass(new RenderGraph.Pass("invert", Set.of("painted"), Set.of("inverted"), Set.of(), ctx ->
                ctx.dispatch(invert.bind().texture("Source", ctx.read("painted"), FilterMode.NEAREST).image("Inverted", ctx.storage("inverted")), 2, 2, 1)))
            .pass(new RenderGraph.Pass("sample", Set.of("inverted"), Set.of("sampled"), Set.of(), ctx ->
                ctx.draw(ctx.material(sample).texture("Inverted", "inverted", FilterMode.NEAREST), List.of("sampled"), null, null, true)))
            .build());
        var readPainted = session.buffer("probe readback painted", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, ByteBuffer.allocateDirect(N * N * 4));
        var readSampled = session.buffer("probe readback sampled", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, ByteBuffer.allocateDirect(N * N * 4));
        var readCells = session.buffer("probe readback cells", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, ByteBuffer.allocateDirect(N * N * 4));
        var readLut = session.buffer("probe readback lut", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, ByteBuffer.allocateDirect(8 * 8 * 4));
        var readCounter = session.buffer("probe readback counter", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, ByteBuffer.allocateDirect(16));
        var frames = new int[1]; var pending = new AtomicInteger();
        return frame -> {
            frames[0]++;
            if (frames[0] == 20 && api.get() == null) {
                var encoder = session.device().createCommandEncoder();
                long t0 = System.nanoTime();
                session.dispatch(encoder, fill.bind().buffer("Cells", cells), 4, 4, 1);
                session.dispatch(encoder, paint.bind().buffer("Cells", cells).image("Painted", painted), 4, 4, 1);
                graph.execute(frame, Map.of("painted", painted, "sampled", sampled));
                double cpuMs = (System.nanoTime() - t0) / 1e6;
                var evidence = new JsonObject();
                evidence.addProperty("recordCpuMs", cpuMs);
                evidence.add("bindings", GSON.toJsonTree(List.of(fill.bindings(), paint.bindings(), invert.bindings())));
                pending.set(3);
                Runnable check = () -> { if (pending.decrementAndGet() == 0) verifyApi(evidence, readCells, readPainted, readSampled); };
                encoder.copyToBuffer(cells.buffer().slice(), readCells.buffer().slice());
                encoder.copyTextureToBuffer(painted.texture(), readPainted.buffer(), 0, check, 0);
                encoder.copyTextureToBuffer(sampled.texture(), readSampled.buffer(), 0, check, 0);
                encoder.submit();
                check.run();
            }
            if (frames[0] == 60 && pack.get() == null) {
                var lut = PackCompute.image("Lut"); var counter = PackCompute.buffer("Counter");
                if (lut == null || counter == null) { pack.set(new IllegalStateException("Pack resources missing; status " + ShaderRuntime.status())); return; }
                var encoder = session.device().createCommandEncoder(); var left = new AtomicInteger(2); int sceneWidth = frame.width();
                Runnable check = () -> { if (left.decrementAndGet() == 0) verifyPack(readLut, readCounter, sceneWidth); };
                encoder.copyToBuffer(counter.slice(), readCounter.buffer().slice());
                encoder.copyTextureToBuffer(lut.texture(), readLut.buffer(), 0, check, 0);
                encoder.submit();
                check.run();
            }
        };
    }

    private static int rgba(float r, float g, float b, float a) {
        return Math.round(r * 255) | Math.round(g * 255) << 8 | Math.round(b * 255) << 16 | Math.round(a * 255) << 24;
    }
    private static void verifyApi(JsonObject evidence, BufferHandle cells, BufferHandle painted, BufferHandle sampled) {
        try (var c = cells.buffer().map(true, false); var p = painted.buffer().map(true, false); var s = sampled.buffer().map(true, false)) {
            var cd = c.data().order(ByteOrder.LITTLE_ENDIAN); var pd = p.data().order(ByteOrder.LITTLE_ENDIAN); var sd = s.data().order(ByteOrder.LITTLE_ENDIAN);
            int mismatches = 0; var first = new JsonArray();
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                int i = (y * N + x) * 4;
                int expected = rgba(x / 15f, y / 15f, .25f, 1);
                int inverted = (255 - (expected & 0xFF)) | (255 - (expected >> 8 & 0xFF)) << 8 | (255 - (expected >> 16 & 0xFF)) << 16 | 0xFF << 24;
                int[] actual = {cd.getInt(i), pd.getInt(i), sd.getInt(i)}; int[] wanted = {expected, expected, inverted};
                for (int k = 0; k < 3; k++) if (actual[k] != wanted[k]) {
                    if (mismatches++ < 6) first.add(String.format("%s(%d,%d) expected %08x got %08x", new String[]{"ssbo", "image", "sampled"}[k], x, y, wanted[k], actual[k]));
                }
            }
            evidence.addProperty("pixels", N * N); evidence.addProperty("mismatches", mismatches); evidence.add("firstMismatches", first);
            if (mismatches != 0) api.set(new IllegalStateException("API compute mismatch: " + first));
            else api.set(evidence);
        } catch (Throwable error) { api.set(error); }
    }
    private static void verifyPack(BufferHandle lut, BufferHandle counter, int sceneWidth) {
        try (var l = lut.buffer().map(true, false); var c = counter.buffer().map(true, false)) {
            var ld = l.data().order(ByteOrder.LITTLE_ENDIAN); var cd = c.data().order(ByteOrder.LITTLE_ENDIAN);
            var evidence = new JsonObject(); int mismatches = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                if (ld.getInt((y * 8 + x) * 4) != rgba(x / 7f, y / 7f, .5f, 1)) mismatches++;
            int frames = cd.getInt(0), width = cd.getInt(4);
            evidence.addProperty("lutMismatches", mismatches); evidence.addProperty("counterFrames", frames);
            evidence.addProperty("counterWidth", width); evidence.addProperty("sceneWidth", sceneWidth);
            if (mismatches != 0 || frames < 2 || width != sceneWidth) pack.set(new IllegalStateException("Pack compute mismatch: " + evidence));
            else pack.set(evidence);
        } catch (Throwable error) { pack.set(error); }
    }

    private static void finish(Minecraft client, Path output, Throwable error) {
        done = true;
        if (error != null) {
            GreenMarine.LOGGER.error("Compute probe failed", error);
            RESULT.addProperty("status", "failed"); RESULT.addProperty("error", error + (error.getCause() == null ? "" : " / " + error.getCause()));
        }
        try { Files.writeString(output.resolve("compute-result.json"), GSON.toJson(RESULT) + "\n"); } catch (Exception ignored) {}
        try { if (observer != null) observer.close(); if (registration != null) registration.close(); } catch (Exception ignored) {}
        if (saved != null) ShaderRuntime.apply(saved);
        client.stop();
    }
}
