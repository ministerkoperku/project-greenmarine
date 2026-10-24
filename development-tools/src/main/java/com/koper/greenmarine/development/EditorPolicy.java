package com.koper.greenmarine.development;

import static com.koper.greenmarine.development.ShaderCompiler.*;

final class EditorPolicy {
    private EditorPolicy() {}
    static boolean canApply(String path, Language language, Stage stage) {
        if (path.endsWith(".json")) return true;
        return language == Language.GLSL && (path.endsWith(".fsh") && stage == Stage.FRAGMENT
            || path.endsWith(".vsh") && stage == Stage.VERTEX);
    }
}
