package com.koper.greenmarine.development;

import com.koper.greenmarine.api.pack.ShaderBinary;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Developer-only compiler. Compilation returns bytes; callers explicitly choose whether to save them.
 * This validates Vulkan GLSL, without reproducing host rewrites of loose non-opaque uniforms into blocks.
 */
public final class ShaderCompiler {
    public enum Language { GLSL, SLANG }
    public enum Stage { VERTEX, FRAGMENT, COMPUTE }
    public record Result(boolean success, String diagnostics, byte[] spirv) {
        public Result { spirv = spirv.clone(); }
        @Override public byte[] spirv() { return spirv.clone(); }
    }
    private static final int MAX_SOURCE = 2 * 1024 * 1024, MAX_DIAGNOSTICS = 64 * 1024;
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\h*#(?:moj_import|include)\\h+[<\"]([^>\"\\r\\n]+)[>\"]\\h*(?://[^\\r\\n]*)?$");
    private static final Pattern GLSL_330 = Pattern.compile("(?m)^(\\h*#\\h*version\\h+)330\\b");
    private ShaderCompiler() {}

    public static Result compile(Language language, Stage stage, String source, String name, Path slangCompiler) {
        return compile(language, stage, source, name, slangCompiler, id -> null);
    }
    /** GLSL #include and #moj_import use exact identifiers; GLSL 330 is compiled as 450 for Vulkan. No implicit filesystem includes. */
    public static Result compile(Language language, Stage stage, String source, String name, Path slangCompiler,
                                 Function<String, String> includes) {
        if (language == null || stage == null || source == null) return failure("Language, stage and source are required");
        if (source.length() > MAX_SOURCE) return failure("Source exceeds 2 MiB limit");
        try {
            if (language == Language.SLANG) return slang(stage, source, slangCompiler);
            String normalized = GLSL_330.matcher(source).replaceAll(match -> match.group(1) + "450");
            String label = name == null || name.isBlank() ? "developer.glsl" : name;
            var mapped = expandTopLevel(normalized, includes == null ? id -> null : includes);
            var result = glsl(stage, mapped.text(), label);
            return new Result(result.success(), remap(result.diagnostics(), label, mapped), result.spirv());
        } catch (IOException | IllegalArgumentException | LinkageError ex) {
            return failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
    /** origin[i] = editor line of expanded line i+1; includes collapse onto their #include line. */
    private record Mapped(String text, int[] origin, String[] include) {}
    private static Mapped expandTopLevel(String source, Function<String,String> resolver) {
        var output = new StringBuilder(); var origin = new ArrayList<Integer>(); var include = new ArrayList<String>();
        Set<String> active = new HashSet<>(), imported = new HashSet<>();
        String[] lines = source.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            var matcher = IMPORT.matcher(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (!matcher.matches()) {
                output.append(line); if (index + 1 < lines.length) output.append('\n');
                origin.add(index + 1); include.add(null); continue;
            }
            String id = matcher.group(1), body = "";
            if (imported.add(id)) {
                String included = resolver.apply(id);
                if (included == null) throw new IllegalArgumentException("Missing import: " + id + " (line " + (index + 1) + ")");
                if (included.length() > MAX_SOURCE) throw new IllegalArgumentException("Import exceeds source limit: " + id);
                active.add(id);
                body = expand(included, resolver, active, imported, 1);
                active.remove(id);
            }
            int count = (int) body.chars().filter(c -> c == '\n').count() + 1;
            output.append(body).append('\n');
            for (int k = 0; k < count; k++) { origin.add(index + 1); include.add(id); }
            if (output.length() > MAX_SOURCE) throw new IllegalArgumentException("Expanded source exceeds 2 MiB limit");
        }
        return new Mapped(output.toString(), origin.stream().mapToInt(Integer::intValue).toArray(), include.toArray(String[]::new));
    }
    private static String remap(String diagnostics, String label, Mapped mapped) {
        if (diagnostics == null || diagnostics.isEmpty()) return diagnostics;
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(label) + ":(\\d+):").matcher(diagnostics);
        return matcher.replaceAll(match -> {
            int line = Integer.parseInt(match.group(1));
            if (line < 1 || line > mapped.origin().length) return java.util.regex.Matcher.quoteReplacement(match.group());
            String via = mapped.include()[line - 1];
            String location = label + ":" + mapped.origin()[line - 1] + ":";
            return java.util.regex.Matcher.quoteReplacement(via == null ? location : location + " [in " + via + "]");
        });
    }
    private static String expand(String source, Function<String,String> resolver, Set<String> active,
                                 Set<String> imported, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Import nesting exceeds 32 levels");
        var matcher = IMPORT.matcher(source); var output = new StringBuilder(); int start = 0;
        while (matcher.find()) {
            output.append(source, start, matcher.start());
            String id = matcher.group(1);
            if (active.contains(id)) throw new IllegalArgumentException("Recursive import: " + id);
            if (imported.add(id)) {
                String included = resolver.apply(id);
                if (included == null) throw new IllegalArgumentException("Missing import: " + id);
                if (included.length() > MAX_SOURCE) throw new IllegalArgumentException("Import exceeds source limit: " + id);
                active.add(id);
                output.append(expand(included, resolver, active, imported, depth + 1)).append('\n');
                active.remove(id);
            }
            start = matcher.end();
            if (output.length() > MAX_SOURCE) throw new IllegalArgumentException("Expanded source exceeds 2 MiB limit");
        }
        output.append(source, start, source.length());
        if (output.length() > MAX_SOURCE) throw new IllegalArgumentException("Expanded source exceeds 2 MiB limit");
        return output.toString();
    }
    private static Result glsl(Stage stage, String source, String name) {
        long compiler = shaderc_compiler_initialize(), options = 0, result = 0;
        if (compiler == 0) return failure("Shaderc compiler initialization failed");
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0) return failure("Shaderc options initialization failed");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            int kind = switch (stage) { case VERTEX -> shaderc_vertex_shader; case FRAGMENT -> shaderc_fragment_shader; case COMPUTE -> shaderc_compute_shader; };
            result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            if (result == 0) return failure("Shaderc returned no compilation result");
            String diagnostic = shaderc_result_get_error_message(result);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) return failure(diagnostic);
            ByteBuffer nativeBytes = shaderc_result_get_bytes(result);
            if (nativeBytes == null || nativeBytes.remaining() > ShaderBinary.MAX_BYTES) return failure("Shaderc returned invalid binary size");
            byte[] bytes = new byte[nativeBytes.remaining()]; nativeBytes.get(bytes);
            return validated(stage, bytes, diagnostic);
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
    private static Result slang(Stage stage, String source, Path executable) throws IOException {
        if (executable == null || !Files.isRegularFile(executable) || !Files.isExecutable(executable))
            return failure("Slang compiler unavailable: configure an executable compatible with this platform");
        Path directory = Files.createTempDirectory("greenmarine-slang-");
        Process process = null;
        try {
            Path input = directory.resolve("shader.slang"), output = directory.resolve("shader.spv");
            Files.writeString(input, source, StandardCharsets.UTF_8);
            String targetStage = switch(stage) { case VERTEX -> "vertex"; case FRAGMENT -> "fragment"; case COMPUTE -> "compute"; };
            process = new ProcessBuilder(executable.toAbsolutePath().toString(), input.toString(), "-entry", "main", "-stage", targetStage,
                "-target", "spirv", "-profile", "spirv_1_5", "-o", output.toString()).directory(directory.toFile()).redirectErrorStream(true).start();
            Process running = process;
            var diagnosticTask = new FutureTask<String>(() -> {
                try (var stream = running.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int total = 0, count;
                    while ((count = stream.read(buffer)) >= 0) {
                        int keep = Math.min(count, MAX_DIAGNOSTICS - bytes.size());
                        if (keep > 0) bytes.write(buffer, 0, keep);
                        total += count;
                        if (total > MAX_DIAGNOSTICS * 16) { stop(running); return bytes.toString(StandardCharsets.UTF_8) + "\nCompiler output limit exceeded"; }
                    }
                    return bytes.toString(StandardCharsets.UTF_8);
                }
            });
            Thread.ofVirtual().name("greenmarine-slang-diagnostics").start(diagnosticTask);
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) { stop(process); return failure("Slang compiler timed out after 15 seconds"); }
                String diagnostic = diagnosticTask.get(1, TimeUnit.SECONDS);
                if (process.exitValue() != 0) return failure("Slang compiler exited " + process.exitValue() + ": " + diagnostic);
                if (!Files.isRegularFile(output) || Files.size(output) > ShaderBinary.MAX_BYTES) return failure("Slang compiler produced no valid-size SPIR-V output: " + diagnostic);
                return validated(stage, Files.readAllBytes(output), diagnostic);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); return failure("Slang compilation interrupted");
            } catch (ExecutionException | TimeoutException ex) {
                return failure("Cannot read Slang compiler diagnostics: " + ex.getMessage());
            } finally { diagnosticTask.cancel(true); }
        } finally {
            if (process != null) { stop(process); process.getInputStream().close(); }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }
    private static Result validated(Stage stage, byte[] bytes, String diagnostic) {
        if (stage == Stage.FRAGMENT) new ShaderBinary(bytes);
        else {
            if (bytes.length < 20 || bytes.length > ShaderBinary.MAX_BYTES || bytes.length % 4 != 0) return failure("Invalid SPIR-V size");
            var words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int version = words.getInt(4), bound = words.getInt(12);
            if (words.getInt(0) != 0x07230203 || version < 0x10000 || version > 0x10500 || (version & 255) != 0 || bound < 1 || bound > 1_000_000 || words.getInt(16) != 0)
                return failure("Invalid SPIR-V header");
            int expected = stage == Stage.VERTEX ? 0 : 5, entry = 0, function = 0;
            boolean shaderCapability = false, memoryModel = false;
            var completedFunctions = new HashSet<Integer>();
            for (int offset = 20; offset < bytes.length;) {
                int instruction = words.getInt(offset), count = instruction >>> 16, opcode = instruction & 65535;
                if (count == 0 || (long) offset + count * 4L > bytes.length) return failure("Truncated SPIR-V instruction");
                if (opcode == 17) {
                    if (count != 2) return failure("Invalid SPIR-V capability");
                    if (words.getInt(offset + 4) == 1) shaderCapability = true;
                }
                if (opcode == 14) {
                    if (count != 3) return failure("Invalid SPIR-V memory model");
                    memoryModel = true;
                }
                if (opcode == 15) {
                    if (count < 5 || words.getInt(offset + 4) != expected) return failure("SPIR-V entry point stage mismatch");
                    int id = words.getInt(offset + 8);
                    if (id <= 0 || id >= bound) return failure("Invalid SPIR-V entry point ID");
                    if (words.getInt(offset + 12) == 0x6e69616d && words.getInt(offset + 16) == 0) entry = id;
                }
                if (opcode == 54) {
                    if (count != 5 || function != 0) return failure("Invalid SPIR-V function");
                    function = words.getInt(offset + 8);
                    if (function <= 0 || function >= bound) return failure("Invalid SPIR-V function ID");
                }
                if (opcode == 56) {
                    if (count != 1 || function == 0) return failure("Invalid SPIR-V function end");
                    completedFunctions.add(function); function = 0;
                }
                offset += count * 4;
            }
            if (!shaderCapability || !memoryModel || entry == 0 || function != 0 || !completedFunctions.contains(entry))
                return failure("Incomplete " + stage + " SPIR-V module");
        }
        return new Result(true, diagnostic == null ? "" : diagnostic, bytes);
    }
    private static Result failure(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) diagnostic = "Compiler failed without diagnostics";
        if (diagnostic.length() > MAX_DIAGNOSTICS) diagnostic = diagnostic.substring(0, MAX_DIAGNOSTICS) + "\n[diagnostics truncated]";
        return new Result(false, diagnostic, new byte[0]);
    }
}
