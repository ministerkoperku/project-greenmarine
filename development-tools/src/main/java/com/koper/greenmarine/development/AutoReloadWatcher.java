package com.koper.greenmarine.development;

import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded, polling-only detector. The caller validates shader entrypoints before reloading resources. */
public final class AutoReloadWatcher {
    private static final int MAX_FILES = 2048, MAX_TEXT = 262144, MAX_PNG = 4 * 1024 * 1024;
    private static final long MAX_TOTAL = 32L * 1024 * 1024;
    private static final Set<String> TEXT = Set.of("fsh", "vsh", "glsl", "slang", "comp", "json", "lua", "mcmeta");
    private static final Set<String> SKIP_DIRECTORIES = Set.of("build", "out", "target", "node_modules");
    public record PollResult(boolean changed, List<Path> changedFiles, String diagnostic) {
        public PollResult { changedFiles = List.copyOf(changedFiles); }
    }
    private record Fingerprint(long size, String modified, String fileKey, String sha256) {}
    private final List<Path> roots;
    private Map<Path, Fingerprint> published, pending;

    public AutoReloadWatcher(List<Path> roots) {
        this.roots = roots.stream().map(path -> Objects.requireNonNull(path).toAbsolutePath().normalize()).distinct().toList();
    }

    /** Initial poll establishes a baseline; subsequent changes require two identical complete polls. */
    public synchronized PollResult poll() throws IOException {
        final Map<Path, Fingerprint> snapshot;
        try { snapshot = snapshot(); }
        catch (IOException | IllegalArgumentException ex) {
            pending = null;
            return new PollResult(false, List.of(), "Auto reload waiting: " + ex.getMessage());
        }
        if (published == null) {
            published = snapshot;
            return new PollResult(false, List.of(), "Auto reload baseline ready");
        }
        if (snapshot.equals(published)) {
            pending = null;
            return new PollResult(false, List.of(), "Auto reload: no changes");
        }
        if (!snapshot.equals(pending)) {
            pending = snapshot;
            return new PollResult(false, List.of(), "Auto reload waiting for stable files");
        }
        var changed = new TreeSet<Path>();
        changed.addAll(published.keySet()); changed.addAll(snapshot.keySet());
        changed.removeIf(path -> Objects.equals(published.get(path), snapshot.get(path)));
        published = snapshot; pending = null;
        return new PollResult(true, List.copyOf(changed), "Stable changes detected; validate before reload");
    }

    private Map<Path, Fingerprint> snapshot() throws IOException {
        var files = new TreeMap<Path, Fingerprint>();
        long[] total = {0}; int[] visited = {0};
        for (Path root : roots) {
            for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent())
                if (Files.isSymbolicLink(ancestor)) throw new IOException("Linked watch root is unsupported: " + root);
            final BasicFileAttributes rootAttributes;
            try { rootAttributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) { continue; }
            if (!rootAttributes.isDirectory()) throw new IOException("Watch root is not a directory: " + root);
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
                private void visitBound() throws IOException {
                    if (++visited[0] > 16384) throw new IOException("Watch directory traversal limit exceeded");
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    visitBound();
                    String name = directory.getFileName().toString();
                    return !directory.equals(root) && (name.startsWith(".") || SKIP_DIRECTORIES.contains(name))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    visitBound();
                    String name = file.getFileName().toString();
                    if (name.startsWith(".") || attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    int dot = name.lastIndexOf('.');
                    String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                    if (!TEXT.contains(extension) && !extension.equals("png")) return FileVisitResult.CONTINUE;
                    if (!attrs.isRegularFile()) throw new IOException("Source is not a regular file: " + file);
                    if (files.containsKey(file)) return FileVisitResult.CONTINUE;
                    if (files.size() >= MAX_FILES) throw new IOException("Watch limit exceeds 2048 source files");
                    int limit = extension.equals("png") ? MAX_PNG : MAX_TEXT;
                    if (attrs.size() > limit) throw new IOException("Source exceeds " + limit + " bytes: " + file);
                    byte[] bytes;
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(limit + 1); }
                    if (bytes.length > limit) throw new IOException("Source exceeds " + limit + " bytes: " + file);
                    total[0] += bytes.length;
                    if (total[0] > MAX_TOTAL) throw new IOException("Watch sources exceed 32 MiB");
                    BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!after.isRegularFile() || attrs.size() != bytes.length || attrs.size() != after.size()
                        || !attrs.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(attrs.fileKey(), after.fileKey()))
                        throw new IOException("Source changed during read: " + file);
                    if (extension.equals("png")) validatePng(bytes, file);
                    else {
                        final String text;
                        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString(); }
                        catch (CharacterCodingException ex) { throw new IOException("Invalid UTF-8: " + file, ex); }
                        if (extension.equals("json") || extension.equals("mcmeta")) validateJson(text, file);
                    }
                    files.put(file, new Fingerprint(after.size(), after.lastModifiedTime().toString(), String.valueOf(after.fileKey()), hash(bytes)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException ex) throws IOException {
                    throw new IOException("Cannot read watched source: " + file, ex);
                }
            });
        }
        return Collections.unmodifiableMap(files);
    }

    private static void validateJson(String text, Path file) throws IOException {
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() == JsonToken.END_DOCUMENT) throw new IOException("Empty JSON: " + file);
            JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON content: " + file);
        } catch (RuntimeException ex) { throw new IOException("Invalid JSON: " + file, ex); }
    }

    /** Check chunk bounds, CRCs, dimensions and completion before a partial PNG can trigger reload. */
    private static void validatePng(byte[] bytes, Path file) throws IOException {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (bytes.length < 33 || buffer.getLong(0) != 0x89504e470d0a1a0aL || buffer.getInt(8) != 13
            || buffer.getInt(12) != 0x49484452) throw new IOException("Invalid PNG header: " + file);
        int width = buffer.getInt(16), height = buffer.getInt(20);
        if (width < 1 || height < 1 || width > 256 || height > 256) throw new IOException("PNG dimensions must be 1..256: " + file);
        boolean data = false, end = false;
        for (int offset = 8; offset < bytes.length;) {
            if (bytes.length - offset < 12) throw new IOException("Partial PNG chunk: " + file);
            int length = buffer.getInt(offset), type = buffer.getInt(offset + 4);
            if (length < 0 || (long) offset + length + 12 > bytes.length) throw new IOException("Partial PNG data: " + file);
            var crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            if ((int) crc.getValue() != buffer.getInt(offset + length + 8)) throw new IOException("Invalid PNG CRC: " + file);
            if (type == 0x49444154) data = true;
            if (type == 0x49454e44) {
                if (length != 0 || offset + 12 != bytes.length) throw new IOException("Invalid PNG end: " + file);
                end = true;
            }
            offset += length + 12;
        }
        if (!data || !end) throw new IOException("Incomplete PNG: " + file);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
}
