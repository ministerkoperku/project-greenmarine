package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import java.util.*;

/** Reuses owned attachments; borrowed imports remain owned by the caller. */
public final class GraphExecution extends OwnedResource {
    private final RenderSession session;
    private final RenderGraph graph;
    private Map<String, TextureHandle> textures = new LinkedHashMap<>();
    private int width, height;
    private boolean profiling;
    private long frames;
    private double initialSubmissionMs;
    private final com.koper.greenmarine.client.PerformanceSamples cpu = new com.koper.greenmarine.client.PerformanceSamples();
    private com.koper.greenmarine.client.GpuTimer gpu;
    public record Timing(long frames, double initialSubmissionMs,
                         com.koper.greenmarine.client.PerformanceSamples.Snapshot cpu,
                         com.koper.greenmarine.client.PerformanceSamples.Snapshot gpu) {}
    public void profiling(boolean enabled) {
        check(); profiling = enabled;
        if (!enabled && gpu != null) { gpu.close(); gpu = null; }
    }
    public Timing timing() {
        check(); return new Timing(frames, initialSubmissionMs, cpu.snapshot(), gpu == null
            ? new com.koper.greenmarine.client.PerformanceSamples.Snapshot(0, 0, 0, 0, 0) : gpu.snapshot());
    }
    GraphExecution(RenderSession session, RenderGraph graph) { super(session.scope); this.session = session; this.graph = Objects.requireNonNull(graph); }
    private void prepare(FrameContext frame) {
        if (width == frame.width() && height == frame.height()) return;
        var fresh = new LinkedHashMap<String, TextureHandle>();
        var allocated = new ArrayList<TextureHandle>();
        try {
            for (var entry : graph.textures().entrySet()) {
                var previous = textures.get(entry.getKey()); var spec = entry.getValue();
                if (previous != null && previous.texture().getWidth(0) == spec.width(frame.width())
                    && previous.texture().getHeight(0) == spec.height(frame.height())) fresh.put(entry.getKey(), previous);
                else {
                    var created = session.texture("GreenMarine graph " + entry.getKey(), spec, frame.width(), frame.height());
                    allocated.add(created); fresh.put(entry.getKey(), created);
                }
            }
        } catch (RuntimeException | Error e) { allocated.forEach(TextureHandle::close); throw e; }
        for (var previous : textures.entrySet()) if (fresh.get(previous.getKey()) != previous.getValue()) previous.getValue().close();
        textures = fresh; width = frame.width(); height = frame.height();
    }
    public void execute(FrameContext frame) { execute(frame, Map.of()); }
    /** Typed imports belong to this session; frame imports remain borrowed from the host. */
    public void execute(FrameContext frame, Map<String, TextureHandle> imports) {
        check(); frame.checkActive();
        long started = System.nanoTime();
        var views = new HashMap<String, GpuTextureView>();
        for (var name : imports.keySet()) if (!graph.imports().contains(name)) throw new IllegalArgumentException("Undeclared import: " + name);
        for (var name : graph.imports()) {
            var handle = imports.get(name);
            if (handle != null) { handle.requireOwner(session.scope); views.put(name, handle.view()); }
            else views.put(name, frame.texture(name));
        }
        var identities = Collections.newSetFromMap(new IdentityHashMap<com.mojang.renderpearl.api.textures.GpuTexture, Boolean>());
        for (var view : views.values()) if (!identities.add(view.texture())) throw new IllegalArgumentException("Graph imports alias the same physical texture");
        prepare(frame); textures.forEach((name, texture) -> views.put(name, texture.view()));
        var encoder = session.device().createCommandEncoder();
        if (profiling && gpu == null) {
            try { gpu = new com.koper.greenmarine.client.GpuTimer(); }
            catch (RuntimeException unavailable) { com.koper.greenmarine.GreenMarine.LOGGER.warn("Graph GPU timing unavailable: {}", unavailable.getMessage()); }
        }
        int measurement = frames >= 40 && gpu != null ? gpu.begin(encoder) : -1;
        try {
            for (var pass : graph.passes()) {
                var context = new PassContext(session, frame, encoder, views, pass);
                try { pass.execute().accept(context); } finally { context.active = false; }
            }
        } finally { if (gpu != null) gpu.end(encoder, measurement); encoder.submit(); }
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        if (frames == 0) initialSubmissionMs = elapsed;
        frames++; if (profiling && frames > 40) cpu.add(elapsed);
    }
    static double depthClear(com.mojang.renderpearl.api.pipeline.CompareOp compare) {
        return compare == com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN
            || compare == com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN_OR_EQUAL ? 1 : 0;
    }
    static void checkAttachmentNames(List<String> colors, String depth) {
        if (new HashSet<>(colors).size() != colors.size() || (depth != null && colors.contains(depth))) throw new IllegalArgumentException("Duplicate attachments");
    }
    static void checkAttachment(int width, int height, int actualWidth, int actualHeight, GpuFormat format, boolean depth) {
        if (width != actualWidth || height != actualHeight || (depth ? !format.hasDepthAspect() : !format.hasColorAspect()))
            throw new IllegalArgumentException("Attachment dimensions/aspect do not match");
    }
    @Override protected void destroy() { textures.values().forEach(TextureHandle::close); textures.clear(); if (gpu != null) gpu.close(); }

