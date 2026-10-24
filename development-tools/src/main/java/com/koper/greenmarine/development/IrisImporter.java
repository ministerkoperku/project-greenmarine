package com.koper.greenmarine.development;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Bounded source import and conservative fullscreen translation, not an Iris runtime. */
public final class IrisImporter {
    public record Report(boolean runnable, int sourceFiles, List<String> diagnostics, Path destination) {
        public Report { diagnostics = List.copyOf(diagnostics); }
    }
    // real packs ship 20 MB moon textures; the snapshot only lives during one import
    private static final int MAX_FILES = 8192, MAX_TEXT = 256 * 1024;
    static int maxBytes = 256 * 1024 * 1024; // tests lower it instead of allocating 256 MiB
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\h*#include\\h+[\"<]([^\">\\r\\n]+)[\">]\\h*(?://[^\\r\\n]*)?$");
    private static final String FRAGMENT = "assets/iris_imported/shaders/core/converted.fsh";
    private IrisImporter() {}

    /** Destination must be a new project directory chosen inside the caller's editable workspace. */
    public static Report convert(Path source, Path destination) throws IOException {
        Path input = source.toAbsolutePath().normalize(), output = destination.toAbsolutePath().normalize();
        checkAncestors(input); checkAncestors(output);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import destination already exists");
        if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS) && output.startsWith(input))
            throw new IOException("Import destination cannot be inside the original pack");
        var snapshot = snapshot(input);
        if (snapshot.keySet().stream().noneMatch(name -> name.startsWith("shaders/"))) throw new IOException("No Iris shaders/ directory found");
        var diagnostics = new ArrayList<String>();
        diagnostics.add("Source snapshot and limited fullscreen conversion; not full Iris runtime compatibility.");
        var problems = new LinkedHashSet<String>();
        String candidate = snapshot.containsKey("shaders/final.fsh") ? "shaders/final.fsh" :
            snapshot.containsKey("shaders/composite.fsh") ? "shaders/composite.fsh" : null;
        inspect(snapshot, candidate, problems);
        String translated = null;
        if (candidate == null) problems.add("Manual work: no root final.fsh or composite.fsh fullscreen fragment was found.");
        else {
            try {
                String expanded = expand(candidate, snapshot, new HashSet<>(), 0);
                translated = translate(expanded, problems);
                var result = ShaderCompiler.compile(ShaderCompiler.Language.GLSL, ShaderCompiler.Stage.FRAGMENT, translated, candidate, null);
                if (!result.success()) problems.add("Manual work: translated candidate failed Vulkan compile: " + result.diagnostics());
                else diagnostics.add("Translated fullscreen fragment passed Shaderc Vulkan compilation; runtime rendering is unverified.");
            } catch (IOException ex) { problems.add("Manual work: " + ex.getMessage()); }
        }
        boolean runnable = translated != null && problems.isEmpty();
        diagnostics.addAll(problems);
        String coverage = coverage(snapshot);
        if (coverage != null) diagnostics.add(coverage);
        if (runnable) diagnostics.add("Only the single fullscreen effect is translated. Host scene/depth data and color conventions may differ from Iris.");
        else diagnostics.add("Manual conversion required. No selectable GreenMarine pack manifest was generated.");
        createDirectories(output.getParent()); checkAncestors(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".iris-import-");
        try {
            for (var entry : snapshot.entrySet()) write(staging.resolve("iris-source").resolve(entry.getKey()), entry.getValue());
            if (translated != null) write(staging.resolve(runnable ? FRAGMENT : "conversion-candidate/converted.fsh"), translated.getBytes(StandardCharsets.UTF_8));
            if (runnable) {
                String suffix = UUID.randomUUID().toString().replace("-", "");
                write(staging.resolve("assets/iris_imported/greenmarine/packs/imported.json"), ("""
                    {"schema":1,"id":"iris_imported:converted_%s","name":"Imported Iris fullscreen effect",
                     "passes":[{"name":"converted","fragment":"iris_imported:core/converted"}]}
                    """).formatted(suffix).getBytes(StandardCharsets.UTF_8));
            }
            String report = "# Iris import report\n\nRunnable fullscreen candidate: " + runnable + "\nSource files preserved: " + snapshot.size() + "\n\n" +
                String.join("\n\n", diagnostics) + "\n\nOriginal files are under iris-source/. Edit translated GLSL only after reviewing this report.\n" +
                "Format references: https://shaders.properties/current/reference/programs/final/ and https://shaders.properties/current/reference/buffers/colortex/\n";
            write(staging.resolve("IMPORT-REPORT.md"), report.getBytes(StandardCharsets.UTF_8));
            checkAncestors(output);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import destination appeared during conversion");
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            return new Report(runnable, snapshot.size(), diagnostics, output);
        } finally { deleteTree(staging); }
    }
    /** Full-pack IR-1 numbers: what the future Iris runtime can already compile. Never blocks the import. */
    private static String coverage(Map<String, byte[]> snapshot) {
        try {
            var results = com.koper.greenmarine.development.iris.IrisCoverage.run(com.koper.greenmarine.development.iris.IrisSources.of(snapshot), null);
            return "## Iris runtime readiness (IR-1)\n\n```\n" + com.koper.greenmarine.development.iris.IrisCoverage.report(results) + "```\n"
                + "IR-1 means translated to Vulkan GLSL and compiled; it does not mean the program renders yet.";
        } catch (Exception | LinkageError e) { return "IR-1 coverage unavailable: " + e.getMessage(); }
    }
    private static Map<String, byte[]> snapshot(Path input) throws IOException {
        var files = new LinkedHashMap<String, byte[]>(); int[] total = {0};
        if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(input, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) throw new IOException("Symbolic links are not accepted in Iris packs");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Non-regular pack file or directory depth exceeds 64: " + file);
                    String name = safeName(input.relativize(file).toString());
                    checkAncestors(file);
                    try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { add(files, total, name, stream); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source must be an Iris directory or ZIP");
            checkZip(input);
            try (var archive = new ZipFile(input.toFile())) {
                var names = new HashSet<String>();
                for (var entries = archive.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement(); String name = safeName(entry.getName());
                    if (!names.add(name)) throw new IOException("Duplicate ZIP path: " + name);
                    if (entry.isDirectory()) continue;
                    try (var stream = archive.getInputStream(entry)) { add(files, total, name, stream); }
                }
            }
        }
        return files;
    }
    private static void add(Map<String,byte[]> files, int[] total, String name, InputStream stream) throws IOException {
        if (files.size() >= MAX_FILES) throw new IOException("Iris snapshot exceeds 8192 files");
        byte[] bytes = stream.readNBytes(maxBytes - total[0] + 1);
        total[0] += bytes.length;
        if (total[0] > maxBytes) throw new IOException("Iris snapshot exceeds " + (maxBytes >> 20) + " MiB uncompressed");
        files.put(name, bytes);
    }
    private static String safeName(String name) throws IOException {
        if (name == null || name.length() > 1024 || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0)
            throw new IOException("Unsafe pack path");
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isBlank() || Arrays.stream(name.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IOException("Unsafe pack path: " + name);
        return name;
    }
    /** ZipFile does not expose Unix mode bits; inspect central entries before extracting any bytes. */
    private static void checkZip(Path input) throws IOException {
        long size = Files.size(input);
        if (size > 256L * 1024 * 1024 || size < 22) throw new IOException("ZIP size is invalid or exceeds 256 MiB compressed limit");
        try (var file = new RandomAccessFile(input.toFile(), "r")) {
            int tailSize = (int)Math.min(size, 65557); byte[] tail = new byte[tailSize];
            file.seek(size - tailSize); file.readFully(tail); var words = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int end = -1;
            for (int i = tailSize - 22; i >= 0; i--) if (words.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(words.getShort(i + 20)) == tailSize) { end = i; break; }
            if (end < 0 || words.getShort(end + 4) != 0 || words.getShort(end + 6) != 0) throw new IOException("Unsupported ZIP directory");
            int count = Short.toUnsignedInt(words.getShort(end + 10));
            long length = Integer.toUnsignedLong(words.getInt(end + 12)), offset = Integer.toUnsignedLong(words.getInt(end + 16));
            if (count > 8192 || length > 8 * 1024 * 1024 || offset + length > size - tailSize + end || offset == 0xffffffffL)
                throw new IOException("ZIP64, oversized or invalid ZIP directory is unsupported");
            byte[] directory = new byte[(int)length]; file.seek(offset); file.readFully(directory);
            var central = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN); int at = 0;
            for (int i = 0; i < count; i++) {
                if (at + 46 > directory.length || central.getInt(at) != 0x02014b50) throw new IOException("Invalid ZIP central entry");
                long attributes = Integer.toUnsignedLong(central.getInt(at + 38)); int mode = (int)(attributes >>> 16);
                if ((mode & 0170000) == 0120000) throw new IOException("Symbolic links are not accepted in ZIP packs");
                if ((central.getShort(at + 8) & 1) != 0) throw new IOException("Encrypted ZIP packs are unsupported");
                at += 46 + Short.toUnsignedInt(central.getShort(at + 28)) + Short.toUnsignedInt(central.getShort(at + 30)) + Short.toUnsignedInt(central.getShort(at + 32));
                if (at > directory.length) throw new IOException("Truncated ZIP central entry");
            }
            if (at != directory.length) throw new IOException("Unexpected ZIP directory data");
        }
    }
    private static void inspect(Map<String,byte[]> files, String candidate, Set<String> problems) throws IOException {
        for (var entry : files.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("shaders/")) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\.(?:fsh|vsh|gsh|csh|tcs|tes)$")) {
                boolean selectedFragment = name.equals(candidate), selectedVertex = candidate != null && name.equals(candidate.replace(".fsh", ".vsh"));
                if (!selectedFragment && !selectedVertex) problems.add("Manual work: Iris world/gbuffer, extra pass, compute or dimension-specific stage: " + name);
                if (selectedVertex && !standardVertex(decode(entry.getValue()))) problems.add("Manual work: custom fullscreen vertex behavior: " + name);
            }
            if (lower.endsWith("shaders.properties") && !withoutComments(decode(entry.getValue())).replaceAll("(?m)^\\h*#.*$", "").isBlank())
                problems.add("Manual work: shaders.properties options, custom targets/textures or pipeline settings are not translated.");
            if (!lower.matches(".*\\.(?:fsh|vsh|gsh|csh|glsl|tcs|tes)$")) continue;
            String text = withoutComments(decode(entry.getValue()));
            if (Pattern.compile("\\b(?:buffer|[iu]?image\\w*|atomic_uint)\\b").matcher(text).find()) problems.add("Manual work: SSBO/image/atomic storage is unsupported: " + name);
            var buffers = Pattern.compile("\\b(?:colortex|depthtex)[0-9]+\\b").matcher(text);
            while (buffers.find()) if (!Set.of("colortex0", "depthtex0").contains(buffers.group())) problems.add("Manual work: additional buffer " + buffers.group() + " in " + name);
            if (Pattern.compile("\\b(?:colortex[0-9]+(?:Format|Clear|MipmapEnabled)|shadowtex[0-9]+|shadowcolor[0-9]+)\\b").matcher(text).find())
                problems.add("Manual work: custom buffer formats, history or shadow textures are unsupported: " + name);
            var targets = Pattern.compile("(?:DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*\\r\\n]+)").matcher(decode(entry.getValue()));
            while (targets.find()) if (!targets.group(1).strip().equals("0")) problems.add("Manual work: multiple/custom render targets in " + name);
        }
    }
    private static String expand(String name, Map<String,byte[]> files, Set<String> active, int depth) throws IOException {
        if (depth > 32 || !active.add(name)) throw new IOException("Recursive or excessively nested Iris includes: " + name);
        byte[] bytes = files.get(name); if (bytes == null) throw new IOException("Missing Iris include: " + name);
        if (bytes.length > MAX_TEXT) throw new IOException("Fullscreen source exceeds 256 KiB editable limit");
        String source = decode(bytes); var matcher = INCLUDE.matcher(source); var output = new StringBuilder(); int start = 0;
        while (matcher.find()) {
            output.append(source, start, matcher.start()); String include = matcher.group(1);
            if (include.contains("\\") || include.contains(":")) throw new IOException("Unsafe Iris include path");
            Path resolved = include.startsWith("/") ? Path.of("shaders").resolve(include.substring(1)) : Path.of(name).getParent().resolve(include);
            String relative = resolved.normalize().toString();
            if (!relative.startsWith("shaders/")) throw new IOException("Iris include escapes shaders directory");
            output.append(expand(relative, files, active, depth + 1)).append('\n'); start = matcher.end();
            if (output.length() > MAX_TEXT) throw new IOException("Expanded fullscreen source exceeds editable limit");
        }
        output.append(source, start, source.length()); active.remove(name);
        if (output.length() > MAX_TEXT) throw new IOException("Expanded fullscreen source exceeds editable limit");
        return output.toString();
    }
    private static String translate(String source, Set<String> problems) {
        String code = withoutComments(source);
        var uniforms = Pattern.compile("\\buniform\\s+([^;]+);").matcher(code);
        while (uniforms.find()) if (!uniforms.group(1).strip().matches("sampler2D\\s+(?:colortex0|depthtex0)"))
            problems.add("Manual work: unsupported Iris uniform declaration: " + uniforms.group(1).strip());
        var inputs = Pattern.compile("\\b(?:varying|in)\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(code);
        while (inputs.find()) if (!inputs.group(1).equals("vec2") || !Set.of("texcoord", "texCoord").contains(inputs.group(2)))
            problems.add("Manual work: unsupported fullscreen varying: " + inputs.group(2));
        if (code.contains("gl_FragData") || code.contains("gl_FragDepth")) problems.add("Manual work: explicit fragment buffer/depth writes are unsupported.");
        String translated = source.replaceAll("(?m)^\\h*#version[^\\r\\n]*", "#version 450");
        if (!Pattern.compile("(?m)^\\h*#version\\b").matcher(translated).find()) translated = "#version 450\n" + translated;
        translated = translated.replaceAll("\\btexture2D\\b", "texture").replaceAll("\\bcolortex0\\b", "ColorSampler")
            .replaceAll("\\bdepthtex0\\b", "DepthSampler").replaceAll("\\btexcoord\\b", "texCoord")
            .replaceAll("\\b(?:varying|in)\\s+vec2\\s+texCoord\\s*;", "layout(location=0) in vec2 texCoord;");
        if (code.contains("gl_FragColor")) {
            translated = translated.replaceAll("\\bgl_FragColor\\b", "irisFragColor");
            int newline = translated.indexOf('\n');
            translated = translated.substring(0, newline + 1) + "layout(location=0) out vec4 irisFragColor;\n" + translated.substring(newline + 1);
        }
        String stripped = withoutComments(translated);
        var outputs = Pattern.compile("(?:layout\\s*\\(([^)]*)\\)\\s*)?\\bout\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(stripped);
        int count = 0;
        while (outputs.find()) {
            count++;
            if (!outputs.group(2).equals("vec4") || (outputs.group(1) != null && !outputs.group(1).replaceAll("\\s", "").equals("location=0")))
                problems.add("Manual work: fullscreen output must be one vec4 at location 0.");
        }
        if (count != 1) problems.add("Manual work: fullscreen effect requires exactly one color output.");
        return translated;
    }
    private static boolean standardVertex(String source) {
        String body = withoutComments(source).replaceAll("(?m)^\\h*#version[^\\r\\n]*", "").replaceAll("\\s", "");
        return body.matches("(?:(?:varying|out)vec2texcoord;)?voidmain\\(\\)\\{gl_Position=ftransform\\(\\);texcoord=(?:gl_MultiTexCoord0\\.xy|\\(gl_TextureMatrix\\[0\\]\\*gl_MultiTexCoord0\\)\\.xy);\\}");
    }
    private static String withoutComments(String source) { return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\r\\n]*", " "); }
    private static String decode(byte[] source) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString(); }
        catch (CharacterCodingException ex) { throw new IOException("Shader text is not valid UTF-8", ex); }
    }
    private static void write(Path file, byte[] bytes) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, bytes, StandardOpenOption.CREATE_NEW); }
    private static void checkAncestors(Path path) throws IOException {
        Path at = path.getRoot();
        for (Path part : path) { at = at.resolve(part); if (Files.isSymbolicLink(at)) throw new IOException("Symbolic link source/destination ancestry is unsupported: " + at); }
    }
    private static void createDirectories(Path path) throws IOException {
        checkAncestors(path); Path at = path.getRoot();
        for (Path part : path) {
            at = at.resolve(part);
            try { Files.createDirectory(at); } catch (FileAlreadyExistsException ex) { if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import parent is not a directory", ex); }
        }
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: IrisImporter <iris-directory-or-zip> <new-workspace-project-directory>");
        var report = convert(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Destination: " + report.destination() + "\nRunnable fullscreen candidate: " + report.runnable() + "\nPreserved files: " + report.sourceFiles());
        report.diagnostics().forEach(System.out::println);
    }
}
