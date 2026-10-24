package com.koper.greenmarine.development;

import com.koper.greenmarine.api.material.MaterialAddons;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReloadValidatorTest {
    @TempDir Path root;
    private Path shaders(){return root.resolve("shaderpacks/greenmarine");}
    private Path materials(){return root.resolve("resourcepacks/materials");}
    private ReloadValidator validator(){return new ReloadValidator(shaders(),materials(),id->null,id->null);}
    private Path write(Path path,String text)throws Exception{Files.createDirectories(path.getParent());Files.writeString(path,text);return path;}
    private Path pack()throws Exception{
        write(shaders().resolve("test/assets/demo/greenmarine/packs/main.json"),"{\"schema\":1,\"id\":\"demo:test\",\"passes\":[{\"name\":\"main\",\"fragment\":\"demo:core/main\"}]}");
        return write(shaders().resolve("test/assets/demo/shaders/core/main.fsh"),"#version 450\nlayout(location=0) out vec4 color; void main(){color=vec4(1.0);}");
    }
    private Path material(String surface,String roughness,String schema)throws Exception{
        return write(materials().resolve("assets/demo/greenmarine/materials/main.json"),"{\"schema\":"+schema+",\"id\":\"demo:main\",\"textures\":{\"demo:textures/main.png\":{\"default\":{\"surface\":"+surface+",\"roughness\":"+roughness+",\"reflection\":0.1,\"emission\":0}}}}");
    }
    @Test void compilesOnlyDeclaredStagesAndIgnoresPreservedIrisSources()throws Exception{
        Path shader=pack();
        Path original=write(shaders().resolve("test/iris-source/shaders/final.fsh"),"invalid Iris original");
        Path candidate=write(shaders().resolve("test/conversion-candidate/converted.fsh"),"invalid manual candidate");
        write(shaders().resolve("test/assets/demo/shaders/core/unused.fsh"),"invalid unused source");
        assertNull(validator().validate(List.of(shader,original,candidate)));
    }
    @Test void removedRequiredIncludeIsRejectedEvenWithStaleMountedFallback()throws Exception{
        Path shader=pack();Files.writeString(shader,"#version 450\n#moj_import <demo:value.glsl>\nlayout(location=0) out vec4 color; void main(){color=value();}");
        Path include=write(shaders().resolve("test/assets/demo/shaders/include/value.glsl"),"vec4 value(){return vec4(1.0);}");
        assertNull(validator().validate(List.of(include)));Files.delete(include);
        var validator=new ReloadValidator(shaders(),materials(),id->"vec4 value(){return vec4(1.0);}",id->null);
        assertNotNull(validator.validate(List.of(include)));
    }
    @Test void missingDeclaredEntrypointIsRejectedButRemovedCompletePackIsAllowed()throws Exception{
        Path shader=pack();Files.delete(shader);assertNotNull(validator().validate(List.of(shader)));
        try(var paths=Files.walk(shaders().resolve("test"))){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        assertNull(validator().validate(List.of(shader)));
    }
    @Test void invalidMaterialSchemaAndRangesAreRejectedWithoutChangingGlobalGeneration()throws Exception{
        long generation=MaterialAddons.generation();
        Path json=material("\"matte\"","0.8","1");assertNull(validator().validate(List.of(json)));
        material("\"matte\"","2","1");assertNotNull(validator().validate(List.of(json)));
        material("\"matte\"","0.8","2");assertNotNull(validator().validate(List.of(json)));
        material("\"unknown\"","0.8","1");assertNotNull(validator().validate(List.of(json)));
        assertEquals(generation,MaterialAddons.generation());
    }
    @Test void changedMaskValidatesReferencingMaterialAndMissingMaskIsRejected()throws Exception{
        Path json=write(materials().resolve("assets/demo/greenmarine/materials/main.json"),"{\"schema\":1,\"id\":\"demo:main\",\"textures\":{\"demo:main\":{\"mask\":\"demo:greenmarine/masks/main.png\",\"palette\":{\"ffffff\":{\"surface\":\"metal\",\"roughness\":0.3,\"reflection\":0.9,\"emission\":0}}}}}");
        Path mask=materials().resolve("assets/demo/greenmarine/masks/main.png");Files.createDirectories(mask.getParent());
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1024,1,java.awt.image.BufferedImage.TYPE_INT_ARGB),"PNG",mask.toFile());
        assertNull(validator().validate(List.of(mask)));Files.delete(mask);assertNotNull(validator().validate(List.of(mask)));
        assertNotNull(validator().validate(List.of(json)));
    }
    @Test void validatesPaletteAndPackSelectors()throws Exception{
        Path json=material("\"matte\"","0.8","1");
        Files.writeString(json,Files.readString(json).replace("\"textures\"","\"shaderpacks\":[\"invalid selector\"],\"textures\""));
        assertNotNull(validator().validate(List.of(json)));
    }
    @Test void engineIncludesUseExactMountedResourceId()throws Exception{
        Path shader=pack();Files.writeString(shader,"#version 450\n#include <engine:value.glsl>\nlayout(location=0) out vec4 color; void main(){color=value();}");
        var seen=new ArrayList<String>();
        var validator=new ReloadValidator(shaders(),materials(),id->{seen.add(id);return id.equals("engine:shaders/include/value.glsl")?"vec4 value(){return vec4(1.0);}":null;},id->null);
        assertNull(validator.validate(List.of(shader)));assertTrue(seen.contains("engine:shaders/include/value.glsl"));
    }
}
