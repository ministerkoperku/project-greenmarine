package com.koper.greenmarine.example;

import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.nio.*;
import java.util.*;
import java.util.function.*;

/** Opt-in consumer: a contributed mesh G-buffer, shaded against the vanilla scene depth. */
public final class GeometryExample implements Consumer<FrameContext> {
    private static final VertexFormat VERTEX = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT).addAttribute("Normal", GpuFormat.RGB32_FLOAT).build();
    private final RenderSession session;
    private final ProgramHandle geometry;
    private final Mesh cube;
    private final ByteBuffer matrices = ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());
    private final ByteBuffer color = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private final Function<FrameContext, Matrix4f> model;
    private GraphExecution graph;
    private ProgramHandle composite;
    private GpuFormat sceneFormat;
    public GeometryExample(RenderSession session, Function<FrameContext, Matrix4f> model, boolean profiling) {
        this.session = session; this.model = Objects.requireNonNull(model);
        geometry = session.compile(geometryProgram()); cube = session.mesh("GreenMarine example cube", VERTEX, 36, cubeVertices());
        color.putFloat(0, .95f).putFloat(4, .04f).putFloat(8, .65f).putFloat(12, 1);
        this.profiling = profiling;
    }
    private final boolean profiling;
    public GraphExecution.Timing timing() { return graph.timing(); }
    public RenderSession.Stats resources() { return session.stats(); }
    @Override public void accept(FrameContext frame) {
        var format = frame.texture("sceneColor").texture().getFormat();
        if (graph == null || sceneFormat != format) {
            if (graph != null) graph.close();
            if (composite != null) composite.close();
            composite = session.compile(compositeProgram(format));
            graph = session.graph(RenderGraph.builder()
                .importTexture("sceneColor").importTexture("sceneDepth")
                .texture("albedo", TextureSpec.scaled(GpuFormat.RGBA8_UNORM, 1))
                .texture("normal", TextureSpec.scaled(GpuFormat.RGBA8_UNORM, 1))
                .texture("meshDepth", TextureSpec.scaled(GpuFormat.D32_FLOAT, 1))
                // Deliberately declared before its producer: graph compilation supplies the order.
                .pass(new RenderGraph.Pass("shade", Set.of("albedo", "normal", "meshDepth", "sceneDepth"), Set.of("sceneColor"), Set.of(), ctx -> {
                    var material = ctx.material(composite).texture("Albedo", "albedo", FilterMode.NEAREST)
                        .texture("NormalMap", "normal", FilterMode.NEAREST).texture("MeshDepth", "meshDepth", FilterMode.NEAREST)
                        .texture("SceneDepth", "sceneDepth", FilterMode.NEAREST);
                    ctx.draw(material, List.of("sceneColor"), null, null, false);
                }))
                .pass(new RenderGraph.Pass("mesh", Set.of(), Set.of("albedo", "normal", "meshDepth"), Set.of(), ctx -> {
                    var f = ctx.frame(); f.projection().get(0, matrices); f.view().get(64, matrices); model.apply(f).get(128, matrices);
                    matrices.position(0).limit(192); color.position(0).limit(16);
                    var material = ctx.material(geometry).uniform("GeometryFrame", matrices).uniform("Surface", color);
                    ctx.draw(material, List.of("albedo", "normal"), "meshDepth", cube, true);
                })).build());
            graph.profiling(profiling); sceneFormat = format;
        }
        graph.execute(frame);
    }
    public static ShaderProgram geometryProgram() {
        var vertex = Identifier.parse("greenmarine:example/mesh"); var fragment = Identifier.parse("greenmarine:example/material");
        var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:example/geometry"))
            .withVertexShader(vertex).withFragmentShader(fragment).withVertexBinding(0, VERTEX)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("GeometryFrame", UniformType.UNIFORM_BUFFER)
                .withUniform("Surface", UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        return new ShaderProgram(pipeline, Map.of(vertex, """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(location=0) in vec3 Position;
            layout(location=1) in vec3 Normal;
            layout(std140) uniform GeometryFrame { mat4 Projection; mat4 View; mat4 Model; };
            layout(location=0) out vec3 worldNormal;
            void main() {
                worldNormal = normalize(mat3(transpose(inverse(Model))) * Normal);
                gl_Position = Projection * View * Model * vec4(Position, 1.0);
            }
            """, fragment, """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(std140) uniform Surface { vec4 BaseColor; };
            layout(location=0) in vec3 worldNormal;
            layout(location=0) out vec4 albedo;
            layout(location=1) out vec4 normal;
            void main() { albedo = BaseColor; normal = vec4(normalize(worldNormal) * 0.5 + 0.5, 1.0); }
            """), Map.of(), Map.of());
    }
    public static ShaderProgram compositeProgram(GpuFormat format) {
        var vertex = Identifier.parse("greenmarine:example/quad"); var fragment = Identifier.parse("greenmarine:example/shade");
        var layout = BindGroupLayout.builder();
        for (String sampler : List.of("Albedo", "NormalMap", "MeshDepth", "SceneDepth")) layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:example/composite"))
            .withVertexShader(vertex).withFragmentShader(fragment).withBindGroupLayout(layout.build())
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), format, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        return new ShaderProgram(pipeline, Map.of(vertex, """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            layout(location=0) out vec2 uv;
            void main() { uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2); gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0); }
            """, fragment, """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform sampler2D Albedo;
            uniform sampler2D NormalMap;
            uniform sampler2D MeshDepth;
            uniform sampler2D SceneDepth;
            layout(location=0) in vec2 uv;
            layout(location=0) out vec4 shaded;
            void main() {
                vec4 surface = texture(Albedo, uv);
                float depth = texture(MeshDepth, uv).r;
                if (surface.a < 0.5 || depth <= texture(SceneDepth, uv).r) discard;
                vec3 normal = normalize(texture(NormalMap, uv).xyz * 2.0 - 1.0);
                float light = 0.25 + 0.75 * max(dot(normal, normalize(vec3(0.4, 0.8, 0.3))), 0.0);
                shaded = vec4(surface.rgb * light, surface.a);
            }
            """), Map.of(), Map.of());
    }
    private static ByteBuffer cubeVertices() {
        var data = ByteBuffer.allocateDirect(36 * VERTEX.getVertexSize()).order(ByteOrder.nativeOrder());
        float[][] corners = {{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
        int[][] faces = {{0,1,2,3},{4,7,6,5},{0,4,5,1},{3,2,6,7},{0,3,7,4},{1,5,6,2}};
        float[][] normals = {{0,0,-1},{0,0,1},{0,-1,0},{0,1,0},{-1,0,0},{1,0,0}};
        for (int face = 0; face < 6; face++) for (int corner : new int[]{0,1,2,0,2,3}) {
            for (float value : corners[faces[face][corner]]) data.putFloat(value * .5f);
            for (float value : normals[face]) data.putFloat(value);
        }
        return data.flip();
    }
}
