package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class SessionExecutionTest {
    @org.junit.jupiter.api.BeforeAll static void hostThread() { RenderTestHost.initialize(); }
    @Test void reusesFixedResourcesAcrossResizeAndNeverDestroysBorrowedImports() {
        var gpu = new TestGpu(); var session = new RenderSession(gpu.device);
        var borrowed = gpu.texture("borrowed", GpuFormat.RGBA8_UNORM, 4, 4);
        var graph = session.graph(RenderGraph.builder().importTexture("sceneColor")
            .texture("fixed", TextureSpec.fixed(GpuFormat.RGBA8_UNORM, 2, 2))
            .texture("scaled", TextureSpec.scaled(GpuFormat.RGBA8_UNORM, .5))
            .pass(new RenderGraph.Pass("write", Set.of(), Set.of("fixed", "scaled"), Set.of(), ctx -> {})).build());
        graph.execute(frame(4, borrowed)); graph.execute(frame(4, borrowed));
        assertEquals(2, session.stats().textureAllocations());
        graph.execute(frame(8, borrowed));
        assertEquals(3, session.stats().textureAllocations());
        session.close(); assertFalse(borrowed.texture().isClosed());
        assertEquals(3, gpu.closedTextures);
    }
    @Test void failedResizeClosesPartialResourcesAndPreservesPreviousAttachments() {
        var gpu = new TestGpu(); var session = new RenderSession(gpu.device);
        var borrowed = gpu.texture("borrowed", GpuFormat.RGBA8_UNORM, 4, 4);
        var graph = session.graph(RenderGraph.builder().texture("a", TextureSpec.scaled(GpuFormat.RGBA8_UNORM, 1))
            .texture("b", TextureSpec.scaled(GpuFormat.RGBA8_UNORM, 1))
            .pass(new RenderGraph.Pass("write", Set.of(), Set.of("a", "b"), Set.of(), ctx -> {})).build());
        graph.execute(frame(4, borrowed)); gpu.failAllocation = 4;
        assertThrows(IllegalStateException.class, () -> graph.execute(frame(8, borrowed)));
        assertEquals(1, gpu.closedTextures);
        graph.execute(frame(4, borrowed)); assertEquals(3, session.stats().textureAllocations());
        session.close(); assertEquals(3, gpu.closedTextures);
    }
    @Test void rejectsUndeclaredAccessAndExpiredPassReferences() {
        var gpu = new TestGpu(); var session = new RenderSession(gpu.device);
        var borrowed = gpu.texture("borrowed", GpuFormat.RGBA8_UNORM, 4, 4);
        var contexts = new ArrayList<GraphExecution.PassContext>();
        var graph = session.graph(RenderGraph.builder().importTexture("sceneColor")
            .pass(new RenderGraph.Pass("write", Set.of(), Set.of("sceneColor"), Set.of(), ctx -> {
                assertThrows(IllegalArgumentException.class, () -> ctx.read("sceneColor")); contexts.add(ctx);
            })).build());
        graph.execute(frame(4, borrowed));
        assertThrows(IllegalStateException.class, () -> contexts.getFirst().write("sceneColor"));
        session.close(); assertFalse(borrowed.texture().isClosed());
    }
    @Test void rejectsFeedbackThroughAliasedImportNames() {
        var gpu = new TestGpu(); var session = new RenderSession(gpu.device);
        var borrowed = gpu.texture("borrowed", GpuFormat.RGBA8_UNORM, 4, 4);
        var graph = session.graph(RenderGraph.builder().importTexture("input").importTexture("output")
            .pass(new RenderGraph.Pass("feedback", Set.of("input"), Set.of("output"), Set.of(), ctx -> {})).build());
        var frame = new FrameContext(Map.of("input", borrowed, "output", borrowed), 4, 4,
            new Matrix4f(), new Matrix4f(), 0, 0, 0, 0, true);
        assertThrows(IllegalArgumentException.class, () -> graph.execute(frame)); session.close();
    }
    private static FrameContext frame(int size, GpuTextureView texture) {
        return new FrameContext(Map.of("sceneColor", texture), size, size, new Matrix4f(), new Matrix4f(), 0, 0, 0, 0, true);
    }
    /** Backend allocation boundary only; graph scheduling/lifetime are real. */
    private static class TestGpu {
        int allocations, closedTextures, failAllocation = -1;
        final GpuDevice device = proxy(GpuDevice.class, (method, args) -> switch (method) {
            case "createTexture" -> {
                if (++allocations == failAllocation) throw new IllegalStateException("Allocation refused");
                yield texture((String)args[0], (GpuFormat)args[2], (int)args[3], (int)args[4]).texture();
            }
            case "createTextureView" -> view((GpuTexture)args[0]);
            case "createCommandEncoder" -> proxy(CommandEncoder.class, (name, arguments) -> { if (name.equals("submit")) return null; throw new UnsupportedOperationException(name); });
            default -> throw new UnsupportedOperationException(method);
        });
        GpuTextureView texture(String label, GpuFormat format, int width, int height) {
            boolean[] closed = {false};
            return view(proxy(GpuTexture.class, (method, args) -> switch(method) {
                case "close" -> { if (!closed[0]) closedTextures++; closed[0] = true; yield null; }
                case "isClosed" -> closed[0]; case "getWidth" -> width; case "getHeight" -> height;
                case "getFormat" -> format; case "getLabel" -> label;
                default -> throw new UnsupportedOperationException(method);
            }));
        }
        GpuTextureView view(GpuTexture texture) {
            return proxy(GpuTextureView.class, (method, args) -> switch(method) {
                case "texture" -> texture; case "close" -> null;
                default -> throw new UnsupportedOperationException(method);
            });
        }
        interface Call { Object invoke(String name, Object[] args); }
        @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, Call call) {
            return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> call.invoke(method.getName(), args));
        }
    }
}
