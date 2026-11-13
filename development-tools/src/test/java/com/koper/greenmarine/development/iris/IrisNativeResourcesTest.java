package com.koper.greenmarine.development.iris;

import com.koper.greenmarine.development.IrisProjectExporter;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisNativeResourcesTest {
    private static final String VERTEX="#version 430\nvoid main(){gl_Position=vec4(0.0);}";
    private static IrisPipelinePlan plan(String properties,String... stages) throws Exception {
        var files=new LinkedHashMap<String,byte[]>();files.put("shaders/shaders.properties",properties.getBytes(StandardCharsets.UTF_8));
        if(stages.length==0)stages=new String[]{"final.vsh",VERTEX,"final.fsh",fragment("")};
        for(int i=0;i<stages.length;i+=2)files.put("shaders/"+stages[i],stages[i+1].getBytes(StandardCharsets.UTF_8));
        return IrisPipelinePlan.parse(IrisSources.of(files));
    }
    private static String fragment(String directives){return "#version 430\n"+directives+"\nvoid main(){gl_FragData[0]=vec4(1.0);}";}
    private static IrisNativeResources.World root(IrisNativeResources.Result result){return result.worlds().stream().filter(w->w.folder().isEmpty()).findFirst().orElseThrow();}

    @Test void compilerIsAvailableWithoutMinecraftRuntime() throws Exception {
        // The converter CLI must load this compiler without a Minecraft jar on the classpath.
        assertNotNull(Class.forName("com.koper.greenmarine.development.iris.IrisNativeResources"));
    }
    @Test void targetFormatsClearBitsSizesAndMrtLocationsLowerWithoutCoercion() throws Exception {
        var result=IrisNativeResources.compile(plan("size.buffer.colortex3=0.5 0.5\nsize.buffer.colortex12=0.5 0.5\n",
            "composite.vsh",VERTEX,"composite.fsh",fragment("""
                /* RENDERTARGETS: 3,12 */
                const int colortex3Format=RGBA16F;
                const bool colortex3Clear=false;
                const int colortex12Format=RG32UI;
                const vec4 colortex12ClearColor=vec4(1.0,0.0,0.0,0.0);
                """).replace("gl_FragData[0]=vec4(1.0);","gl_FragData[0]=vec4(1.0);gl_FragData[1]=vec4(0.0);")));
        assertTrue(result.complete(),result.diagnostics().toString());var world=root(result);
        assertEquals("RGBA16F",world.targets().getAsJsonObject("colortex3").get("format").getAsString());
        assertEquals(.5,world.targets().getAsJsonObject("colortex3").get("scale").getAsDouble());
        assertFalse(world.targets().getAsJsonObject("colortex3").get("clearEachFrame").getAsBoolean());
        assertEquals(1065353216L,world.targets().getAsJsonObject("colortex12").getAsJsonArray("clear").get(0).getAsLong());
        assertEquals(Map.of(0,"colortex3",1,"colortex12"),world.passes().getFirst().outputs());
    }
    @Test void defaultFogClearAndUnsupportedFormatsNeverBecomeComplete() throws Exception {
        var result=IrisNativeResources.compile(plan("","composite.fsh",fragment("/* RENDERTARGETS: 0,2 */\nconst int colortex2Format=RGBA16UI;")));
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("DYNAMIC_TARGET_CLEAR")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("UNSUPPORTED_FORMAT")));
        assertFalse(root(result).targets().has("colortex2"));
    }
    @Test void flipsAndMipsRetainExactProgramScopeAndBlockUnsupportedExecution() throws Exception {
        var result=IrisNativeResources.compile(plan("flip.composite2.colortex3=false\n",
            "composite2.fsh",fragment("/* RENDERTARGETS: 3 */\nconst bool colortex3MipmapEnabled=true;"),
            "composite10.fsh",fragment("/* RENDERTARGETS: 3 */")));
        assertFalse(result.complete());var passes=root(result).passes();
        assertEquals(List.of("composite2","composite10"),passes.stream().map(IrisNativeResources.Pass::program).toList());
        assertEquals(false,passes.getFirst().flips().get("colortex3"));
        assertEquals(true,passes.get(1).flips().get("colortex3"));
        assertEquals(Set.of("colortex3"),passes.getFirst().mipmaps());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("SUPPRESSED_TARGET_FLIP")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("TARGET_MIPMAPS")));
    }
    @Test void imagesKeepDimensionsSamplerAliasAndClearRequirement() throws Exception {
        var result=IrisNativeResources.compile(plan("""
            image.table=tableSampler RG_INTEGER RG32UI UNSIGNED_INT false false 2048
            image.volume=none RED_INTEGER R32UI UNSIGNED_INT true false 16 8 4
            image.screen=screenSampler RG RG16F HALF_FLOAT false true 0.5 0.5
            """));
        var world=root(result);var images=world.resources().getAsJsonObject("images");
        assertEquals(1,images.getAsJsonObject("table").getAsJsonArray("size").size());
        assertEquals(3,images.getAsJsonObject("volume").getAsJsonArray("size").size());
        assertEquals(.5,images.getAsJsonObject("screen").get("scale").getAsDouble());
        assertEquals("tableSampler",world.imageBindings().stream().filter(i->i.image().equals("table")).findFirst().orElseThrow().sampler());
        assertTrue(result.complete(),result.diagnostics().toString());
        assertFalse(images.getAsJsonObject("table").get("clearEachFrame").getAsBoolean());
        assertTrue(images.getAsJsonObject("volume").get("clearEachFrame").getAsBoolean());
        assertFalse(images.getAsJsonObject("screen").get("clearEachFrame").getAsBoolean());
    }
    @Test void linkedSsboBindingUsesActualBlockNameAndSharedIdentity() throws Exception {
        var p=plan("bufferObject.4=1024\n","composite.csh","#version 430\nlayout(local_size_x=1) in; const ivec3 workGroups=ivec3(1,1,1); void main(){}");
        var group=new IrisProjectExporter.Group("root/composite","","composite","compute",false,true,List.of(),List.of(),List.of(),List.of(),
            List.of(new IrisProgramLinker.ResourceBlock("buffer","Counters","layout(std430,binding=4) buffer Counters { uint value; };",4)),"");
        var result=IrisNativeResources.compile(p,List.of(group));
        assertTrue(result.complete(),result.diagnostics().toString());
        assertEquals(1024,root(result).resources().getAsJsonObject("buffers").getAsJsonObject("Counters").get("size").getAsInt());
        assertEquals("Counters",root(result).bufferBindings().getFirst().resource());
        assertEquals("1,1,1",root(result).passes().getFirst().groups());
    }
    @Test void conflictingBufferAliasesAndRelativeSizesRemainUnresolved() throws Exception {
        var p=plan("bufferObject.4=1024\nbufferObject.5=16 true 0.5 0.5\n");
        var blocks=List.of(new IrisProgramLinker.ResourceBlock("buffer","A","",4),new IrisProgramLinker.ResourceBlock("buffer","B","",4));
        var group=new IrisProjectExporter.Group("root/test","","test","compute",false,true,List.of(),List.of(),List.of(),List.of(),blocks,"");
        var result=IrisNativeResources.compile(p,List.of(group));
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("BUFFER_BINDING_ALIASES")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("RELATIVE_BUFFER_SIZE")));
    }
    @Test void rawAndFileTexturesRemainCopyableRequirementsAndCannotMasqueradeAsEmptyImages() throws Exception {
        var result=IrisNativeResources.compile(plan("customTexture.noise=texture/a.png\ntexture.composite.colortex6=texture/raw.bin TEXTURE_3D R32F 4 4 4 RED FLOAT\n"));
        assertFalse(result.complete());assertEquals(2,root(result).textureBindings().size());
        assertEquals("composite",root(result).textureBindings().get(1).scope());
        assertEquals("texture/raw.bin",root(result).textureBindings().get(1).path());
        assertTrue(root(result).resources().getAsJsonObject("images").isEmpty());
    }
    @Test void disabledAndOtherDimensionDirectivesDoNotLeakIntoSelectedWorld() throws Exception {
        var result=IrisNativeResources.compile(plan("program.composite.enabled=false\n",
            "composite.fsh",fragment("/* RENDERTARGETS: 3 */\nconst int colortex3Format=R16UI;"),
            "world1/composite.fsh",fragment("/* RENDERTARGETS: 3 */\nconst int colortex3Format=RGBA32F;")));
        assertTrue(root(result).targets().isEmpty());
        assertEquals("RGBA32F",result.worlds().stream().filter(w->w.folder().equals("world1")).findFirst().orElseThrow().targets().getAsJsonObject("colortex3").get("format").getAsString());
    }
    @Test void realEclipseResourcePlanRetainsTypedRequirements() throws Exception {
        String pack=System.getProperty("greenmarine.iris.pack");org.junit.jupiter.api.Assumptions.assumeTrue(pack!=null&&!pack.isBlank());
        var source=IrisPipelinePlan.parse(IrisSources.load(Path.of(pack)));var result=IrisNativeResources.compile(source);
        assertFalse(result.complete());
        var world=result.worlds().stream().filter(w->w.folder().equals("world0")).findFirst().orElseThrow();
        assertTrue(world.targets().size()>4);assertFalse(world.textureBindings().isEmpty());
        assertTrue(world.imageBindings().stream().anyMatch(i->i.dimensions()==3));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("CUSTOM_TEXTURE_UPLOAD")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("BUFFER_BLOCK_UNRESOLVED")));
    }
    @Test void legacyDepthSamplerUpgradesUnspecifiedDepthTargetAndPreservesLegacySizes() throws Exception {
        var result=IrisNativeResources.compile(plan("size.buffer.gdepth=0.5 0.5\n","composite.fsh",fragment("/* RENDERTARGETS: 1 */\nuniform sampler2D gdepth;")));
        assertTrue(result.complete(),result.diagnostics().toString());var target=root(result).targets().getAsJsonObject("colortex1");
        assertEquals("RGBA32F",target.get("format").getAsString());assertEquals(.5,target.get("scale").getAsDouble());
    }
    @Test void sameBlockNameCannotMergeIndependentIrisBufferIdentities() throws Exception {
        var p=plan("bufferObject.4=1024\nbufferObject.5=1024\n");var groups=new ArrayList<IrisProjectExporter.Group>();
        for(int binding:List.of(4,5))groups.add(new IrisProjectExporter.Group("g"+binding,"","final","graphics",false,true,List.of(),List.of(),List.of(),List.of(),List.of(new IrisProgramLinker.ResourceBlock("buffer","Counters","",binding)),""));
        var result=IrisNativeResources.compile(p,groups);assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("BUFFER_IDENTITY_COLLISION")));
    }
    @Test void requiredUndeclaredBlocksCannotBeReportedComplete() throws Exception {
        var p=plan("");var group=new IrisProjectExporter.Group("g","","final","graphics",false,true,List.of(),List.of(),List.of(),List.of(),List.of(new IrisProgramLinker.ResourceBlock("buffer","Counters","",4)),"");
        var result=IrisNativeResources.compile(p,List.of(group));assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("BUFFER_DECLARATION_MISSING")));
    }
    @Test void implicitTargetsOnlyBindShaderOutputLocations() throws Exception {
        var result=IrisNativeResources.compile(plan("","composite.fsh",fragment("const vec4 colortex0ClearColor=vec4(0.0);")));
        assertTrue(result.complete(),result.diagnostics().toString());
        assertEquals(Map.of(0,"colortex0"),root(result).passes().getFirst().outputs());
        assertEquals(Set.of("colortex0"),root(result).targets().keySet());
    }
    @Test void explicitTargetsWithoutMatchingFragmentOutputsRemainUnresolved() throws Exception {
        var result=IrisNativeResources.compile(plan("","composite.fsh",fragment("/* RENDERTARGETS: 2,3 */")));
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("FRAGMENT_OUTPUT_MISMATCH")));
        assertEquals(Map.of(0,"colortex2"),root(result).passes().getFirst().outputs());
    }
    @Test void realLinkerDescriptorOffsetResolvesOriginalIrisSsboBinding() throws Exception {
        var p=plan("bufferObject.4=1024\n","composite.csh","#version 430\nlayout(local_size_x=1) in; const ivec3 workGroups=ivec3(1); layout(std430,binding=4) buffer Counters { uint counter; }; void main(){counter=1u;}");
        var source=p.world("").programs().getFirst().computes().getFirst();var linked=IrisProgramLinker.link(Map.of(IrisStage.COMPUTE,source.preprocessedText()));
        assertEquals(104,linked.blocks().getFirst().binding());
        var group=new IrisProjectExporter.Group("g","","composite","compute",false,true,List.of(),linked.uniforms(),linked.opaques(),linked.varyings(),linked.blocks(),"");
        var result=IrisNativeResources.compile(p,List.of(group));assertTrue(result.complete(),result.diagnostics().toString());
        assertEquals("Counters",root(result).bufferBindings().getFirst().resource());
        assertEquals(4,root(result).bufferBindings().getFirst().binding());
    }
    @Test void defaultAndIdentityRelativeComputeDispatchUseScreenCoverage() throws Exception {
        var result=IrisNativeResources.compile(plan("","begin.csh","#version 430\nlayout(local_size_x=8,local_size_y=8) in;void main(){}",
            "prepare.csh","#version 430\nlayout(local_size_x=8,local_size_y=8) in;const vec2 workGroupsRender=vec2(1.0);void main(){}"));
        assertTrue(result.complete(),result.diagnostics().toString());assertEquals(List.of("screen","screen"),root(result).passes().stream().map(IrisNativeResources.Pass::groups).toList());
    }
    @Test void nonidentityRelativeComputeDispatchCannotSilentlyDispatchWholeScreen() throws Exception {
        var result=IrisNativeResources.compile(plan("","begin.csh","#version 430\nlayout(local_size_x=8) in;const vec2 workGroupsRender=vec2(0.5,0.25);void main(){}"));
        assertFalse(result.complete());assertNull(root(result).passes().getFirst().groups());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("RELATIVE_COMPUTE_DISPATCH")));
    }
    @Test void legacyFlipAliasRetainsSuppressedFlipSemantics() throws Exception {
        var result=IrisNativeResources.compile(plan("flip.composite.gaux1=false\n","composite.fsh",fragment("/* RENDERTARGETS: 4 */")));
        assertFalse(result.complete());assertEquals(false,root(result).passes().getFirst().flips().get("colortex4"));
    }
    @Test void modernUnlocatedOutputsUseActualLinkedLocations() throws Exception {
        var result=IrisNativeResources.compile(plan("","composite.vsh",VERTEX,"composite.fsh","#version 430\n/* RENDERTARGETS: 3 */\nout vec4 resultColor;void main(){resultColor=vec4(1.0);}"));
        assertTrue(result.complete(),result.diagnostics().toString());assertEquals(Map.of(0,"colortex3"),root(result).passes().getFirst().outputs());
    }
    @Test void incompatibleMrtExtentsAndWorldTargetExtentsCannotBeReportedComplete() throws Exception {
        var result=IrisNativeResources.compile(plan("size.buffer.colortex2=0.5 0.5\n","composite.fsh",fragment("/* RENDERTARGETS: 2,3 */").replace("gl_FragData[0]=vec4(1.0);","gl_FragData[0]=vec4(1.0);gl_FragData[1]=vec4(1.0);"),
            "gbuffers_terrain.vsh",VERTEX,"gbuffers_terrain.fsh",fragment("/* RENDERTARGETS: 2 */")));
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("TARGET_DIMENSION_MISMATCH")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("WORLD_TARGET_DIMENSIONS")));
    }
    @Test void viewportTransformCannotBeSilentlyReplacedWithTargetScaling() throws Exception {
        var result=IrisNativeResources.compile(plan("scale.composite=0.5 0.25 0.25\n","composite.fsh",fragment("/* RENDERTARGETS: 2 */")));
        assertFalse(result.complete());assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("PASS_VIEWPORT")));
    }
}
