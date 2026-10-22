package com.koper.greenmarine.api.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;

/** Bounded resource snapshots for independently editable directory or ZIP shaderpacks. */
public final class ExternalPacks {
    private static final int MAX_FILES = 2048;
    private static final int MAX_PACK_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SCAN_BYTES = 32 * 1024 * 1024;
    private ExternalPacks() {}

    /** Sources have private assets, then fall back to mounted engine/game resources. No GPU calls. */
    public static List<PackSource> scan(Path root, Function<String, String> textFallback,
                                        Function<String, byte[]> binaryFallback) {
        Objects.requireNonNull(textFallback); Objects.requireNonNull(binaryFallback);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectLink(root);
        var result = new ArrayList<PackSource>();
        int bytes = 0, packs = 0;
        try (var children = Files.list(root)) {
            for (var child : children.sorted().toList()) {
                rejectLink(child);
                boolean directory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                if (!directory && !child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                if (++packs > 64) throw new IllegalArgumentException("External pack limit exceeded");
                Map<String, byte[]> assets;
                try { assets = directory ? directory(child) : archive(child); }
                catch (IOException | RuntimeException error) {
                    throw new IllegalArgumentException(child.getFileName() + ": " + error.getMessage(), error);
                }
                for (var data : assets.values()) bytes = Math.addExact(bytes, data.length);
                if (bytes > MAX_SCAN_BYTES) throw new IllegalArgumentException("External pack snapshot exceeds 32 MiB");
                var snapshot = Map.copyOf(assets);
                Function<String, String> text = id -> {
                    var data = snapshot.get(id);
                    return data == null ? textFallback.apply(id) : new String(data, StandardCharsets.UTF_8);
                };
                Function<String, byte[]> binary = id -> {
                    var data = snapshot.get(id);
                    return data == null ? binaryFallback.apply(id) : data.clone();
                };
                for (var entry : snapshot.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    String path = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
                    if (path.startsWith("greenmarine/packs/") && path.endsWith(".json")) {
                        if (result.size() >= 64) throw new IllegalArgumentException("External manifest limit exceeded");
                        result.add(new PackSource(child.getFileName() + "!/" + entry.getKey(),
                            new String(entry.getValue(), StandardCharsets.UTF_8), text, binary));
                    }
                }
            }
            return List.copyOf(result);
        } catch (IOException error) { throw new UncheckedIOException("Reading external shaderpacks: " + root, error); }
    }

    private static Map<String, byte[]> directory(Path root) throws IOException {
        var assets = new LinkedHashMap<String, byte[]>();
        int entries = 0, bytes = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next(); rejectLink(file);
                if (++entries > MAX_FILES) throw new IllegalArgumentException("Pack file limit exceeded");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                String id = resourceId(name);
                if (id == null) continue;
                byte[] data;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { data = read(input, name); }
                bytes += data.length;
                if (bytes > MAX_PACK_BYTES) throw new IllegalArgumentException("Pack snapshot exceeds 8 MiB");
                assets.put(id, data);
            }
        }
        return assets;
    }

    private static Map<String, byte[]> archive(Path file) throws IOException {
        var assets = new LinkedHashMap<String, byte[]>();
        var names = new HashSet<String>();
        int entries = 0, bytes = 0;
        try (var zip = new ZipFile(file.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                if (++entries > MAX_FILES) throw new IllegalArgumentException("Pack file limit exceeded");
                String name = entry.getName();
                safePath(entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length()-1) : name);
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate ZIP entry: " + name);
                if (entry.isDirectory()) continue;
                String id = resourceId(name);
                if (id == null) continue;
                byte[] data;
                try (var input = zip.getInputStream(entry)) { data = read(input, name); }
                bytes += data.length;
                if (bytes > MAX_PACK_BYTES) throw new IllegalArgumentException("Pack snapshot exceeds 8 MiB");
                assets.put(id, data);
            }
        }
        return assets;
    }

    private static byte[] read(InputStream input, String name) throws IOException {
        int limit = name.endsWith(".spv") ? ShaderBinary.MAX_BYTES : 262_144;
        if (name.contains("/greenmarine/packs/") || name.endsWith(".lua")) limit = 65_536;
        var data = input.readNBytes(limit + 1);
        if (data.length > limit) throw new IllegalArgumentException("Resource exceeds " + limit + " bytes: " + name);
        return data;
    }

    private static String resourceId(String name) {
        safePath(name);
        if (!name.startsWith("assets/")) return null;
        int slash = name.indexOf('/', 7);
        if (slash < 0) return null;
        String id = name.substring(7, slash) + ":" + name.substring(slash + 1);
        ShaderPack.requireId(id);
        return id;
    }

    private static void safePath(String name) {
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Unsafe pack path: " + name);
        for (var part : name.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("Unsafe pack path: " + name);
    }

    private static void rejectLink(Path path) {
        if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Linked pack paths are unsupported: " + path);
    }
}
