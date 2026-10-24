package com.koper.greenmarine.development.iris;

import java.nio.ByteBuffer;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Strict Vulkan GLSL compile (no relaxed rules): what passes here is what a converted pack can ship. */
public final class IrisCompiler {
    public record Result(boolean success, String diagnostics, byte[] spirv) {}
    private IrisCompiler() {}

    public static Result compile(String source, IrisStage stage, String name) {
        long compiler = shaderc_compiler_initialize(), options = 0, result = 0;
        if (compiler == 0) return new Result(false, "Shaderc unavailable", new byte[0]);
        try {
            options = shaderc_compile_options_initialize();
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            long finalOptions = options;
            result = NativeText.with(source, name, (text, file, entry) -> shaderc_compile_into_spv(compiler, text, stage.shadercKind(), file, entry, finalOptions));
            String diagnostics = shaderc_result_get_error_message(result);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                return new Result(false, diagnostics == null ? "Compilation failed" : diagnostics, new byte[0]);
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            byte[] spirv = new byte[bytes.remaining()]; bytes.get(spirv);
            return new Result(true, diagnostics == null ? "" : diagnostics, spirv);
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
