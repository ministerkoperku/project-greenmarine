package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.example.GeometryExample;
import com.koper.greenmarine.client.PackRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises only exported API. Owns dev-only registrations and restores them on every exit. */
final class RenderApiProbe {
    private static int stage, ticks;
    private static boolean blocked;
    private static final AtomicBoolean captured = new AtomicBoolean();
    private static MarineRenderer.Registration geometry, depth, invalid, binary;
    private static GeometryExample example;
    private static RenderSession retainedSession;
    private static BufferHandle retainedBuffer;
    private static FrameContext retainedFrame;
    private static GraphExecution.PassContext retainedPass;
    private static com.google.gson.JsonObject result = new com.google.gson.JsonObject();
    private static boolean reloading;
    private static long initialPackFrames;
    private static com.koper.greenmarine.client.ShaderSettings savedSettings;
    static void tick(Minecraft client, Path output) {
        try {
            if(savedSettings==null) {
                savedSettings=com.koper.greenmarine.client.ShaderRuntime.settings();
                com.koper.greenmarine.client.ShaderRuntime.apply(new com.koper.greenmarine.client.ShaderSettings(
                    savedSettings.pack(),savedSettings.quality(),com.koper.greenmarine.api.pack.RayMode.VOXEL,savedSettings.profiling(),savedSettings.parameters()));
            }
            if (++ticks < 60) return;
            if (client.player != null) { client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); }
            switch (stage) {
                case 0 -> { capture(client, output, "baseline.png"); stage = 1; }
                case 1 -> {
                    if (!captured.get()) return;
                    depth = MarineRenderer.register("probe:depth", MarineRenderer.Stage.AFTER_PACKS, session -> {
                        var graph = session.graph(RenderGraph.builder().importTexture("sceneDepth")
                            .importTexture("worldLighting").importTexture("worldNormalDepth").importTexture("worldAlbedo")
                            .pass(new RenderGraph.Pass("depth", Set.of("worldLighting","worldNormalDepth","worldAlbedo"), Set.of("sceneDepth"), Set.of(), ctx -> {
                                retainedPass = ctx;
                                if(ctx.frame().worldRaysReady()) throw new IllegalStateException("Voxel pack unexpectedly claims hardware world data");
                                for(var name:Set.of("worldLighting","worldNormalDepth","worldAlbedo"))
                                    if(ctx.read(name).getWidth(0)!=1 || ctx.read(name).texture().isClosed()) throw new IllegalStateException("Uninitialized public ray placeholder: "+name);
                                result.addProperty("publicRayPlaceholders",true);
                                ctx.encoder().clearDepthTexture(ctx.write("sceneDepth").texture(), blocked ? 1 : 0);
                            })).build());
                        return graph::execute;
                    });
                    geometry = MarineRenderer.register("probe:geometry", MarineRenderer.Stage.AFTER_PACKS, session -> {
                        retainedSession = session;
                        retainedBuffer = session.buffer("probe lifecycle", com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM,
                            java.nio.ByteBuffer.allocateDirect(16));
                        example = new GeometryExample(session, frame -> frame.view().invert().translate(0, 0, -3).rotateY(.5f).rotateX(.2f), true);
                        return frame -> { retainedFrame = frame; example.accept(frame); };
                    });
                    binary = MarineRenderer.register("probe:pack_program", MarineRenderer.Stage.AFTER_PACKS, session -> {
                        var loaded = GreenMarine.PACKS.get("greenmarine:portable").orElseThrow();
                        var vertex = net.minecraft.resources.Identifier.parse("greenmarine:example/quad");
                        var fragment = net.minecraft.resources.Identifier.parse("greenmarine:core/pack_grade");
                        var pipeline = com.mojang.renderpearl.api.pipeline.RenderPipeline.builder()
                            .withLocation(net.minecraft.resources.Identifier.parse("probe:pack_program"))
                            .withVertexShader(vertex).withFragmentShader(fragment)
                            .withBindGroupLayout(com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder()
                                .withUniform("MarineFrame", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
                                .withUniform("ColorSampler", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(Optional.empty(),
                                com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, com.mojang.renderpearl.api.pipeline.ColorTargetState.WRITE_ALL))
                            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES).withCull(false).build();
                        var program = session.compile(ShaderProgram.fromPack(pipeline, loaded,
                            Map.of(vertex, GeometryExample.compositeProgram(com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM).sources().get(vertex))));
                        var uniforms = java.nio.ByteBuffer.allocateDirect(288).order(java.nio.ByteOrder.nativeOrder());
                        uniforms.putFloat(160, 1).putFloat(164, 1);
                        var graph = session.graph(RenderGraph.builder().importTexture("sceneColor")
                            .texture("preview", TextureSpec.fixed(com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, 64, 64))
                            .pass(new RenderGraph.Pass("pack", Set.of("sceneColor"), Set.of("preview"), Set.of(), ctx ->
                                ctx.draw(ctx.material(program).uniform("MarineFrame", uniforms)
                                    .texture("ColorSampler", "sceneColor", com.mojang.renderpearl.api.textures.FilterMode.LINEAR),
                                    List.of("preview"), null, null, true))).build());
                        return graph::execute;
                    });
                    stage = 2; ticks = 0;
                }
                case 2 -> {
                    requireHealthy(); result.add("openTiming", new com.google.gson.Gson().toJsonTree(example.timing()));
                    result.add("openResources", new com.google.gson.Gson().toJsonTree(example.resources()));
                    if (example.resources().textureAllocations() != 3 || example.resources().programCompilations() != 2)
                        throw new IllegalStateException("Graph resources were not reused");
                    var wrongThread = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                    var thread = new Thread(() -> { try { MarineRenderer.reset(); } catch(Throwable e) { wrongThread.set(e); } });
                    thread.start(); thread.join();
                    if (!(wrongThread.get() instanceof IllegalStateException) || retainedBuffer.isClosed())
                        throw new IllegalStateException("Wrong-thread reset affected a live session");
                    result.addProperty("wrongThreadResetRejected", true);
                    expectFailure(retainedFrame::width); expectFailure(() -> retainedPass.read("sceneDepth"));
                    capture(client, output, "geometry.png"); stage = 3;
                }
                case 3 -> { if (!captured.get()) return; blocked = true; ticks = 0; stage = 4; }
                case 4 -> { requireHealthy(); capture(client, output, "occluded.png"); stage = 5; }
                case 5 -> {
                    if (!captured.get()) return; blocked = false;
                    client.getWindow().setWindowed(800, 600); ticks = 0; stage = 6;
                }
                case 6 -> {
                    requireHealthy();
                    result.add("resizeResources", new com.google.gson.Gson().toJsonTree(example.resources()));
                    if (example.resources().textureAllocations() != 6) throw new IllegalStateException("Expected one attachment replacement on resize");
                    capture(client, output, "resized.png"); stage = 7;
                }
                case 7 -> {
                    if (!captured.get()) return;
                    reloading = true;
                    client.reloadResourcePacks().whenComplete((unused, error) -> { reloading = false; if (error != null) abort(client, output, error); });
                    ticks = 0; stage = 8;
                }
                case 8 -> {
                    if (reloading) return; requireHealthy();
                    result.add("reloadTiming", new com.google.gson.Gson().toJsonTree(example.timing()));
                    capture(client, output, "reloaded.png"); stage = 9;
                }
                case 9 -> {
                    if (!captured.get()) return;
                    initialPackFrames = PackRenderer.sceneFrames();
                    invalid = MarineRenderer.register("probe:bad_shader", MarineRenderer.Stage.AFTER_PACKS, session -> {
                        var valid = GeometryExample.geometryProgram(); var sources = new HashMap<>(valid.sources());
                        sources.put(valid.pipeline().getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT), "#version 330\ninvalid shader");
                        session.compile(new ShaderProgram(valid.pipeline(), sources, Map.of(), Map.of()));
                        return frame -> {};
                    });
                    stage = 10; ticks = 0;
                }
                case 10 -> {
                    requireHealthy();
                    if (MarineRenderer.status().stream().noneMatch(s -> s.id().equals("probe:bad_shader") && s.failed()))
                        throw new IllegalStateException("Invalid shader did not fail its contribution");
                    if (PackRenderer.failed() || PackRenderer.sceneFrames() <= initialPackFrames)
                        throw new IllegalStateException("Shader failure interrupted pack rendering");
                    result.addProperty("shaderFailureIsolated", true);
                    invalid.close(); invalid = null;
                    result.add("finalTiming", new com.google.gson.Gson().toJsonTree(example.timing()));
                    geometry.close(); geometry = null; depth.close(); depth = null; binary.close(); binary = null;
                    stage = 11; ticks = 0;
                }
                case 11 -> {
                    if (!retainedBuffer.isClosed()) throw new IllegalStateException("Lease did not release owned buffer");
                    expectFailure(() -> retainedSession.stats());
                    capture(client, output, "released.png"); stage = 12;
                }
                case 12 -> {
                    if (!captured.get()) return;
                    result.addProperty("borrowedSceneSurvived", !client.gameRenderer.mainRenderTarget().getColorTexture().isClosed());
                    result.add("capabilities", new com.google.gson.Gson().toJsonTree(MarineRenderer.capabilities()));
                    result.addProperty("status", "complete"); write(output);
                    restore();
                    client.disconnectWithSavingScreen(); client.stop();
                }
            }
        } catch (Throwable error) { abort(client, output, error); }
    }
    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new IllegalStateException("Expired API object remained usable");
    }
    private static void requireHealthy() {
        if (example == null || MarineRenderer.status().stream().anyMatch(s -> !s.id().equals("probe:bad_shader") && s.failed()))
            throw new IllegalStateException("Geometry contribution failed");
    }
    private static void capture(Minecraft client, Path output, String name) {
        captured.set(false);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try { image.writeToFile(output.resolve(name)); captured.set(true); }
            catch (Exception error) { client.execute(() -> abort(client, output, error)); }
            finally { image.close(); }
        });
    }
    private static void write(Path output) throws java.io.IOException { Files.writeString(output.resolve("result.json"), result + "\n"); }
    static void abort(Minecraft client, Path output, Throwable error) {
        GreenMarine.LOGGER.error("Render API probe failed at stage " + stage, error);
        if (geometry != null) geometry.close(); if (depth != null) depth.close(); if (invalid != null) invalid.close(); if (binary != null) binary.close();
        MarineRenderer.reset(); result.addProperty("status", "failed"); result.addProperty("stage", stage);
        result.addProperty("error", error.toString()); try { write(output); } catch (Exception ignored) {}
        restore();client.stop();
    }
    private static void restore() {if(savedSettings!=null) com.koper.greenmarine.client.ShaderRuntime.apply(savedSettings);}
}
