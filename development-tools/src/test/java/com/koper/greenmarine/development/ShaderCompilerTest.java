package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.koper.greenmarine.development.ShaderCompiler.*;

class ShaderCompilerTest {
    @TempDir Path temp;
    @Test void compilesActualGlslForEachStage() {
        var sources = Map.of(Stage.VERTEX, "void main(){gl_Position=vec4(0.0);}",
            Stage.FRAGMENT, "layout(location=0) out vec4 color; void main(){color=vec4(1.0);}",
            Stage.COMPUTE, "layout(local_size_x=1) in; void main(){}");
        sources.forEach((stage, source) -> {
            var result = compile(Language.GLSL, stage, "#version 450\n" + source, "example.glsl", null);
            assertTrue(result.success(), result.diagnostics());
            assertEquals(0x07230203, ByteBuffer.wrap(result.spirv()).order(ByteOrder.LITTLE_ENDIAN).getInt());
            var words = ByteBuffer.wrap(result.spirv()).order(ByteOrder.LITTLE_ENDIAN);
            int model = -1;
            for (int i=20;i<words.capacity();) { int op=words.getInt(i); if((op&65535)==15) model=words.getInt(i+4); i+=(op>>>16)*4; }
            assertEquals(switch(stage) {case VERTEX -> 0; case FRAGMENT -> 4; case COMPUTE -> 5;}, model);
        });
    }
    @Test void reportsActualSyntaxErrorsWithoutBinary() {
        var result=compile(Language.GLSL, Stage.FRAGMENT, "#version 450\nvoid main(){ invalid syntax; }", "broken.frag", null);
        assertFalse(result.success()); assertTrue(result.diagnostics().contains("broken.frag")); assertEquals(0,result.spirv().length);
    }
    @Test void resolvesNestedMinecraftImportsAndAssignsLocations() {
        var includes=Map.of("demo:color.glsl", "#moj_import <demo:value.glsl>\nvec4 color(){return value();}", "demo:value.glsl", "vec4 value(){return vec4(1.0);}");
        var result=compile(Language.GLSL,Stage.FRAGMENT,"#version 450\r\n#moj_import <demo:color.glsl>\r\nout vec4 outputColor; void main(){outputColor=color();}","imports.frag",null,includes::get);
        assertTrue(result.success(),result.diagnostics());
    }
    @Test void compilesGlsl330PackIncludesWithVulkanLayoutQualifiers() {
        var includes=Map.of("greenmarine:frame.glsl", "#moj_import <greenmarine:value.glsl>\nlayout(binding=0) uniform sampler2D ColorSampler;\nvec4 sampleScene(){return texture(ColorSampler,vec2(value()));}",
            "greenmarine:value.glsl", "float value(){return 0.5;}");
        var result=compile(Language.GLSL,Stage.FRAGMENT,"#version 330\n#extension GL_ARB_separate_shader_objects : require\n#include <greenmarine:frame.glsl>\nlayout(location=0) out vec4 color; void main(){color=sampleScene();}","pack.frag",null,includes::get);
        assertTrue(result.success(),result.diagnostics());
    }
    @Test void definesVulkanForHostVertexShaderBranches() {
        var result=compile(Language.GLSL,Stage.VERTEX,"#version 330\n#if !defined(VULKAN)\n#error Vulkan target must define VULKAN\n#endif\nvoid main(){gl_Position=vec4(float(gl_VertexIndex),0.0,0.0,1.0);}","host.vsh",null);
        assertTrue(result.success(),result.diagnostics());
    }
    @Test void reportsMissingAndRecursiveImports() {
        var source="#version 450\n#moj_import <demo:a.glsl>\nvoid main(){}";
        var missing=compile(Language.GLSL,Stage.FRAGMENT,source,"missing.frag",null);
        assertFalse(missing.success()); assertTrue(missing.diagnostics().contains("demo:a.glsl"));
        var recursive=compile(Language.GLSL,Stage.FRAGMENT,source,"cycle.frag",null,id -> "#moj_import <demo:a.glsl>");
        assertFalse(recursive.success()); assertTrue(recursive.diagnostics().contains("Recursive"));
    }
    @Test void missingSlangCompilerIsExplicitlyUnsupported() {
        for(Path executable : Arrays.asList(null,temp.resolve("no slang compiler"))) {
            var result=compile(Language.SLANG,Stage.FRAGMENT,"void main(){}","test.slang",executable);
            assertFalse(result.success()); assertTrue(result.diagnostics().contains("Slang compiler")); assertEquals(0,result.spirv().length);
        }
    }
    @Test void rejectsEntryPointOnlySpirvForVertexAndCompute() throws Exception {
        Path binary=temp.resolve("incomplete.spv"), compiler=temp.resolve("fake-slangc");
        Files.writeString(compiler,"#!/bin/sh\nfor target do :; done\ncp '"+binary+"' \"$target\"\n");
        assertTrue(compiler.toFile().setExecutable(true));
        for(Stage stage:List.of(Stage.VERTEX,Stage.COMPUTE)) {
            var words=ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            words.putInt(0x07230203).putInt(0x10500).putInt(0).putInt(2).putInt(0);
            words.putInt((5<<16)|15).putInt(stage==Stage.VERTEX?0:5).putInt(1).putInt(0x6e69616d).putInt(0);
            Files.write(binary,words.array());
            var result=compile(Language.SLANG,stage,"ignored","incomplete.slang",compiler);
            assertFalse(result.success(),"Header and entry point alone are not complete SPIR-V");
            assertEquals(0,result.spirv().length);
        }
    }
    @Test void configuredSlangRunsWithSafeTemporaryPathsAndCleansUp() throws Exception {
        Path compiler=temp.resolve("compiler with spaces");
        Files.writeString(compiler,"#!/bin/sh\nprintf '%s\\n' \"$1\" > '"+temp.resolve("input-path")+"'\nprintf 'deliberate diagnostic'\nexit 3\n");
        assertTrue(compiler.toFile().setExecutable(true));
        var result=compile(Language.SLANG,Stage.FRAGMENT,"void main(){}","../../bad name;$(touch injected).slang",compiler);
        assertFalse(result.success()); assertTrue(result.diagnostics().contains("deliberate diagnostic"));
        Path input=Path.of(Files.readString(temp.resolve("input-path")).strip());
        assertFalse(Files.exists(input)); assertFalse(Files.exists(input.getParent()));
    }
}
