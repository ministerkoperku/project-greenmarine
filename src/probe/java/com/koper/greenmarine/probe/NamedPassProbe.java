package com.koper.greenmarine.probe;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** GPU regression fixture for named outputs retained across unrelated same-format passes. */
final class NamedPassProbe {
    private static final Gson GSON = new Gson();
    private static final String OBSERVER = "probe:named_pass_readback";
    private static final String[] PACKS = {"probe:named_pass_plain", "probe:named_pass_history"};
    private static final String[] CHANGES = {"baseline", "resize", "quality", "reset"};
    private static final float[] EXPECTED = {.16f, .32f, .48f, 1};
    // Allows half-float rounding plus scene UNORM/sRGB output quantization and sampling.
    private static final float TOLERANCE = .012f;
    private static final String HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n";
    private static final JsonObject RESULT = new JsonObject();
    private static final JsonArray CASES = new JsonArray();
    private static final AutoCloseable[] registrations = new AutoCloseable[2];
    private static final AtomicBoolean inFlight = new AtomicBoolean();
    private static final AtomicReference<Readback> completed = new AtomicReference<>();
    private static MarineRenderer.Registration observer;
    private static RenderSession observerSession;
    private static ShaderSettings saved;
    private static boolean savedEnabled, finishing, stopped;
    private static int phase, ticks, renderFrames, samples, beforeWidth, beforeHeight;
    private static long started;
    private static Path output;
    private static JsonObject activeCase;
    private record Readback(int phase, JsonObject evidence, float[] pixels, Throwable error) {}

    private NamedPassProbe() {}

    static void tick(Minecraft client, Path folder) {
        output = folder;
        if (stopped) return;
        try {
            if (finishing) { finish(client); return; }
            if (client.player == null || client.level == null) return;
            if (saved == null) {
                started = System.nanoTime();
                saved = ShaderRuntime.settings(); savedEnabled = GreenMarineClient.enabled();
                saved.save(output.resolve("original-settings.json"));
                RESULT.addProperty("fixture", "named-pass-gpu");
                RESULT.add("expectedRgba", GSON.toJsonTree(EXPECTED));
                RESULT.addProperty("tolerance", TOLERANCE);
                RESULT.addProperty("samplesPerCase", 3);
                RESULT.add("capabilities", GSON.toJsonTree(MarineRenderer.capabilities()));
                RESULT.addProperty("passes", "producer -> red -> blue -> compose(ProducerSampler=producer)");
                for (int i = 0; i < registrations.length; i++) registrations[i] = registerPack(i == 1);
                observer = MarineRenderer.register(OBSERVER, MarineRenderer.Stage.AFTER_PACKS, NamedPassProbe::observe);
                if (!savedEnabled) GreenMarineClient.toggleEffect();
                begin(client);
                return;
            }
            if (++ticks > 600 || System.nanoTime() - started > 120_000_000_000L)
                throw new IllegalStateException("Named pass fixture timed out at phase " + phase);
            if (PackRenderer.failed() || ShaderRuntime.status().state() == PackStatus.State.FAILED)
                throw new IllegalStateException("Pack renderer failed: " + ShaderRuntime.status());
            if (MarineRenderer.status().stream().anyMatch(s -> s.id().equals(OBSERVER) && s.failed()))
                throw new IllegalStateException("Named pass GPU observer failed");
            var readback = completed.getAndSet(null);
            if (readback == null) return;
            if (readback.error() != null) throw new IllegalStateException("GPU readback failed", readback.error());
            if (readback.phase() != phase) throw new IllegalStateException("Readback crossed a lifecycle boundary");
            activeCase.getAsJsonArray("gpuSamples").add(readback.evidence());
            for (int i = 0; i < readback.pixels().length; i++) {
                float actual = readback.pixels()[i];
                if (!Float.isFinite(actual) || Math.abs(actual - EXPECTED[i % 4]) > TOLERANCE)
                    throw new IllegalStateException("Named output overwritten at " + activeCase.get("name")
                        + ", pixel " + i / 4 + ", channel " + i % 4 + ": expected " + EXPECTED[i % 4] + ", got " + actual);
            }
            if (++samples < 3) return;
            activeCase.addProperty("passed", true);
            if (++phase < 8) begin(client);
            else { RESULT.addProperty("status", "complete"); finishing = true; finish(client); }
        } catch (Throwable error) { abort(client, folder, error); }
    }

