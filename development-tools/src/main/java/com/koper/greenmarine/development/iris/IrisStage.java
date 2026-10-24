package com.koper.greenmarine.development.iris;

import static org.lwjgl.util.shaderc.Shaderc.*;

public enum IrisStage {
    VERTEX("vsh"), FRAGMENT("fsh"), GEOMETRY("gsh"), TESS_CONTROL("tcs"), TESS_EVALUATION("tes"), COMPUTE("csh");
    public final String extension;
    IrisStage(String extension) { this.extension = extension; }

    public static IrisStage of(String path) {
        for (var stage : values()) if (path.endsWith("." + stage.extension)) return stage;
        return null;
    }
    int shadercKind() {
        return switch (this) {
            case VERTEX -> shaderc_vertex_shader; case FRAGMENT -> shaderc_fragment_shader; case GEOMETRY -> shaderc_geometry_shader;
            case TESS_CONTROL -> shaderc_tess_control_shader; case TESS_EVALUATION -> shaderc_tess_evaluation_shader; case COMPUTE -> shaderc_compute_shader;
        };
    }
}
