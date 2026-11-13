package com.koper.greenmarine.client.terrain;

import com.koper.greenmarine.client.HostStorage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.chunk.*;
import java.nio.*;
import java.util.*;

/** Opt-in per-section descriptors; Minecraft retains ownership of every draw and terrain allocation. */
public final class TerrainDrawBindings {
    public static final String VERTICES = "MarineTerrainVertices", DRAWS = "MarineTerrainDraws";
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static volatile boolean enabled;
    private static GpuBuffer emptyVertices, emptyDraw;
    private static long boundDraws, unavailableDraws;
    private TerrainDrawBindings() {}

    /** Converted terrain selects separate host draws so each section can bind its own records. */
    public static void configure(boolean value) { enabled = value; }
    public static boolean enabled() { return enabled; }
    public static long boundDraws() { return boundDraws; }
    public static long unavailableDraws() { return unavailableDraws; }

    public interface Attachment {
        Capture greenmarine$terrainDraws();
        void greenmarine$terrainDraws(Capture value);
    }

    /** Buffer identity matters: separate uber buffers may reuse exactly the same base vertex. */
    public static final class Capture {
        private final Map<Integer, IdentityHashMap<Object, Map<Integer, Object>>> sources = new HashMap<>();
        public void observe(int layer, Object buffer, int baseVertex, Object mesh) {
            sources.computeIfAbsent(layer, key -> new IdentityHashMap<>())
                .computeIfAbsent(buffer, key -> new HashMap<>()).put(baseVertex, mesh);
        }
        public Object mesh(int layer, Object buffer, int baseVertex) {
            var byBuffer = sources.get(layer);
            var byVertex = byBuffer == null ? null : byBuffer.get(buffer);
            return byVertex == null ? null : byVertex.get(baseVertex);
        }
    }

    public static Capture begin() {
        Capture previous = CAPTURE.get();
        CAPTURE.set(new Capture());
        return previous;
    }
    public static Capture current() { return CAPTURE.get(); }
    public static void end(Capture previous) { if (previous == null) CAPTURE.remove(); else CAPTURE.set(previous); }
    public static void observe(Object mesh, ChunkSectionLayer layer, SectionRenderDispatcher.RenderSectionBufferSlice slice) {
        var capture = CAPTURE.get();
        if (capture == null || slice == null) return;
        int stride = layer.pipeline(false).getVertexFormatBinding(0).getVertexSize();
        capture.observe(layer.ordinal(), slice.vertexBuffer(), Math.toIntExact(slice.vertexBufferOffset() / stride), mesh);
    }

    /** One std430 ivec4, indexed by gl_InstanceIndex (zero for separate host draws). */
    public static ByteBuffer metadata(int hostBaseVertex, int sidecarBase, int vertexCount, boolean ready) {
        return ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
            .putInt(hostBaseVertex).putInt(sidecarBase).putInt(vertexCount).putInt(ready ? 1 : 0).flip();
    }

    public static <T> Collection<RenderPass.Draw<T>> decorate(Capture capture, ChunkSectionLayer layer, Collection<RenderPass.Draw<T>> draws) {
        if (!enabled || capture == null) return draws;
        var decorated = new ArrayList<RenderPass.Draw<T>>(draws.size());
        for (var draw : draws) {
            Object mesh = capture.mesh(layer.ordinal(), draw.vertexBuffer(), draw.baseVertex());
            decorated.add(new RenderPass.Draw<>(draw.slot(), draw.vertexBuffer(), draw.indexBuffer(), draw.indexType(),
                draw.firstIndex(), draw.indexCount(), draw.baseVertex(), (data, uploader) -> {
                    if (draw.uniformUploaderConsumer() != null) draw.uniformUploaderConsumer().accept(data, uploader);
                    bind(uploader, mesh, layer, draw.baseVertex());
                }));
        }
        return decorated;
    }

    public static Collection<String> uniforms(Collection<String> original) {
        var names = new ArrayList<>(original);
        if (!names.contains(VERTICES)) names.add(VERTICES);
        if (!names.contains(DRAWS)) names.add(DRAWS);
        return names;
    }

    private static void bind(RenderPass.UniformUploader uploader, Object mesh, ChunkSectionLayer layer, int baseVertex) {
        var device = RenderSystem.getDevice();
        var captured = TerrainSidecars.data(mesh);
        var ready = TerrainSidecars.prepare(mesh, layer, device);
        if (ready == null) {
            if (emptyVertices == null) emptyVertices = storage("GreenMarine unavailable terrain", ByteBuffer.allocateDirect(32));
            if (emptyDraw == null) emptyDraw = storage("GreenMarine unavailable terrain draw", metadata(0, 0, 0, false));
            uploader.setUniform(VERTICES, emptyVertices.slice());
            uploader.setUniform(DRAWS, emptyDraw.slice());
            unavailableDraws++;
            return;
        }
        var source = captured.layers().get(layer);
        if (source.drawMetadata() == null || source.hostBaseVertex() != baseVertex) {
            var replacement = storage("GreenMarine terrain draw", metadata(baseVertex, 0, ready.vertexCount(), true));
            source.drawMetadata(replacement, baseVertex);
        }
        uploader.setUniform(VERTICES, ready.records());
        uploader.setUniform(DRAWS, source.drawMetadata().slice());
        boundDraws++;
    }
    private static GpuBuffer storage(String name, ByteBuffer bytes) {
        return HostStorage.create(() -> RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_COPY_SRC, bytes));
    }
    /** Render thread only, alongside mesh retirement. */
    public static void collectRetired() {
        if (enabled) return;
        if (emptyVertices != null) { emptyVertices.close(); emptyVertices = null; }
        if (emptyDraw != null) { emptyDraw.close(); emptyDraw = null; }
    }
}