    private static AutoCloseable registerPack(boolean history) {
        var producer = pass("producer", "probe:named_producer", history, Map.of());
        var red = pass("red", "probe:named_red", false, Map.of());
        var blue = pass("blue", "probe:named_blue", false, Map.of());
        var compose = pass("compose", "probe:named_compose", false, Map.of("ProducerSampler", "producer"));
        var pack = new ShaderPack(PACKS[history ? 1 : 0], "Named pass GPU fixture " + history, null,
            List.of(), List.of(producer, red, blue, compose));
        String composeSource = HEADER + "uniform sampler2D ProducerSampler;\n"
            + "layout(location=0) in vec2 texCoord;layout(location=0) out vec4 fragColor;\n"
            + "void main(){fragColor=texture(ProducerSampler,texCoord);}";
        return GreenMarine.PACKS.register(new PackCatalog.LoadedPack(pack, "function update(frame) end", Map.of(
            "probe:named_producer", constant(".16,.32,.48,1"), "probe:named_red", constant("1,0,0,1"),
            "probe:named_blue", constant("0,0,1,1"), "probe:named_compose", composeSource)));
    }

    private static PackPass pass(String name, String fragment, boolean history, Map<String,String> inputs) {
        return new PackPass(name, PackPass.Stage.SCENE, fragment, null, PackPass.Requirement.NONE,
            Quality.LOW, 1, null, PackPass.Format.RGBA16F, history, inputs);
    }

    private static String constant(String color) {
        return HEADER + "layout(location=0)out vec4 fragColor;void main(){fragColor=vec4(" + color + ");}";
    }

    private static void begin(Minecraft client) {
        if (inFlight.get()) throw new IllegalStateException("Lifecycle change with pending GPU readback");
        ticks = renderFrames = samples = 0;
        var main = client.gameRenderer.mainRenderTarget(); beforeWidth = main.width; beforeHeight = main.height;
        activeCase = new JsonObject();
        activeCase.addProperty("name", (phase < 4 ? "plain-" : "history-") + CHANGES[phase % 4]);
        activeCase.addProperty("producerHistory", phase >= 4);
        activeCase.addProperty("passScale", 1); activeCase.addProperty("passFormat", "RGBA16F");
        activeCase.addProperty("beforeWidth", beforeWidth); activeCase.addProperty("beforeHeight", beforeHeight);
        activeCase.add("gpuSamples", new JsonArray()); CASES.add(activeCase);
        switch (phase % 4) {
            case 0 -> ShaderRuntime.apply(new ShaderSettings(PACKS[phase / 4], Quality.HIGH, RayMode.OFF, false));
            case 1 -> client.getWindow().setWindowed(client.getWindow().getScreenWidth() == 800 ? 960 : 800,
                client.getWindow().getScreenWidth() == 800 ? 720 : 600);
            case 2 -> ShaderRuntime.apply(new ShaderSettings(PACKS[phase / 4], Quality.LOW, RayMode.OFF, false));
            case 3 -> ShaderRuntime.reset();
        }
    }

