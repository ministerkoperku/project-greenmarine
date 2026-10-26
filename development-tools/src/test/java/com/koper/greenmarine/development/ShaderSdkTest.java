package com.koper.greenmarine.development;

import com.google.gson.JsonParser;
import com.koper.greenmarine.api.pack.PackPass;
import com.koper.greenmarine.api.pack.ShaderPack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShaderSdkTest {
    // Reflection keeps the initial failure behavioral: the SDK is absent, rather than a compile error.
    private Object call(String method, Class<?>[] types, Object... args) {
        try {
            var sdk = Class.forName("com.koper.greenmarine.development.ShaderSdk");
            var target = sdk.getDeclaredMethod(method, types); target.setAccessible(true);
            return target.invoke(null, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) { throw new AssertionError("Embedded SDK contract missing", error); }
    }
    @SuppressWarnings("unchecked") private List<Object> entries() { return (List<Object>) call("entries", new Class<?>[0]); }
    private String field(Object entry, String name) throws Exception { var method=entry.getClass().getDeclaredMethod(name); method.setAccessible(true); return (String)method.invoke(entry); }
    @SuppressWarnings("unchecked") private Map<String,String> starter(String ns,String name,String template) { return (Map<String,String>)call("starter",new Class<?>[]{String.class,String.class,String.class},ns,name,template); }
    @SuppressWarnings("unchecked") private List<Object> search(String query) { return (List<Object>)call("search",new Class<?>[]{String.class},query); }

    @Test void catalogsEveryCurrentDocumentIncludeAndPublicJavaSourceVerbatim() throws Exception {
        var catalog=entries(); assertFalse(catalog.isEmpty());
        var byId=new java.util.HashMap<String,String>();
        for(var entry:catalog) assertNull(byId.put(field(entry,"id"),field(entry,"text")),"duplicate entry id");
        Path root=Path.of("..");
        for(String directory:List.of("docs","src/main/resources/assets/greenmarine/shaders/include","src/main/java/com/koper/greenmarine/api")) {
            try(var paths=Files.walk(root.resolve(directory))) {
                for(var path:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".md")||p.toString().endsWith(".glsl")||p.toString().endsWith(".java")).toList()) {
                    String id=root.relativize(path).toString().replace('\\','/');
                    assertEquals(Files.readString(path),byId.get(id),id);
                }
            }
        }
        assertFalse(search("MarineFrame").isEmpty());
        assertEquals(search("marineframe").size(),search("MARINEFRAME").size());
        assertEquals(catalog.size(),search(" ").size());
        assertTrue(search("this_symbol_does_not_exist_649739").isEmpty());
        assertFalse(search("Java-only").isEmpty());
        assertFalse(search("lightingHistory").isEmpty());
        assertFalse(search("Slang").isEmpty());
    }

    @Test void generatesCompleteIndependentProjectsForAllThreeTemplates() {
        assertEquals(List.of("fullscreen","compute","world"),call("templates",new Class<?>[0]));
        for(String template:List.of("fullscreen","compute","world")) {
            var files=starter("test_sdk","My starter \"example\"",template);
            String manifestPath="assets/test_sdk/greenmarine/packs/main.json";
            assertTrue(files.containsKey("SDK.md"));
            var pack=ShaderPack.parse(JsonParser.parseString(files.get(manifestPath)).getAsJsonObject());
            assertEquals("test_sdk:main",pack.id()); assertEquals("My starter \"example\"",pack.name());
            for(var path:files.keySet()) assertTrue(path.equals("SDK.md")||path.startsWith("assets/"),path);
            for(var pass:pack.passes()) {
                assertTrue(files.containsKey(shaderPath(pass.fragment(),pass.stage()==PackPass.Stage.COMPUTE?"csh":"fsh")),pass.fragment());
                if(pass.fallback()!=null) assertTrue(files.containsKey(shaderPath(pass.fallback(),"fsh")));
            }
            for(var program:pack.programs()) {
                assertTrue(files.containsKey(shaderPath(program.fragment(),"fsh")));
                if(program.vertex()!=null)assertTrue(files.containsKey(shaderPath(program.vertex(),"vsh")));
            }
            if(template.equals("compute"))assertTrue(pack.passes().stream().anyMatch(p->p.stage()==PackPass.Stage.COMPUTE));
            if(template.equals("world"))assertFalse(pack.programs().isEmpty());
            assertThrows(UnsupportedOperationException.class,()->files.put("broken","broken"));
        }
    }
    private static String shaderPath(String id,String ext) { return "assets/"+id.replace(":","/shaders/")+"."+ext; }

    @Test void fullscreenAndComputeSourcesCompileWithTheirPrivateIncludes() {
        for(String template:List.of("fullscreen","compute")) {
            var files=starter("demo","Compile starter",template);
            for(var entry:files.entrySet()) {
                String path=entry.getKey();
                if(!path.endsWith(".fsh")&&!path.endsWith(".csh"))continue;
                var stage=path.endsWith(".csh")?ShaderCompiler.Stage.COMPUTE:ShaderCompiler.Stage.FRAGMENT;
                var result=ShaderCompiler.compile(ShaderCompiler.Language.GLSL,stage,entry.getValue(),path,null,
                    id-> { int colon=id.indexOf(':'); return files.get("assets/"+id.substring(0,colon)+"/shaders/include/"+id.substring(colon+1)); });
                assertTrue(result.success(),path+": "+result.diagnostics());
            }
        }
    }

    @Test void rejectsTraversalAndInvalidProjectInputs() {
        for(String ns:List.of("../escape","bad/ns","UPPER","","test:other"))
            assertThrows(IllegalArgumentException.class,()->starter(ns,"Example","fullscreen"));
        assertThrows(IllegalArgumentException.class,()->starter("demo","","fullscreen"));
        assertThrows(IllegalArgumentException.class,()->starter("demo","a".repeat(81),"fullscreen"));
        assertThrows(IllegalArgumentException.class,()->starter("demo","Example","unknown"));
    }

    @Test void offersEditableSnippetsForAllAuthoringContexts() throws Exception {
        var ids=new java.util.HashSet<String>(); for(var entry:entries())ids.add(field(entry,"id"));
        for(String type:List.of("scene","lightmap","compute","include","vertex","world","lua","material","manifest"))
            assertTrue(ids.contains("snippet/"+type),type);
    }
}
