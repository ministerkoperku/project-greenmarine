package com.koper.greenmarine.api.render;

import com.koper.greenmarine.GreenMarine;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.util.*;
import java.util.function.*;

/** Client-only render contributions, independent of pack selection and effect toggles. */
public final class MarineRenderer {
    public enum Stage { BEFORE_LIGHTING, BEFORE_PACKS, AFTER_PACKS }
    public record Capabilities(String backend, int maxColorAttachments, boolean fragmentSpirv,
                               boolean geometry, boolean hardwareRayTracing) {}
    public record Status(String id, Stage stage, boolean initialized, boolean failed, long frames) {}
    public interface Registration extends AutoCloseable { @Override void close(); }
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final List<Entry> RETIRED = new ArrayList<>();
    private static List<Entry> snapshot = List.of();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private MarineRenderer() {}
    private static final class Entry {
        final String id;
        final Stage stage;
        final Function<RenderSession, Consumer<FrameContext>> factory;
        volatile RenderSession session;
        Consumer<FrameContext> callback;
        volatile boolean failed;
        volatile long frames;
        Entry(String id, Stage stage, Function<RenderSession, Consumer<FrameContext>> factory) {
            this.id = id; this.stage = stage; this.factory = factory;
        }
        void reset() {
            try { if (session != null) session.close(); }
            finally { session = null; callback = null; failed = false; frames = 0; }
        }
    }
    /** Thread-safe registration; GPU work and lease cleanup occur on the render thread. */
    public static synchronized Registration register(String id, Stage stage, Function<RenderSession, Consumer<FrameContext>> factory) {
        if (!id.contains(":")) throw new IllegalArgumentException("Contribution IDs must be namespaced");
        Identifier.parse(id); Objects.requireNonNull(stage); Objects.requireNonNull(factory);
        if (ENTRIES.containsKey(id)) throw new IllegalArgumentException("Duplicate rendering contribution: " + id);
        var entry = new Entry(id, stage, factory); ENTRIES.put(id, entry); snapshot = List.copyOf(ENTRIES.values());
        return () -> retire(entry);
    }
    private static synchronized void retire(Entry entry) {
        if (ENTRIES.remove(entry.id, entry)) { RETIRED.add(entry); snapshot = List.copyOf(ENTRIES.values()); }
    }
    public static synchronized List<Status> status() {
        return snapshot.stream().map(e -> new Status(e.id, e.stage, e.session != null, e.failed, e.frames)).toList();
    }
    public static Capabilities capabilities() {
        RenderSystem.assertOnRenderThread(); var info = RenderSystem.getDevice().getDeviceInfo();
        return new Capabilities(info.backendName(), info.limits().maxColorAttachments(), info.backendName().equalsIgnoreCase("Vulkan"), true, com.koper.greenmarine.client.rt.RayWorld.available());
    }
    public static void captureProjection(Matrix4f projection) { RenderSystem.assertOnRenderThread(); PROJECTION.set(projection); }
    private static synchronized void cleanupRetired() {
        for (var entry : RETIRED) safeReset(entry); RETIRED.clear();
    }
    private static void safeReset(Entry entry) {
        try { entry.reset(); } catch (RuntimeException e) { GreenMarine.LOGGER.error("Unable to close render contribution {}", entry.id, e); }
    }
    public static synchronized void reset() {
        RenderSystem.assertOnRenderThread();
        cleanupRetired(); for (var entry : snapshot) safeReset(entry);
    }
    public static void render(Stage stage, GameRenderer renderer) {
        RenderSystem.assertOnRenderThread();
        cleanupRetired();
        List<Entry> entries; synchronized (MarineRenderer.class) { entries = snapshot; }
        if (entries.isEmpty() || !renderer.gameRenderState().shouldRenderLevel) return;
        var main = renderer.mainRenderTarget();
        if (main.width <= 0 || main.height <= 0 || main.getColorTextureView() == null || main.getDepthTextureView() == null) return;
        var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        var device = RenderSystem.getDevice(); var client = Minecraft.getInstance();
        var encoder=device.createCommandEncoder();
        com.koper.greenmarine.client.rt.RayWorld.bindings(encoder);com.koper.greenmarine.client.rt.RayWorld.surfaceBindings(encoder);
        for (var entry : entries) {
            if (entry.stage != stage || entry.failed) continue;
            var frame = new FrameContext(Map.of("sceneColor", main.getColorTextureView(), "sceneDepth", main.getDepthTextureView(),
                "lightmap", renderer.levelLightmap(),
                "worldLighting",com.koper.greenmarine.client.rt.RayWorld.lighting(),
                "worldNormalDepth",com.koper.greenmarine.client.rt.RayWorld.normals(),
                "worldAlbedo",com.koper.greenmarine.client.rt.RayWorld.albedo(),
                "surfaceNormalDepth",com.koper.greenmarine.client.world.WorldPrograms.surfaceReady()?com.koper.greenmarine.client.world.WorldPrograms.surfaceNormalDepth():com.koper.greenmarine.client.rt.RayWorld.normals(),
                "surfaceAlbedoMaterial",com.koper.greenmarine.client.world.WorldPrograms.surfaceReady()?com.koper.greenmarine.client.world.WorldPrograms.surfaceAlbedo():com.koper.greenmarine.client.rt.RayWorld.albedo(),
                "surfaceLighting",com.koper.greenmarine.client.rt.RayWorld.surfaceLighting(),
                "surfaceReflection",com.koper.greenmarine.client.rt.RayWorld.reflections()), main.width, main.height, PROJECTION, camera.viewRotationMatrix,
                camera.pos.x, camera.pos.y, camera.pos.z,
                com.koper.greenmarine.client.GreenMarineClient.seconds(client.getDeltaTracker().getGameTimeDeltaPartialTick(false)),
                device.getDeviceInfo().isZZeroToOne(),com.koper.greenmarine.client.rt.RayWorld.ready(),
                com.koper.greenmarine.client.world.WorldPrograms.surfaceReady(),com.koper.greenmarine.client.rt.RayWorld.surfaceReady());
            try {
                if (entry.session == null) {
                    entry.session = new RenderSession(device); entry.callback = Objects.requireNonNull(entry.factory.apply(entry.session));
                }
                entry.callback.accept(frame); entry.frames++;
            } catch (RuntimeException | LinkageError error) {
                safeReset(entry); entry.failed = true;
                GreenMarine.LOGGER.error("Render contribution {} disabled until world/resource reset", entry.id, error);
            } finally { frame.expire(); }
        }
    }
}
