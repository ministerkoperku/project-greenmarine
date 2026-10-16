package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderContractsTest {
    private static RenderPipeline pipeline() {
        return RenderPipeline.builder().withLocation(Identifier.parse("test:program"))
            .withVertexShader(Identifier.parse("test:vertex")).withFragmentShader(Identifier.parse("test:fragment"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Frame", UniformType.UNIFORM_BUFFER)
                .withUniform("Color", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
    @Test void snapshotsShadersAndRequiresGlslFallbackForEveryStage() {
        var sources = new HashMap<Identifier, String>(); sources.put(Identifier.parse("test:vertex"), "vertex");
        sources.put(Identifier.parse("test:fragment"), "fragment");
        var program = new ShaderProgram(pipeline(), sources, Map.of(), Map.of()); sources.clear();
        assertEquals("fragment", program.sources().get(Identifier.parse("test:fragment")));
        assertThrows(IllegalArgumentException.class, () -> new ShaderProgram(pipeline(), Map.of(), Map.of(), Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> program.sources().clear());
        assertThrows(IllegalArgumentException.class, () -> program.checkBinding("Color", UniformType.UNIFORM_BUFFER));
        assertThrows(IllegalArgumentException.class, () -> program.checkBinding("Missing", UniformType.UNIFORM_BUFFER));
        assertThrows(IllegalArgumentException.class, () -> program.checkComplete(Set.of("Frame")));
        program.checkComplete(Set.of("Frame", "Color"));
    }
    @Test void importsPackSnapshotIncludesAndBinariesWithoutChangingPackAbi() {
        var definition = new com.koper.greenmarine.api.pack.ShaderPack("test:pack", "Pack", null,
            List.of(), List.of());
        var loaded = new com.koper.greenmarine.api.pack.PackCatalog.LoadedPack(definition, "",
            Map.of("test:fragment", "fragment", "include|test:common.glsl", "included"));
        var program = ShaderProgram.fromPack(pipeline(), loaded, Map.of(Identifier.parse("test:vertex"), "vertex"));
        assertEquals("included", program.includes().get(Identifier.parse("test:common.glsl")));
        assertEquals("fragment", program.sources().get(Identifier.parse("test:fragment")));
    }
    @Test void ownedResourcesCloseOnceAndRejectWrongOwnerAndThread() throws Exception {
        var scope = new ResourceScope();
        class Resource extends OwnedResource {
            int destroyed;
            Resource() { super(scope); }
            void use() { check(); }
            @Override protected void destroy() { destroyed++; }
        }
        var resource = new Resource();
        assertThrows(IllegalArgumentException.class, () -> resource.requireOwner(new ResourceScope()));
        var wrongThread = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { resource.use(); } catch(Throwable e) { wrongThread.set(e); } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, wrongThread.get());
        scope.close(); resource.close(); assertEquals(1, resource.destroyed);
        assertThrows(IllegalStateException.class, resource::use);
        assertThrows(IllegalStateException.class, () -> new Resource());
    }
    @Test void meshRejectsShortAndMisalignedBuffers() {
        assertThrows(IllegalArgumentException.class, () -> Mesh.validate(12, 3, 35));
        assertThrows(IllegalArgumentException.class, () -> Mesh.validate(12, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Mesh.validate(12, 3, 37));
        Mesh.validate(12, 3, 36);
    }
    @Test void acceptsHostSparseVertexBindingSlots() {
        var definition = com.koper.greenmarine.example.GeometryExample.geometryProgram();
        Mesh.checkLayout(definition.pipeline().getVertexFormatBinding(0), definition);
        assertThrows(IllegalArgumentException.class, () -> Mesh.checkLayout(
            com.mojang.renderpearl.api.vertex.VertexFormat.builder(0).addAttribute("Position", GpuFormat.RGB32_FLOAT).build(), definition));
    }
    @Test void allowsColorOnlyImmutableAttachmentList() {
        GraphExecution.checkAttachmentNames(List.of("color"), null);
        assertThrows(IllegalArgumentException.class, () -> GraphExecution.checkAttachmentNames(List.of("color", "color"), null));
        assertThrows(IllegalArgumentException.class, () -> GraphExecution.checkAttachmentNames(List.of("color"), "color"));
    }
    @Test void depthClearMatchesForwardAndReverseDepthTests() {
        assertEquals(1, GraphExecution.depthClear(CompareOp.LESS_THAN));
        assertEquals(1, GraphExecution.depthClear(CompareOp.LESS_THAN_OR_EQUAL));
        assertEquals(0, GraphExecution.depthClear(CompareOp.GREATER_THAN));
    }
    @Test void attachmentsRejectMismatchedDimensionsAndAspects() {
        assertThrows(IllegalArgumentException.class, () -> GraphExecution.checkAttachment(960, 540, 480, 270, GpuFormat.RGBA8_UNORM, false));
        assertThrows(IllegalArgumentException.class, () -> GraphExecution.checkAttachment(960, 540, 960, 540, GpuFormat.RGBA8_UNORM, true));
        GraphExecution.checkAttachment(960, 540, 960, 540, GpuFormat.D32_FLOAT, true);
    }
}
