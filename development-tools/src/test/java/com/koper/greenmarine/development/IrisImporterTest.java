package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisImporterTest {
    @TempDir Path temp;
    private static final String LEGACY="""
        #version 120
        uniform sampler2D colortex0;
        varying vec2 texcoord;
        void main(){ gl_FragColor=texture2D(colortex0,texcoord)*vec4(0.8,1.0,1.0,1.0); }
        """;
    @Test void convertsLegacyFullscreenDirectoryAndPreservesOriginalBytes() throws Exception {
        Path source=directory("source",Map.of("shaders/final.fsh",LEGACY,"LICENSE.txt","owner license"));
        Path destination=temp.resolve("workspace/converted");
        var report=IrisImporter.convert(source,destination);
        assertTrue(report.runnable(),report.diagnostics().toString()); assertEquals(2,report.sourceFiles());
        assertArrayEquals(Files.readAllBytes(source.resolve("shaders/final.fsh")),Files.readAllBytes(destination.resolve("iris-source/shaders/final.fsh")));
        assertEquals("owner license",Files.readString(destination.resolve("iris-source/LICENSE.txt")));
        Path shader=destination.resolve("assets/iris_imported/shaders/core/converted.fsh");
        String translated=Files.readString(shader);
        assertTrue(translated.contains("#version 450")); assertTrue(translated.contains("ColorSampler"));
        assertFalse(translated.contains("gl_FragColor"));
        assertTrue(ShaderCompiler.compile(ShaderCompiler.Language.GLSL,ShaderCompiler.Stage.FRAGMENT,translated,"converted.fsh",null).success());
        assertTrue(Files.isRegularFile(destination.resolve("assets/iris_imported/greenmarine/packs/imported.json")));
        assertTrue(Files.readString(destination.resolve("IMPORT-REPORT.md")).contains("not full Iris runtime compatibility"));
    }
    @Test void importsZipsWithASingleEnclosingFolder() throws Exception {
        Path archive=zip("wrapped.zip",Map.of("Pretty Pack/shaders/final.fsh",LEGACY,"Pretty Pack/LICENSE.txt","owner license"));
        var result=IrisImporter.convert(archive,temp.resolve("wrapped-import"));
        assertTrue(result.runnable(),result.diagnostics().toString());
        assertEquals("owner license",Files.readString(result.destination().resolve("iris-source/LICENSE.txt")));
    }
    @Test void selectingTheShadersDirectoryImportsItsPack() throws Exception {
        Path original=directory("pack",Map.of("shaders/final.fsh",LEGACY,"LICENSE.txt","owner license"));
        var result=IrisImporter.convert(original.resolve("shaders"),temp.resolve("shader-folder-import"));
        assertTrue(result.runnable(),result.diagnostics().toString());
        assertEquals("owner license",Files.readString(result.destination().resolve("iris-source/LICENSE.txt")));
    }
    @Test void convertsModernZipWithDepthSamplerAndRootRelativeInclude() throws Exception {
        String fragment="#version 330 compatibility\n#include \"/lib/effect.glsl\"\nuniform sampler2D colortex0; uniform sampler2D depthtex0;\nin vec2 texcoord; layout(location=0) out vec4 color;\nvoid main(){color=texture(colortex0,texcoord)*effect(texture(depthtex0,texcoord).r);}";
        Path zip=zip("modern.zip",Map.of("shaders/composite.fsh",fragment,"shaders/lib/effect.glsl","float effect(float depth){return clamp(depth,0.0,1.0);}",
            "shaders/composite.vsh","#version 330 compatibility\nout vec2 texcoord; void main(){gl_Position=ftransform();texcoord=gl_MultiTexCoord0.xy;}"));
        Path destination=temp.resolve("modern"); var report=IrisImporter.convert(zip,destination);
        assertTrue(report.runnable(),report.diagnostics().toString());
        String translated=Files.readString(destination.resolve("assets/iris_imported/shaders/core/converted.fsh"));
        assertTrue(translated.contains("DepthSampler")); assertFalse(translated.contains("#include"));
    }
    @Test void unsupportedWorldStagesAndExtraTargetsStayManualAndPreserveSources() throws Exception {
        Path source=directory("complex",Map.of("shaders/final.fsh",LEGACY,
            "shaders/gbuffers_terrain.fsh","#version 430\nlayout(std430,binding=0) buffer Data{float value;}; uniform sampler2D colortex1; void main(){}",
            "shaders/shaders.properties","size.buffer.colortex1=0.5 0.5\n"));
        Path destination=temp.resolve("manual"); var report=IrisImporter.convert(source,destination);
        assertFalse(report.runnable());
        String diagnostics=report.diagnostics().toString();
        assertTrue(diagnostics.contains("gbuffer"),diagnostics); assertTrue(diagnostics.contains("SSBO"),diagnostics);
        assertTrue(diagnostics.contains("colortex1"),diagnostics); assertTrue(diagnostics.contains("shaders.properties"),diagnostics);
        assertTrue(Files.exists(destination.resolve("iris-source/shaders/gbuffers_terrain.fsh")));
        assertFalse(Files.exists(destination.resolve("assets/iris_imported/greenmarine/packs/imported.json")));
    }
    @Test void failedShaderCompilationCreatesManualProjectWithoutRunnableManifest() throws Exception {
        Path source=directory("broken",Map.of("shaders/final.fsh",LEGACY.replace("gl_FragColor=", "invalid syntax; gl_FragColor=")));
        Path destination=temp.resolve("broken-converted"); var report=IrisImporter.convert(source,destination);
        assertFalse(report.runnable()); assertTrue(report.diagnostics().toString().contains("compile"));
        assertFalse(Files.exists(destination.resolve("assets/iris_imported/greenmarine/packs/imported.json")));
    }
    @Test void rejectsTraversalAndArchiveSymlinksWithoutPartialDestination() throws Exception {
        for(Path source:List.of(zip("traversal.zip",Map.of("../escape.glsl","bad","shaders/final.fsh",LEGACY)), symlinkZip())) {
            Path destination=temp.resolve(source.getFileName()+"-converted");
            assertThrows(IOException.class,()->IrisImporter.convert(source,destination)); assertFalse(Files.exists(destination));
        }
        assertFalse(Files.exists(temp.resolve("escape.glsl")));
    }
    @Test void rejectsDirectorySymlinksAndExistingDestination() throws Exception {
        Path source=directory("links",Map.of("shaders/final.fsh",LEGACY));
        Files.createSymbolicLink(source.resolve("shaders/link.glsl"),source.resolve("shaders/final.fsh"));
        Path destination=temp.resolve("linked-converted");
        assertThrows(IOException.class,()->IrisImporter.convert(source,destination)); assertFalse(Files.exists(destination));
        Path valid=directory("valid",Map.of("shaders/final.fsh",LEGACY));
        Files.createDirectory(destination); Files.writeString(destination.resolve("untouched"),"original");
        assertThrows(IOException.class,()->IrisImporter.convert(valid,destination));
        assertEquals("original",Files.readString(destination.resolve("untouched")));
    }
    @Test void enforcesUncompressedSizeAndFileCountLimits() throws Exception {
        var many=new LinkedHashMap<String,String>(); many.put("shaders/final.fsh",LEGACY);
        for(int i=0;i<8192;i++) many.put("shaders/lib/"+i+".glsl","");
        Path tooMany=zip("many.zip",many), tooLarge=zip("large.zip",Map.of("shaders/final.fsh","x".repeat(8*1024*1024+1)));
        int limit=IrisImporter.maxBytes;IrisImporter.maxBytes=8*1024*1024;
        try {
            for(Path source:List.of(tooMany,tooLarge)) {
                Path destination=temp.resolve(source.getFileName()+"-converted");
                assertThrows(IOException.class,()->IrisImporter.convert(source,destination)); assertFalse(Files.exists(destination));
            }
        } finally {IrisImporter.maxBytes=limit;}
    }
    @Test void missingShaderRootLeavesNoImportOrStagingFiles() throws Exception {
        Path source=directory("no-shaders",Map.of("readme.txt","empty")), workspace=Files.createDirectory(temp.resolve("workspace"));
        assertThrows(IOException.class,()->IrisImporter.convert(source,workspace.resolve("converted")));
        try(var files=Files.list(workspace)) {assertEquals(0,files.count());}
    }
    private Path directory(String name,Map<String,String> files) throws IOException {
        Path root=Files.createDirectory(temp.resolve(name));
        for(var entry:files.entrySet()) {Path path=root.resolve(entry.getKey());Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());}
        return root;
    }
    private Path zip(String name,Map<String,String> files) throws IOException {
        Path file=temp.resolve(name);
        try(var output=new ZipOutputStream(Files.newOutputStream(file))) {
            for(var entry:files.entrySet()) {output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));output.closeEntry();}
        }
        return file;
    }
    private Path symlinkZip() throws IOException {
        Path file=zip("symlink.zip",Map.of("shaders/final.fsh",LEGACY));byte[] bytes=Files.readAllBytes(file);
        var words=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<bytes.length-46;i++) if(words.getInt(i)==0x02014b50) {words.putInt(i+38,0120777<<16);break;}
        Files.write(file,bytes); return file;
    }
}
