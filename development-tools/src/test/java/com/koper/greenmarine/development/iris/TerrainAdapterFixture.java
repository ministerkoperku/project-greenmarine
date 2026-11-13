package com.koper.greenmarine.development.iris;

import java.nio.file.*;
import java.util.*;

/** Repeatable input to the native host-draw readback probe; all output uses the real adapter. */
public final class TerrainAdapterFixture {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        for(String name:List.of("attributes","tintao","signed","emission","tintao_default")) {
            String expression=switch(name) {
                case "attributes"->"vec4(mc_Entity.x,mc_midTexCoord,gl_Vertex.x)";
                case "tintao","tintao_default"->"gl_Color";
                case "signed"->"vec4(at_midBlock.xyz,mc_Entity.y)";
                case "emission"->"vec4(at_midBlock.w,at_tangent.xyz)";
                default->throw new AssertionError();
            };
            var linked=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"""
                #version 450
                layout(std140,binding=9) uniform ProbeFrame {int probeBase;int count;};
                in vec4 mc_Entity;in vec2 mc_midTexCoord;in vec4 at_midBlock;in vec4 at_tangent;
                flat out vec4 proof;
                void main(){
                    proof=EXPRESSION;
                    gl_Position=vec4((float(gl_VertexID-probeBase)+.5)/float(count)*2.-1.,0.,0.,1.);
                    gl_PointSize=1.;
                }
                """.replace("EXPRESSION",expression),IrisStage.FRAGMENT,"""
                #version 450
                flat in vec4 proof;layout(location=0)out vec4 color;
                void main(){color=proof;}
                """));
            var adapted=IrisTerrainAdapter.adapt(linked,name.equals("tintao_default")?IrisTerrainAdapter.ColorProfile.IRIS_MULTIPLIED_AO:IrisTerrainAdapter.ColorProfile.IRIS_SEPARATE_AO,IrisTerrainAdapter.ClipDepth.ZERO_TO_ONE_REVERSED).program();
            for(var stage:adapted.stages().entrySet()) {
                var compiled=IrisCompiler.compileExplicit(stage.getValue().text(),stage.getKey(),name);
                if(!compiled.success())throw new IllegalStateException(compiled.diagnostics());
                Files.writeString(output.resolve(name+"."+stage.getKey().extension),stage.getValue().text());
            }
        }
    }
}
