package com.koper.greenmarine.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Runs the loaded Solstice compose shader against synthetic temporal inputs on the host GPU. */
final class HistoryCompatibilityProbe {
    private static final String ID = "probe:history_compatibility";
    private static final int SIZE = 3, BYTES = SIZE * SIZE * 16;
    private static final float EPSILON = .0001f;
    private static final Vector4f MATERIAL = new Vector4f(.3f, .4f, .5f, .5f);
    private record SampleCase(String name, boolean history, float currentSceneDepth, float previousSceneDepth,
                              Vector4f previousNormalDepth, Vector4f previousMaterial, boolean acceptsHistory,
                              boolean adjacentEntity) {}
    private static final List<SampleCase> CASES = List.of(
        sample("no_history_control", false, .5f, .5f, new Vector4f(0, 1, 0, .5f), MATERIAL, false),
        sample("compatible_land", true, .5f, .5f, new Vector4f(0, 1, 0, .5f), MATERIAL, true),
        sample("current_entity_occludes_land", true, .7f, .5f, new Vector4f(0, 1, 0, .5f), MATERIAL, false),
        sample("previous_entity_occluded_land", true, .5f, .7f, new Vector4f(0, 1, 0, .5f), MATERIAL, false),
        sample("changed_albedo", true, .5f, .5f, new Vector4f(0, 1, 0, .5f), new Vector4f(.9f, .1f, .2f, .5f), false),
        sample("changed_roughness_same_class", true, .5f, .5f, new Vector4f(0, 1, 0, .5f), new Vector4f(.3f, .4f, .5f, .9f), false),
        sample("changed_material_class", true, .5f, .5f, new Vector4f(0, 1, 0, .5f), new Vector4f(.3f, .4f, .5f, 1.5f), false),
        sample("changed_normal", true, .5f, .5f, new Vector4f(1, 0, 0, .5f), MATERIAL, false),
        sample("changed_surface_depth", true, .5f, .5f, new Vector4f(0, 1, 0, .65f), MATERIAL, false),
        new SampleCase("adjacent_previous_entity", true, .5f, .5f, new Vector4f(0, 1, 0, .5f), MATERIAL, true, true)
    );
    private static final List<String> BLOCKS = List.of("MarineFrame", "MarineSurfaceFrame", "MarineEnvironment",
        "RaySurfaceFrame", "RayFrame", "MarineHistoryFrame");
    private static final List<String> SAMPLERS = List.of("ColorSampler", "SolsticeScene", "DepthSampler",
        "MarineOpaqueColor", "MarineOpaqueDepth", "MarineSurfaceNormalDepth", "MarineSurfaceAlbedo",
        "RaySurfaceLighting", "RayReflection", "RayLighting", "RayNormalDepth", "RayAlbedo",
        "HistorySampler", "HistoryNormalDepth", "HistoryAlbedoMaterial", "HistorySceneDepth");
    private static final JsonObject RESULT = new JsonObject();
    private static final JsonArray ASSERTIONS = new JsonArray();
    private static MarineRenderer.Registration registration;
    private static RenderSession ownedSession;
    private static int nextCase, ticks;
    private static volatile boolean pending;
    private static volatile float[] completed;
    private static volatile Throwable gpuError;
    private static float[] control;
    private static float[] compatibleLandControl;
    private static boolean finished;

    private static SampleCase sample(String name, boolean history, float currentDepth, float previousDepth,
                                     Vector4f normal, Vector4f material, boolean acceptsHistory) {
        return new SampleCase(name, history, currentDepth, previousDepth, new Vector4f(normal), new Vector4f(material), acceptsHistory, false);
    }

