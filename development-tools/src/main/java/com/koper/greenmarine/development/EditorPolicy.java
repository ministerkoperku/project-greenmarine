package com.koper.greenmarine.development;

import static com.koper.greenmarine.development.ShaderCompiler.*;

final class EditorPolicy {
    private EditorPolicy() {}
    static boolean canApply(String path, Language language, Stage stage) {
        if (path.endsWith(".json")) return true;
        return language == Language.GLSL && (path.endsWith(".fsh") || path.endsWith(".vsh") || path.endsWith(".gsh") || path.endsWith(".tcs") || path.endsWith(".tes"))
            && stage==Stage.forPath(path);
    }
}
