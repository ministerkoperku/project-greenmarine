package com.koper.greenmarine.development.iris;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisPipelinePlanTest {
    private static IrisSources sources(String... pathsAndText) throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < pathsAndText.length; i += 2)
            files.put("shaders/" + pathsAndText[i], pathsAndText[i + 1].getBytes(StandardCharsets.UTF_8));
        return IrisSources.of(files);
    }
    private static final String VERTEX = "#version 430\nvoid main(){gl_Position=vec4(0.0);}";
    private static final String FRAGMENT = "#version 430\nvoid main(){gl_FragColor=vec4(1.0);}";
    private static final String COMPUTE = "#version 430\nlayout(local_size_x=1) in; void main(){}";

    // Catches accidental root-stage merging and failure to use root when no dimension override exists.
    @Test void dimensionOverridesAreCompleteProgramSetsAndRootRemainsFallback() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("final.vsh", VERTEX, "final.fsh", FRAGMENT,
            "world0/final.fsh", FRAGMENT));
        assertEquals("", plan.world("world-1").folder());
        assertEquals(2, plan.world("world-1").programs().getFirst().stages().size());
        var overridden = plan.world("world0").programs().getFirst();
        assertFalse(overridden.stages().containsKey(IrisStage.VERTEX));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("MISSING_STAGE_PAIR")));
    }

    @Test void disabledGbuffersResolveTheirWholeProgramFallback() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("gbuffers_basic.vsh", VERTEX, "gbuffers_basic.fsh", FRAGMENT,
            "gbuffers_textured.vsh", VERTEX, "gbuffers_textured.fsh", FRAGMENT,
            "shaders.properties", "program.gbuffers_textured.enabled=false\n"));
        assertEquals("gbuffers_basic", plan.world("").resolve("gbuffers_terrain").orElseThrow().name());
        assertEquals("gbuffers_basic", plan.world("").resolve("gbuffers_textured").orElseThrow().name());
    }

    @Test void customDimensionsResolveExplicitMappingsThenWildcardWithoutRootMerging() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("final.vsh", VERTEX, "final.fsh", FRAGMENT,
            "alternate/final.vsh", VERTEX, "alternate/final.fsh", FRAGMENT,
            "dimension.properties", "dimension.alternate = custom:moon *\n"));
        assertEquals("alternate", plan.forDimension("custom:moon").folder());
        assertEquals("alternate", plan.forDimension("minecraft:the_nether").folder());
        assertEquals("alternate", plan.dimensionMappings().get("custom:moon"));
        assertEquals("", plan.world("").folder());
    }

    // Catches lexicographic ordering and treating sibling computes as independent fullscreen passes.
    @Test void passNumbersSortNumericallyAndComputeSiblingsRunBeforeGraphics() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("composite10.csh", COMPUTE, "composite2.csh", COMPUTE,
            "composite.csh", COMPUTE, "composite2_b.csh", COMPUTE, "composite2_a.csh", COMPUTE,
            "composite2.vsh", VERTEX, "composite2.fsh", FRAGMENT, "begin.csh", COMPUTE));
        assertEquals(List.of("begin", "composite", "composite2", "composite10"),
            plan.world("").programs().stream().map(IrisPipelinePlan.Program::name).toList());
        var p = plan.world("").programs().get(2);
        assertEquals(List.of("composite2.csh", "composite2_a.csh", "composite2_b.csh"),
            p.computes().stream().map(IrisPipelinePlan.Stage::path).toList());
        assertEquals(IrisPipelinePlan.Phase.COMPOSITE, p.phase());
    }

    // Catches default options being ignored by property conditionals or option-based program enable expressions.
    @Test void defaultOptionsSelectPropertiesAndDisabledProgramsRemainInventoried() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("lib/settings.glsl", "#define BLOOM\n//#define UPSCALE\n",
            "shaders.properties", "#if BLOOM\nimage.bloom = bloomSampler RGBA RGBA16F HALF_FLOAT true true 0.5 0.5\n#endif\nprogram.composite.enabled = BLOOM && !UPSCALE\nprogram.composite1.enabled = UPSCALE\n",
            "composite.csh", COMPUTE, "composite1.csh", COMPUTE));
        assertTrue(plan.world("").programs().getFirst().enabled());
        assertFalse(plan.world("").programs().get(1).enabled());
        assertEquals("RGBA16F", plan.images().getFirst().internalFormat());
        assertEquals(List.of(0.5, 0.5), plan.images().getFirst().dimensions());
    }

    // Catches render target parsing before conditional selection, lost MRT output locations, or lost resource semantics.
    @Test void selectedTargetsAndConstantsPreserveOutputLocationsAndResourceDeclarations() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("composite.vsh", VERTEX,
            "composite.fsh", "#version 430\n#define MODE 1\n#if MODE == 1\n/* RENDERTARGETS: 3,12,7 */\n#else\n/* DRAWBUFFERS: 01 */\n#endif\nconst int colortex3Format = RGBA16F;\nconst bool colortex3Clear = false;\nconst vec4 colortex3ClearColor = vec4(0.1,0.2,0.3,1.0);\nconst bool colortex12MipmapEnabled = true;\nvoid main(){gl_FragData[0]=vec4(1.0);}\n",
            "shaders.properties", "bufferObject.4 = 1024\nimage.voxels = voxelSampler RED_INTEGER R32UI UNSIGNED_INT false false 64 32 16\ntexture.composite.colortex6 = texture/noise.png\nsize.buffer.colortex3 = 0.5 0.5\nflip.composite.colortex3 = false\nunknown.vendor.setting = retain me\n"));
        var fragment = plan.world("").programs().getFirst().stages().get(IrisStage.FRAGMENT);
        assertEquals(List.of(3, 12, 7), fragment.targets());
        assertEquals("RGBA16F", fragment.constants().get("colortex3Format").expression());
        assertEquals("false", fragment.constants().get("colortex3Clear").expression());
        assertEquals(List.of(64.0, 32.0, 16.0), plan.images().getFirst().dimensions());
        assertEquals(1024L, plan.buffers().getFirst().byteSize());
        assertEquals("colortex6", plan.textures().getFirst().sampler());
        assertEquals("composite", plan.textures().getFirst().scope());
        assertEquals("0.5 0.5", plan.renderSettings().get("size.buffer.colortex3"));
        assertEquals("retain me", plan.unhandledProperties().get("unknown.vendor.setting"));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("UNHANDLED_PROPERTIES")));
    }

    @Test void irisConstantDirectivesInsideBlockCommentsSurviveSelectedBranches() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("composite.fsh", """
            #version 430
            #define QUALITY 1
            #if QUALITY == 1
            /*
            const int colortex0Format = RGBA16F;
            // const int colortex1Format = R32F;
            */
            #else
            /*
            const int colortex0Format = RGBA8;
            */
            #endif
            void main(){gl_FragColor=vec4(1.0);}
            """));
        var fragment = plan.world("").programs().getFirst().stages().get(IrisStage.FRAGMENT);
        assertEquals("RGBA16F", fragment.constants().get("colortex0Format").expression());
        assertFalse(fragment.constants().containsKey("colortex1Format"));
    }

    // Catches silently dropping malformed custom resources or inventing support for external programs.
    @Test void malformedResourcesAndOptionalProgramsProduceExplicitDiagnostics() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("dh_terrain.fsh", FRAGMENT,
            "shaders.properties", "image.bad = too few fields\nbufferObject.2 = nope\n"));
        assertTrue(plan.world("").programs().getFirst().optionalIntegration());
        assertEquals(IrisPipelinePlan.Phase.INTEGRATION, plan.world("").programs().getFirst().phase());
        assertEquals(2, plan.diagnostics().stream().filter(d -> d.code().equals("INVALID_RESOURCE")).count());
        assertTrue(plan.unhandledProperties().containsKey("image.bad"));
    }

    @Test void optionalVoxelIntegrationsRetainTheirGeometryPhase() throws Exception {
        var plan = IrisPipelinePlan.parse(sources("gbuffers_voxels.fsh", FRAGMENT));
        var program = plan.world("").programs().getFirst();
        assertTrue(program.optionalIntegration());
        assertEquals(IrisPipelinePlan.Phase.GBUFFERS, program.phase());
    }

    @Test void realEclipseInventoryWhenConfigured() throws Exception {
        String pack = System.getProperty("greenmarine.iris.pack");
        org.junit.jupiter.api.Assumptions.assumeTrue(pack != null && !pack.isBlank());
        var plan = IrisPipelinePlan.parse(IrisSources.load(Path.of(pack)));
        for (String world : List.of("world0", "world1", "world-1")) assertEquals(world, plan.world(world).folder());
        assertEquals("world0_with_aether_flag", plan.forDimension("aether:the_aether").folder());
        assertTrue(plan.world("world0").programs().stream().anyMatch(p -> p.name().equals("composite10")));
        assertTrue(plan.images().stream().anyMatch(i -> i.dimensions().size() == 3));
        assertTrue(plan.world("world0").programs().stream().flatMap(p -> p.stages().values().stream())
            .anyMatch(s -> s.constants().containsKey("colortex0Format")));
    }
}