    static void tick(Minecraft client, Path output) {
        if (finished) return;
        try {
            if (++ticks > 1200) throw new IllegalStateException("History GPU regression probe timed out");
            if (registration == null) {
                if (client.level == null || client.player == null) return;
                RESULT.addProperty("shader", "greenmarine:core/solstice_compose");
                RESULT.addProperty("target", "3x3 RGBA32_FLOAT, center pixel readback");
                RESULT.addProperty("rayOptions", "z=0,w=1 (lighting settling)");
                RESULT.addProperty("rejectRgbTolerance", EPSILON);
                RESULT.addProperty("acceptMinimumRgbDifference", .05f);
                RESULT.addProperty("historySamplerFilter", "LINEAR (production binding)");
                RESULT.add("assertions", ASSERTIONS);
                registration = MarineRenderer.register(ID, MarineRenderer.Stage.AFTER_PACKS, HistoryCompatibilityProbe::renderer);
            }
            if (gpuError != null) throw new IllegalStateException("History GPU readback failed", gpuError);
            if (MarineRenderer.status().stream().anyMatch(status -> status.id().equals(ID) && status.failed()))
                throw new IllegalStateException("History compose contribution failed; see shader compilation log");
            var values = completed;
            if (values == null) return;
            completed = null;
            record(CASES.get(nextCase), values);
            nextCase++;
            if (nextCase == CASES.size()) finish(client, output);
        } catch (Throwable error) { abort(client, output, error); }
    }

