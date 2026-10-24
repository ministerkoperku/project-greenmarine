package com.koper.greenmarine.development.iris;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipFile;

/** Text files of an Iris pack keyed by path below {@code shaders/}. Binary assets are listed but not loaded. */
public final class IrisSources {
    private static final int MAX_FILES = 8192, MAX_TEXT = 4 * 1024 * 1024;
    private static final Set<String> TEXT = Set.of("glsl", "fsh", "vsh", "csh", "gsh", "tcs", "tes", "properties", "json", "txt", "lang");
    private final Map<String, String> text;
    private final Map<String, Long> binary;

    private IrisSources(Map<String, String> text, Map<String, Long> binary) { this.text = text; this.binary = binary; }

    public static IrisSources load(Path pack) throws IOException {
        var text = new TreeMap<String, String>(); var binary = new TreeMap<String, Long>();
        if (Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) {
            Path root = Files.isDirectory(pack.resolve("shaders")) ? pack.resolve("shaders") : pack;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) throw new IOException("Symbolic links are not accepted: " + file);
                    add(root.relativize(file).toString().replace('\\', '/'), attrs.size(), () -> Files.readAllBytes(file), text, binary);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else try (var zip = new ZipFile(pack.toFile())) {
            String prefix = zip.stream().map(e -> e.getName()).filter(n -> n.endsWith("shaders/shaders.properties") || n.endsWith("shaders/final.fsh") || n.contains("shaders/world0/"))
                .map(n -> n.substring(0, n.indexOf("shaders/") + 8)).findFirst().orElse("shaders/");
            for (var entry : Collections.list(zip.entries())) {
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                String name = entry.getName().substring(prefix.length());
                if (name.contains("..") || name.startsWith("/")) throw new IOException("Unsafe ZIP entry: " + entry.getName());
                add(name, entry.getSize(), () -> { try (var in = zip.getInputStream(entry)) { return in.readNBytes(MAX_TEXT + 1); } }, text, binary);
            }
        }
        if (text.keySet().stream().noneMatch(n -> n.endsWith(".fsh") || n.endsWith(".csh"))) throw new IOException("No Iris programs found in " + pack);
        return new IrisSources(Collections.unmodifiableMap(text), Collections.unmodifiableMap(binary));
    }
    private interface Bytes { byte[] read() throws IOException; }
    private static void add(String name, long size, Bytes bytes, Map<String, String> text, Map<String, Long> binary) throws IOException {
        if (text.size() + binary.size() >= MAX_FILES) throw new IOException("Pack has more than " + MAX_FILES + " files");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TEXT.contains(extension)) { binary.put(name, size); return; }
        byte[] data = bytes.read();
        if (data.length > MAX_TEXT) throw new IOException("Text file over 4 MiB: " + name);
        text.put(name, new String(data, StandardCharsets.UTF_8));
    }

    public Optional<String> text(String path) { return Optional.ofNullable(text.get(path)); }
    public Set<String> textPaths() { return text.keySet(); }
    public Map<String, Long> binaries() { return binary; }
}
