package com.koper.greenmarine.development;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.koper.greenmarine.development.ShaderCompiler.*;

class EditorPolicyTest {
    @Test void applicationRequiresTheRuntimeFileStage() {
        assertTrue(EditorPolicy.canApply("pack/a.fsh", Language.GLSL, Stage.FRAGMENT));
        assertTrue(EditorPolicy.canApply("pack/a.vsh", Language.GLSL, Stage.VERTEX));
        assertFalse(EditorPolicy.canApply("pack/a.fsh", Language.GLSL, Stage.VERTEX));
        assertFalse(EditorPolicy.canApply("pack/a.vsh", Language.GLSL, Stage.FRAGMENT));
        assertFalse(EditorPolicy.canApply("pack/a.fsh", Language.GLSL, Stage.COMPUTE));
        assertFalse(EditorPolicy.canApply("pack/a.fsh", Language.SLANG, Stage.FRAGMENT));
        assertFalse(EditorPolicy.canApply("pack/a.comp", Language.GLSL, Stage.COMPUTE));
        assertFalse(EditorPolicy.canApply("pack/a.slang", Language.SLANG, Stage.FRAGMENT));
        assertTrue(EditorPolicy.canApply("pack/packs/example.json", Language.GLSL, Stage.FRAGMENT));
    }
}
