package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.regex.Pattern;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Iris include rules (absolute from shaders/, else relative to the including file), then glslang's preprocessor. */
public final class IrisPreprocess {
    public record Result(boolean success, String text, String diagnostics) {}
    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]+[\"<]([^\">\\r\\n]+)[\">][^\\r\\n]*$");
    private static final Pattern TARGETS = Pattern.compile("/\\*\\s*(RENDERTARGETS|DRAWBUFFERS)\\s*:\\s*([0-9A-Za-z, ]+?)\\s*\\*/");
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)[^\\r\\n]*$");
    private static final int MAX_EXPANDED = 8 * 1024 * 1024;
    private IrisPreprocess() {}

    public static String expandIncludes(IrisSources sources, String path) {
        return expand(sources, path, sources.text(path).orElseThrow(() -> new IllegalArgumentException("Missing " + path)), 0, new int[1]);
    }
    private static String expand(IrisSources sources, String path, String text, int depth, int[] size) {
        if (depth > 24) throw new IllegalArgumentException("Include nesting over 24 levels at " + path);
        var matcher = INCLUDE.matcher(text); var out = new StringBuilder(); int start = 0;
        String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        while (matcher.find()) {
            out.append(text, start, matcher.start());
            String target = matcher.group(1).strip();
            String resolved = normalize(target.startsWith("/") ? target.substring(1) : directory + target);
            String included = sources.text(resolved).orElseThrow(() -> new IllegalArgumentException("Missing include " + target + " from " + path));
            out.append(expand(sources, resolved, included, depth + 1, size)).append('\n');
            start = matcher.end();
            if ((size[0] += included.length()) > MAX_EXPANDED) throw new IllegalArgumentException("Expanded source over 8 MiB at " + path);
        }
        return out.append(text, start, text.length()).toString();
    }
    static String normalize(String path) {
        var parts = new ArrayDeque<String>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) throw new IllegalArgumentException("Include escapes pack: " + path); parts.removeLast(); }
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }

    /** Comments disappear in glslang's output, so target comments become pragmas that survive #if selection. */
    public static String markTargets(String text) {
        // glslang re-emits pragma tokens without spaces, so the list travels as one identifier
        return TARGETS.matcher(text).replaceAll(m -> "\n#pragma iris_rt_" + m.group(1).charAt(0) + "_" + m.group(2).replace(" ", "").replace(',', '_') + "\n");
    }

    public static Result preprocess(String source, IrisStage stage, String name, Map<String, String> macros) {
        // compatibility isn't a Vulkan profile; translation rewrites the version anyway
        String resolved = new MiniPreprocessor(macros).resolveConditionals(stripComments(markTargets(source)));
        String text = VERSION.matcher(resolved).replaceFirst("#version 460");
        if (!VERSION.matcher(text).find()) text = "#version 460\n" + text;
        long compiler = shaderc_compiler_initialize(), options = 0, result = 0;
        if (compiler == 0) return new Result(false, "", "Shaderc unavailable");
        try {
            options = shaderc_compile_options_initialize();
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            for (var macro : macros.entrySet()) shaderc_compile_options_add_macro_definition(options, macro.getKey(), macro.getValue());
            long finalOptions = options;
            result = NativeText.with(text, name, (buffer, file, entry) -> shaderc_compile_into_preprocessed_text(compiler, buffer, stage.shadercKind(), file, entry, finalOptions));
            String diagnostics = shaderc_result_get_error_message(result);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                return new Result(false, "", diagnostics == null ? "Preprocessing failed" : diagnostics);
            var bytes = shaderc_result_get_bytes(result);
            byte[] data = new byte[bytes.remaining()]; bytes.get(data);
            return new Result(true, new String(data, java.nio.charset.StandardCharsets.UTF_8), diagnostics == null ? "" : diagnostics);
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    /** Directives inside comments must not count; newlines survive so later line numbers stay close. */
    static String stripComments(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    // a backslash-continued // comment swallows the next line too, same as cpp
                    if (text.charAt(i) == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { out.append('\n'); i++; }
                    i++;
                }
                if (i < text.length()) out.append('\n');
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2); end = end < 0 ? text.length() : end + 2;
                for (int k = i; k < end; k++) if (text.charAt(k) == '\n') out.append('\n');
                out.append(' '); i = end - 1;
            } else out.append(c);
        }
        return out.toString();
    }
}
