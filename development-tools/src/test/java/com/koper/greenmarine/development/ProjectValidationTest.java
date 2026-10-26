package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectValidationTest {
    @TempDir Path root;
    private static final String MANIFEST="pack/assets/test/greenmarine/packs/main.json";
    private static final String FRAGMENT="pack/assets/test/shaders/core/main.fsh";
    private static final String VALID_FRAGMENT="#version 450\nlayout(location=0) out vec4 color; void main(){color=vec4(1);}\n";
    private ShaderProject project(String extraFields)throws Exception{
        var ws=new ShaderWorkspace(root);
        ws.save(MANIFEST,"{\"schema\":1,\"id\":\"test:pack\",\"passes\":[{\"name\":\"main\",\"fragment\":\"test:core/main\"}]"+extraFields+"}");
        ws.save(FRAGMENT,VALID_FRAGMENT);
        return new ShaderProject(ws,FRAGMENT);
    }
    private ProjectValidator.Result validate(ShaderProject project,Map<String,String> drafts){
        return ProjectValidator.validate(project,drafts,id->null,id->null);
    }
    @Test void validatesEveryManifestAndItsUnopenedShaderWithoutWritingDrafts()throws Exception{
        var project=project("");
        String second="pack/assets/test/greenmarine/packs/second.json";
        project.workspace().save(second,"{\"schema\":1,\"id\":\"test:second\",\"passes\":[{\"name\":\"other\",\"fragment\":\"test:core/other\"}]}");
        String other="pack/assets/test/shaders/core/other.fsh";
        project.workspace().save(other,VALID_FRAGMENT);
        var result=validate(project,Map.of(other,"#version 450\nthis is broken\n"));
        assertFalse(result.valid());assertEquals(2,result.manifests());assertEquals(4,result.checkedFiles());
        assertTrue(result.problems().stream().anyMatch(p->p.path().equals(other)&&p.line()==2),result.summary());
        assertEquals(VALID_FRAGMENT,project.workspace().read(other));
    }
    @Test void unsavedManifestAndIncludeResolveTogether()throws Exception{
        var project=project("");String include="pack/assets/test/shaders/include/color.glsl";
        project.workspace().save(include,"this is invalid");
        var drafts=new HashMap<String,String>();
        drafts.put(MANIFEST,project.workspace().read(MANIFEST).replace("test:core/main","test:core/draft"));
        String draft="pack/assets/test/shaders/core/draft.fsh";
        project.workspace().save(draft,"bad disk source");
        drafts.put(draft,"#version 450\n#include <test:color.glsl>\nlayout(location=0) out vec4 color; void main(){color=shade();}\n");
        drafts.put(include,"vec4 shade(){return vec4(1);}\n");
        var result=validate(project,drafts);assertTrue(result.valid(),result.summary());
        assertEquals("this is invalid",project.workspace().read(include));
    }
    @Test void invalidManifestDraftIsReportedAtItsWorkspacePath()throws Exception{
        var project=project("");var result=validate(project,Map.of(MANIFEST,"{\n \"schema\": }"));
        assertFalse(result.valid());assertTrue(result.problems().stream().anyMatch(p->p.path().equals(MANIFEST)&&p.line()>=1),result.summary());
    }
    @Test void boundedLuaInitializationRunsButUpdateNeverRuns()throws Exception{
        var project=project(",\"script\":\"test:scripts/main.lua\"");String lua="pack/assets/test/scripts/main.lua";
        project.workspace().save(lua,"function update(frame) error('must not tick') end");
        assertTrue(validate(project,Map.of()).valid());
        var missing=validate(project,Map.of(lua,"local x=1"));
        assertFalse(missing.valid());assertTrue(missing.problems().stream().anyMatch(p->p.path().equals(lua)),missing.summary());
        var infinite=assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->validate(project,Map.of(lua,"while true do end\nfunction update(frame) end")));
        assertFalse(infinite.valid());assertTrue(infinite.summary().contains("budget"),infinite.summary());
    }
    @Test void validatesUnreferencedProjectMaterialsAndDeclaredSpirv()throws Exception{
        var project=project("");String material="pack/assets/test/greenmarine/materials/invalid.json";
        project.workspace().save(material,"{\"schema\":1,\"id\":\"test:materials\",\"textures\":{\"test:block/a\":{\"default\":{\"surface\":\"unknown\",\"roughness\":0,\"reflection\":0,\"emission\":0}}}}");
        var materialResult=validate(project,Map.of());
        assertFalse(materialResult.valid());
        assertTrue(materialResult.problems().stream().anyMatch(problem->problem.path().equals(material)),materialResult.summary());
        Files.delete(root.resolve(material));
        String manifest=project.workspace().read(MANIFEST).replace("\"fragment\":\"test:core/main\"","\"fragment\":\"test:core/main\",\"spirv\":\"test:shaders/core/main.spv\"");
        Files.write(root.resolve("pack/assets/test/shaders/core/main.spv"),new byte[]{1,2,3,4});
        var binaryResult=validate(project,Map.of(MANIFEST,manifest));
        assertFalse(binaryResult.valid());
        assertTrue(binaryResult.problems().stream().anyMatch(problem->problem.path().equals("pack/assets/test/shaders/core/main.spv")),binaryResult.summary());
    }
    @Test void rejectsDraftOutsideProjectAndFreezesResultProblems()throws Exception{
        var project=project("");var result=validate(project,Map.of("other/assets/test/main.fsh",VALID_FRAGMENT));
        assertFalse(result.valid());assertThrows(UnsupportedOperationException.class,()->result.problems().clear());
    }
    @Test void enforcesExternalLoaderAssetSizeWithoutApplyingItToProjectDocumentation()throws Exception{
        var project=project("");
        Files.writeString(root.resolve("pack/SDK.md"),"x".repeat(300000));
        Files.createDirectories(root.resolve("pack/iris-source"));
        Files.writeString(root.resolve("pack/iris-source/original.fsh"),"x".repeat(300000));
        assertTrue(validate(project,Map.of()).valid());
        String asset="pack/assets/test/textures/oversized.png";
        Files.createDirectories(root.resolve(asset).getParent());
        Files.write(root.resolve(asset),new byte[262145]);
        var result=validate(project,Map.of());
        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(problem->problem.path().equals(asset)&&problem.message().contains("262144")),result.summary());
    }
    @Test void enforcesExternalLoaderTotalRecognizedAssetBudget()throws Exception{
        var project=project("");Path assets=root.resolve("pack/assets/test/data");Files.createDirectories(assets);
        for(int index=0;index<33;index++)Files.write(assets.resolve("chunk_"+index+".bin"),new byte[262144]);
        var result=validate(project,Map.of());
        assertFalse(result.valid());assertTrue(result.summary().contains("8 MiB"),result.summary());
    }
    @Test void enforcesExternalLoaderTraversalEntryCount()throws Exception{
        var project=project("");Path docs=root.resolve("pack/notes");Files.createDirectories(docs);
        for(int index=0;index<2048;index++)Files.writeString(docs.resolve("note_"+index+".txt"),"");
        var result=validate(project,Map.of());
        assertFalse(result.valid());assertTrue(result.summary().contains("2048"),result.summary());
    }
    @Test void reloadValidationUsesComputeSourcesAsComputeStages() throws Exception {
        var ws=new ShaderWorkspace(root);String path="pack/assets/test/greenmarine/packs/main.json";
        ws.save(path,"{\"schema\":1,\"id\":\"test:pack\",\"passes\":[{\"name\":\"work\",\"stage\":\"compute\",\"compute\":\"test:compute/main\",\"groups\":[1,1,1]}]}");
        ws.save("pack/assets/test/shaders/compute/main.csh","#version 450\nlayout(local_size_x=1) in; void main(){}\n");
        assertNull(new ReloadValidator(root,root.resolve("materials"),id->null,id->null).validate(List.of(root.resolve(path))));
    }
}
