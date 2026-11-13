package com.koper.greenmarine.client.world;

import com.google.gson.JsonParser;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.GraphicsStageBuild;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldGraphicsStagesTest {
    private static final String GEOMETRY="""
        #version 450
        #include <test:gain>
        layout(triangles) in;layout(triangle_strip,max_vertices=3) out;
        layout(std140,set=0,binding=7) uniform OnlyGeometry {float gain;};
        void main(){for(int i=0;i<3;i++){gl_Position=gl_in[i].gl_Position+extra(gain);EmitVertex();}EndPrimitive();}
        """;
    private static WorldShaders source(String type) {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"test:stages","passes":[],
             "resources":{"uniforms":{"Gain":[{"name":"gain","type":"%s","source":"viewWidth"}]}},
             "programs":[{"vertex":"test:world","fragment":"test:world","geometry":"test:world","uniforms":{"OnlyGeometry":"Gain"}}]}
            """.formatted(type)).getAsJsonObject();
        var pack=ShaderPack.parse(json);
        var loaded=new PackCatalog.LoadedPack(pack,"function update(f) end",Map.of(
            "test:world","#version 450\nlayout(location=0)out vec4 color;void main(){color=vec4(1);}",
            "vertex|test:world","#version 450\nvoid main(){gl_Position=vec4(1);}",
            "geometry|test:world",GEOMETRY,
            "include|test:gain","vec4 extra(float gain){return vec4(gain*0.125);}"));
        var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("test:pipeline"))
            .withVertexShader(Identifier.parse("test:world")).withFragmentShader(Identifier.parse("test:world"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("OnlyGeometry",UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
        return new WorldShaders(loaded,false,pack.programs().getFirst(),Map.of(),pipeline);
    }
    @Test void worldCompilationCapturesGeometryIncludesAndIntermediateOnlyBindings() {
        try(var source=source("float")) {
            var build=GraphicsStageBuild.begin(source);
            try {
                assertNotNull(build,"World sources must enter the actual intermediate compilation path");
                assertEquals(1,build.intermediate().size());
                assertEquals("OnlyGeometry",build.intermediate().getFirst().descriptors().getFirst().name());
            }finally{GraphicsStageBuild.end();}
        }
    }
    @Test void intermediateUniformAbiMismatchFailsBeforePipelineCreation() {
        try(var source=source("vec4")) {
            var failure=assertThrows(IllegalArgumentException.class,()->GraphicsStageBuild.begin(source));
            assertTrue(failure.getMessage().contains("OnlyGeometry"),failure.getMessage());
        }
        assertNull(GraphicsStageBuild.current(),"A failed build must not leak its chain to the next pipeline");
    }
}