    public static final class PassContext {
        private final RenderSession session;
        private final FrameContext frame;
        private final CommandEncoder encoder;
        private final Map<String, GpuTextureView> textures;
        private final RenderGraph.Pass declaration;
        private boolean active = true;
        private PassContext(RenderSession session, FrameContext frame, CommandEncoder encoder, Map<String, GpuTextureView> textures, RenderGraph.Pass declaration) {
            this.session = session; this.frame = frame; this.encoder = encoder; this.textures = textures; this.declaration = declaration;
        }
        void check() { session.scope.check(); frame.checkActive(); if (!active) throw new IllegalStateException("Graph pass has ended"); }
        public RenderSession session() { check(); return session; }
        public FrameContext frame() { check(); return frame; }
        /** Advanced commands must respect the declared resource access and host lifecycle. Do not submit this encoder. */
        public CommandEncoder encoder() { check(); return encoder; }
        public GpuTextureView read(String resource) { return access(resource, declaration.reads()); }
        public GpuTextureView write(String resource) { return access(resource, declaration.writes()); }
        private GpuTextureView access(String name, Set<String> allowed) {
            check(); if (!allowed.contains(name)) throw new IllegalArgumentException("Undeclared access in " + declaration.id() + ": " + name);
            return textures.get(name);
        }
        public Material material(ProgramHandle program) { check(); return new Material(this, program); }
        public void draw(Material material, List<String> colors, String depth, Mesh mesh, boolean clear) {
            check(); material.check();
            if (material.context != this) throw new IllegalArgumentException("Material belongs to another graph pass");
            var pipeline = material.program.definition().pipeline();
            if (colors.isEmpty() || colors.size() != pipeline.getColorTargetStates().size()) throw new IllegalArgumentException("Color attachment count differs from program");
            var first = write(colors.getFirst()); int width = first.getWidth(0), height = first.getHeight(0);
            checkAttachmentNames(colors, depth);
            var builder = RenderPassDescriptor.builder(() -> "GreenMarine graph " + declaration.id());
            for (int i = 0; i < colors.size(); i++) {
                var view = write(colors.get(i)); var texture = view.texture();
                checkAttachment(width, height, view.getWidth(0), view.getHeight(0), texture.getFormat(), false);
                if (pipeline.getColorTargetStates().get(i).format() != texture.getFormat()) throw new IllegalArgumentException("Color format differs from program");
                builder.withColorAttachment(view, clear ? Optional.of(new Vector4f()) : Optional.empty());
            }
            if ((depth != null) != pipeline.wantsDepthTexture()) throw new IllegalArgumentException("Depth attachment differs from program");
            if (depth != null) {
                var view = write(depth); var texture = view.texture();
                checkAttachment(width, height, view.getWidth(0), view.getHeight(0), texture.getFormat(), true);
                builder.withDepthAttachment(view, clear ? OptionalDouble.of(depthClear(pipeline.getDepthStencilState().depthTest())) : OptionalDouble.empty());
            }
            if (pipeline.pushConstantSize() != 0) throw new IllegalArgumentException("Material draws do not supply push constants");
            if (mesh != null) mesh.check(session, material.program.definition());
            else if (pipeline.getVertexFormatBindings().stream().anyMatch(Objects::nonNull)) throw new IllegalArgumentException("Program requires mesh vertices");
            try (var pass = encoder.createRenderPass(builder.build())) {
                material.apply(pass);
                if (mesh != null) pass.setVertexBuffer(0, mesh.buffer.buffer().slice());
                pass.draw(mesh == null ? 3 : mesh.vertices, 1, 0, 0);
            }
        }
    }
}