    private static Consumer<FrameContext> renderer(RenderSession session) {
        ownedSession = session;
        String backend = session.device().getDeviceInfo().backendName();
        RESULT.addProperty("backend", backend);
        if (!backend.equalsIgnoreCase("Vulkan")) throw new IllegalStateException("This regression probe requires the Vulkan backend");
        var pack = GreenMarine.PACKS.get("greenmarine:solstice_rt").orElseThrow();
        var vertex = Identifier.parse("probe:history_vertex");
        var fragment = Identifier.parse("greenmarine:core/solstice_compose");
        String source = pack.sources().get(fragment.toString());
        RESULT.addProperty("fragmentSha256", sha256(source));
        var includes = new JsonObject();
        pack.sources().entrySet().stream().filter(entry -> entry.getKey().startsWith("include|"))
            .sorted(Map.Entry.comparingByKey()).forEach(entry -> includes.addProperty(entry.getKey().substring(8), sha256(entry.getValue())));
        RESULT.add("includeSha256", includes);
        var layout = BindGroupLayout.builder();
        for (String name : BLOCKS) layout.withUniform(name, UniformType.UNIFORM_BUFFER);
        for (String name : SAMPLERS) layout.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        var pipeline = RenderPipeline.builder().withLocation(Identifier.parse(ID))
            .withVertexShader(vertex).withFragmentShader(fragment).withBindGroupLayout(layout.build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        String vertexSource = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(location=0) out vec2 texCoord;
            void main() {
                texCoord=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                gl_Position=vec4(texCoord*2.0-1.0,0.0,1.0);
            }
            """;
        var program = session.compile(ShaderProgram.fromPack(pipeline, pack, Map.of(vertex, vertexSource)));
        var textures = new LinkedHashMap<String, TextureHandle>();
        for (String name : SAMPLERS) textures.put(name, session.texture("History probe " + name,
            TextureSpec.fixed(GpuFormat.RGBA32_FLOAT, SIZE, SIZE), SIZE, SIZE));
        var target = session.texture("History compose GPU output", TextureSpec.fixed(GpuFormat.RGBA32_FLOAT, SIZE, SIZE), SIZE, SIZE);
        textures.put("output", target);
        var readback = session.buffer("History compose GPU readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, ByteBuffer.allocateDirect(BYTES));
        var uniforms = new LinkedHashMap<String, ByteBuffer>();
        var frame = bytes(288);
        frame.putFloat(0, SIZE).putFloat(4, SIZE).putFloat(16, 1);
        new Matrix4f().get(32, frame); new Matrix4f().get(96, frame);
        for (int i = 0; i < pack.definition().parameters().size(); i++) {
            var parameter = pack.definition().parameters().get(i);
            float value = switch (parameter.name()) {
                case "bloom", "waveDiagnostic", "surfaceDiagnostic" -> 0;
                default -> parameter.defaultValue();
            };
            frame.putFloat(160 + i * 4, value);
        }
        uniforms.put("MarineFrame", frame);
        uniforms.put("MarineSurfaceFrame", bytes(128));
        var environment = bytes(128); environment.putFloat(44, 1); uniforms.put("MarineEnvironment", environment);
        uniforms.put("RaySurfaceFrame", bytes(16));
        var rays = bytes(80); rays.putFloat(76, 1); uniforms.put("RayFrame", rays);
        var history = bytes(160); new Matrix4f().get(0, history); new Matrix4f().get(64, history);
        history.putFloat(148, 1).putFloat(152, 8).putFloat(156, .008f); uniforms.put("MarineHistoryFrame", history);
        var graphBuilder = RenderGraph.builder();
        for (String name : textures.keySet()) graphBuilder.importTexture(name);
        var graph = session.graph(graphBuilder.pass(new RenderGraph.Pass("actual_solstice_compose", Set.copyOf(SAMPLERS),
            Set.of("output"), Set.of(), context -> {
                var material = context.material(program);
                for (var block : uniforms.entrySet()) material.uniform(block.getKey(), block.getValue());
                for (String name : SAMPLERS) material.texture(name, name,
                    name.equals("HistorySampler") ? FilterMode.LINEAR : FilterMode.NEAREST);
                context.draw(material, List.of("output"), null, null, true);
            })).build());
        return ignored -> {
            if (finished || pending || completed != null || gpuError != null || nextCase >= CASES.size()) return;
            pending = true;
            var sample = CASES.get(nextCase);
            history.putFloat(144, sample.history ? 1 : 0);
            // Both the compatible control and the edge case reproject into the same land texel.
            new Matrix4f().translation(sample.acceptsHistory ? -.2f : 0, 0, 0).get(0, history);
            var encoder = session.device().createCommandEncoder();
            for (String name : SAMPLERS) {
                Vector4f color = switch (name) {
                    case "SolsticeScene", "ColorSampler" -> new Vector4f(.2f, .2f, .2f, 1);
                    case "HistorySampler" -> new Vector4f(.8f, .8f, .8f, 1);
                    case "MarineSurfaceNormalDepth", "RayNormalDepth" -> new Vector4f(0, 1, 0, .5f);
                    case "HistoryNormalDepth" -> sample.previousNormalDepth;
                    case "MarineSurfaceAlbedo", "RayAlbedo" -> MATERIAL;
                    case "HistoryAlbedoMaterial" -> sample.previousMaterial;
                    case "DepthSampler", "MarineOpaqueDepth" -> new Vector4f(sample.currentSceneDepth);
                    case "HistorySceneDepth" -> new Vector4f(sample.previousSceneDepth);
                    default -> new Vector4f();
                };
                encoder.clearColorTexture(textures.get(name).texture(), color);
            }
            if (sample.adjacentEntity) {
                encoder.writeToTexture(textures.get("HistorySampler").texture(), column(new Vector4f(1, 0, 0, 1)),
                    0, 0, 0, 0, 1, SIZE);
                encoder.writeToTexture(textures.get("HistorySceneDepth").texture(), column(new Vector4f(.7f)),
                    0, 0, 0, 0, 1, SIZE);
            }
            encoder.submit();
            var synthetic = new FrameContext(Map.of(), SIZE, SIZE, new Matrix4f(), new Matrix4f(), 0, 0, 0, 0, true);
            try { graph.execute(synthetic, textures); } finally { synthetic.expire(); }
            var copy = session.device().createCommandEncoder();
            copy.copyTextureToBuffer(target.texture(), readback.buffer(), 0, () -> {
                try (var mapped = readback.buffer().map(true, false)) {
                    var data = mapped.data().order(ByteOrder.nativeOrder());
                    int center = ((SIZE / 2) * SIZE + SIZE / 2) * 16;
                    completed = new float[]{data.getFloat(center), data.getFloat(center + 4), data.getFloat(center + 8), data.getFloat(center + 12)};
                } catch (Throwable error) { gpuError = error; }
                finally { pending = false; }
            }, 0);
            copy.submit();
        };
    }

    private static void record(SampleCase sample, float[] values) {
        if (control == null) control = values.clone();
        float difference = 0;
        boolean finite = true;
        for (int i = 0; i < values.length; i++) {
            finite &= Float.isFinite(values[i]);
            if (i < 3) difference = Math.max(difference, Math.abs(values[i] - control[i]));
        }
        boolean controlValid = control[0] > .3f && control[0] < .75f;
        boolean passed = finite && controlValid && Math.abs(values[3] - 1) < EPSILON
            && (sample.acceptsHistory ? difference > .05f : difference < EPSILON);
        if (sample.name.equals("compatible_land")) compatibleLandControl = values.clone();
        float compatibleDifference = 0;
        if (sample.adjacentEntity) {
            for (int i = 0; i < 3; i++) compatibleDifference = Math.max(compatibleDifference,
                Math.abs(values[i] - compatibleLandControl[i]));
            passed &= compatibleDifference < EPSILON;
        }
        var row = new JsonObject();
        row.addProperty("case", sample.name); row.addProperty("passed", passed);
        row.addProperty("expectsHistory", sample.acceptsHistory);
        row.addProperty("maxRgbDifferenceFromControl", Float.isFinite(difference) ? difference : null);
        row.addProperty("historyProjectionTranslateX", sample.acceptsHistory ? -.2f : 0);
        if (sample.adjacentEntity) {
            row.addProperty("expectedReference", "compatible_land");
            row.addProperty("maxRgbDifferenceFromCompatibleLand", Float.isFinite(compatibleDifference) ? compatibleDifference : null);
            row.addProperty("previousEntityColumn", 0);
            row.addProperty("previousEntityDepth", .7f);
            row.add("previousEntityColor", vector(new Vector4f(1, 0, 0, 1)));
        }
        row.addProperty("currentSceneDepth", sample.currentSceneDepth); row.addProperty("previousSceneDepth", sample.previousSceneDepth);
        row.add("previousNormalDepth", vector(sample.previousNormalDepth)); row.add("previousAlbedoMaterial", vector(sample.previousMaterial));
        var rgba = new JsonArray(); for (float value : values) rgba.add(Float.isFinite(value) ? value : null); row.add("rgba", rgba);
        ASSERTIONS.add(row);
    }

    private static void finish(Minecraft client, Path output) throws Exception {
        long failures = 0;
        for (var assertion : ASSERTIONS) if (!assertion.getAsJsonObject().get("passed").getAsBoolean()) failures++;
        RESULT.addProperty("caseCount", ASSERTIONS.size()); RESULT.addProperty("failedCases", failures);
        RESULT.addProperty("status", failures == 0 ? "complete" : "failed");
        cleanup();
        try { write(output); }
        finally {
            try { client.disconnectWithSavingScreen(); }
            finally { client.stop(); }
        }
    }

    static void abort(Minecraft client, Path output, Throwable error) {
        if (finished) return;
        GreenMarine.LOGGER.error("History compatibility GPU probe failed at case " + nextCase, error);
        RESULT.addProperty("status", "failed"); RESULT.addProperty("caseIndex", nextCase); RESULT.addProperty("error", error.toString());
        RESULT.add("assertions", ASSERTIONS);
        cleanup();
        try { write(output); } catch (Exception writeError) { GreenMarine.LOGGER.error("Unable to write history probe result", writeError); }
        client.stop();
    }

    private static void cleanup() {
        finished = true;
        try {
            if (registration != null) { registration.close(); registration = null; }
            if (ownedSession != null) { ownedSession.close(); ownedSession = null; }
        } catch (RuntimeException error) {
            RESULT.addProperty("status", "failed"); RESULT.addProperty("cleanupError", error.toString());
            GreenMarine.LOGGER.error("Unable to close history probe resources", error);
        }
    }
    private static void write(Path output) throws Exception { Files.createDirectories(output); Files.writeString(output.resolve("result.json"), RESULT + "\n"); }
    private static ByteBuffer bytes(int count) { return ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder()); }
    private static ByteBuffer column(Vector4f value) {
        var data = bytes(SIZE * 16);
        for (int y = 0; y < SIZE; y++) data.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(value.w);
        return data.flip();
    }
    private static JsonArray vector(Vector4f value) { var result = new JsonArray(); result.add(value.x); result.add(value.y); result.add(value.z); result.add(value.w); return result; }
    private static String sha256(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("Cannot hash loaded shader source", error); }
    }
}