    private static Consumer<FrameContext> observe(RenderSession session) {
        observerSession = session;
        var vertex = Identifier.parse("probe:named_sample_vertex");
        var fragment = Identifier.parse("probe:named_sample_fragment");
        String vs = HEADER + "void main(){vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2-1,0,1);}";
        // Read corners and center before GUI rendering; never replace sceneColor with a test pattern.
        String fs = HEADER + "uniform sampler2D SceneColor;layout(location=0)out vec4 color;"
            + "void main(){int i=int(gl_FragCoord.x);vec2 uv=vec2(.5);"
            + "if(i<4)uv=vec2((i&1)==0?.2:.8,(i&2)==0?.2:.8);color=texture(SceneColor,uv);}";
        var pipeline = RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("SceneColor", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var program = session.compile(new ShaderProgram(pipeline, Map.of(vertex, vs, fragment, fs), Map.of(), Map.of()));
        var target = session.texture("Named pass GPU samples", TextureSpec.fixed(GpuFormat.RGBA32_FLOAT, 5, 1), 5, 1);
        var buffer = session.buffer("Named pass GPU readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
            ByteBuffer.allocateDirect(80));
        var graph = session.graph(RenderGraph.builder().importTexture("sceneColor").importTexture("sampleOutput")
            .pass(new RenderGraph.Pass("sample_named_output", Set.of("sceneColor"), Set.of("sampleOutput"), Set.of(), context ->
                context.draw(context.material(program).texture("SceneColor", "sceneColor", FilterMode.NEAREST),
                    List.of("sampleOutput"), null, null, false))).build());
        return frame -> {
            if (finishing || stopped || saved == null || activeCase == null) return;
            var status = ShaderRuntime.status();
            if (!status.pack().equals(PACKS[phase / 4]) || status.state() != PackStatus.State.LOADED
                || ShaderRuntime.settings().quality() != (phase % 4 < 2 ? Quality.HIGH : Quality.LOW)
                || ShaderRuntime.settings().rays() != RayMode.OFF || ShaderRuntime.rays().effective() != RayMode.OFF
                || PackRenderer.sceneFrames() == 0) return;
            if (phase % 4 == 1 && frame.width() == beforeWidth && frame.height() == beforeHeight) return;
            if (++renderFrames < 12 || completed.get() != null || !inFlight.compareAndSet(false, true)) return;
            int samplePhase = phase;
            var evidence = new JsonObject();
            evidence.addProperty("width", frame.width()); evidence.addProperty("height", frame.height());
            evidence.addProperty("sceneFormat", frame.texture("sceneColor").texture().getFormat().toString());
            evidence.addProperty("quality", ShaderRuntime.settings().quality().name());
            evidence.addProperty("rays", ShaderRuntime.rays().effective().name());
            evidence.addProperty("sceneFrames", PackRenderer.sceneFrames());
            evidence.add("packStatus", GSON.toJsonTree(status));
            try {
                graph.execute(frame, Map.of("sampleOutput", target));
                var encoder = session.device().createCommandEncoder();
                encoder.copyTextureToBuffer(target.texture(), buffer.buffer(), 0, () -> {
                    try (var mapped = buffer.buffer().map(true, false)) {
                        var data = mapped.data().order(ByteOrder.nativeOrder());
                        var pixels = new float[20]; for (int i = 0; i < pixels.length; i++) pixels[i] = data.getFloat(i * 4);
                        evidence.add("rgba", GSON.toJsonTree(pixels));
                        completed.set(new Readback(samplePhase, evidence, pixels, null));
                    } catch (Throwable error) { completed.set(new Readback(samplePhase, evidence, null, error)); }
                    finally { inFlight.set(false); }
                }, 0);
                encoder.submit();
            } catch (Throwable error) {
                inFlight.set(false); completed.set(new Readback(samplePhase, evidence, null, error));
            }
        };
    }

    static void abort(Minecraft client, Path folder, Throwable error) {
        if (stopped) return;
        output = folder;
        if (!finishing) {
            RESULT.addProperty("status", "failed"); RESULT.addProperty("phase", phase);
            RESULT.addProperty("error", error.toString());
            GreenMarine.LOGGER.error("Named pass GPU probe failed at phase " + phase, error);
        }
        finishing = true; finish(client);
    }

    private static void finish(Minecraft client) {
        if (stopped || inFlight.get()) return;
        stopped = true;
        Throwable failure = null;
        // Close each owned lease even if another cleanup fails.
        var cleanup = new java.util.ArrayList<AutoCloseable>();
        if (observer != null) cleanup.add(observer);
        if (observerSession != null) cleanup.add(observerSession);
        for (var registration : registrations) if (registration != null) cleanup.add(registration);
        for (var owned : cleanup) try { owned.close(); }
        catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        try {
            if (saved != null) {
                ShaderRuntime.apply(saved);
                if (!ShaderRuntime.settings().equals(saved)) throw new IllegalStateException("Original shader settings were not restored");
                if (GreenMarineClient.enabled() != savedEnabled) GreenMarineClient.toggleEffect();
            }
            RESULT.addProperty("restored", true);
        } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) {
            RESULT.addProperty("status", "failed"); RESULT.addProperty("cleanupError", failure.toString());
            GreenMarine.LOGGER.error("Named pass GPU probe cleanup failed", failure);
        }
        RESULT.add("cases", CASES);
        try { Files.writeString(output.resolve("result.json"), GSON.toJson(RESULT) + "\n"); }
        catch (Throwable error) { GreenMarine.LOGGER.error("Unable to write named pass GPU evidence", error); }
        try { client.disconnectWithSavingScreen(); } finally { client.stop(); }
    }
}
