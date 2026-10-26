package com.koper.greenmarine.development;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;

/** Editable directory packs confined to the caller's shaderpacks/greenmarine directory. */
public final class ShaderWorkspace {
    public static final int MAX_SOURCE_BYTES = 256 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("fsh", "vsh", "glsl", "slang", "comp", "csh", "json", "lua", "md", "txt", "properties");
    private final Path root;
    public ShaderWorkspace(Path root) { this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); }

    public List<String> files() throws IOException {
        checkAncestors(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workspace root is not a directory");
        var files = new ArrayList<String>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = root.relativize(file);
                if (attrs.isRegularFile() && !attrs.isSymbolicLink() && relative.getNameCount() >= 2 && editable(file))
                    files.add(relative.toString().replace('\\', '/'));
                return files.size() >= 512 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        files.sort(String::compareTo);
        return List.copyOf(files);
    }
    public String read(String path) throws IOException {
        Path file = resolve(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not an editable regular file: " + path);
        byte[] bytes;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
        }
        if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("Source exceeds 256 KiB UTF-8 limit");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw new IOException("Source is not valid UTF-8", ex); }
    }
    public void save(String path, String text) throws IOException {
        Path file = resolve(path); byte[] bytes = encoded(text);
        createDirectories(file.getParent());
        checkAncestors(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Destination is not a regular file");
        Path temporary = Files.createTempFile(file.getParent(), ".greenmarine-edit-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            checkAncestors(file);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public ShaderCompiler.Result saveValidated(String path, String text, Function<String, ShaderCompiler.Result> validator) throws IOException {
        resolve(path); encoded(text);
        ShaderCompiler.Result result = Objects.requireNonNull(validator.apply(text), "Validator result");
        if (result.success()) save(path, text);
        return result;
    }
    public String createExample() throws IOException {
        createDirectories(root);
        int index;
        for (index = 1; index <= 10000; index++) {
            Path directory = root.resolve("workbench-" + index);
            try { Files.createDirectory(directory); break; }
            catch (FileAlreadyExistsException ignored) { /* Preserve existing packs, including incomplete examples. */ }
        }
        if (index > 10000) throw new IOException("No available example pack name");
        String prefix = "workbench-" + index + "/", shader = prefix + "assets/development/shaders/core/preview";
        save(prefix + "assets/development/greenmarine/packs/preview.json", """
            {
              "schema": 1,
              "id": "development:preview_%d",
              "name": "Development Preview %d",
              "passes": [{"name": "preview", "fragment": "development:core/preview"}]
            }
            """.formatted(index, index));
        save(shader + ".vsh", """
            #version 450
            layout(location=0) out vec2 texCoord;
            void main() {
                vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                texCoord = uv;
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            }
            """);
        save(shader + ".fsh", """
            #version 450
            layout(binding=0) uniform sampler2D ColorSampler;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            void main() {
                vec4 scene = texture(ColorSampler, texCoord);
                fragColor = vec4(scene.rgb * vec3(1.02, 1.0, 0.98), scene.a);
            }
            """);
        return shader + ".fsh";
    }
    Path root(){return root;}
    private Path resolve(String path)throws IOException{
        Path file=assetPath(path);if(!editable(file))throw new IOException("Unsupported editable path");return file;
    }
    Path assetPath(String path) throws IOException {
        if (path == null || path.isBlank() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.indexOf(':') >= 0)
            throw new IOException("Invalid relative editable path");
        String[] segments = path.split("/", -1);
        if (segments.length < 2 || Arrays.stream(segments).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IOException("Path must stay within a directory pack");
        Path relative;
        try { relative = Path.of(path); } catch (InvalidPathException ex) { throw new IOException("Invalid editable path", ex); }
        Path file = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !file.startsWith(root)) throw new IOException("Unsupported editable path");
        checkAncestors(file);
        return file;
    }
    void create(String path,String text)throws IOException{
        Path file=resolve(path);byte[] bytes=encoded(text);createDirectories(file.getParent());checkAncestors(file);
        Files.write(file,bytes,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);
    }
    List<String> projectFiles(String project)throws IOException{
        Path directory=assetPath(project+"/.index").getParent();var files=new ArrayList<String>();
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Project directory missing");
        Files.walkFileTree(directory,java.util.EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{
                if(attributes.isRegularFile()&&!attributes.isSymbolicLink())files.add(root.relativize(file).toString().replace('\\','/'));
                if(files.size()>8192)throw new IOException("Project exceeds 8192 files");return FileVisitResult.CONTINUE;
            }
        });files.sort(String::compareTo);return List.copyOf(files);
    }
    void saveAll(Map<String,String> values)throws IOException{saveAll(values,Map.of());}
    void saveAll(Map<String,String> values,Map<String,byte[]> images)throws IOException{
        var payloads=new LinkedHashMap<String,byte[]>();
        for(var entry:values.entrySet()){resolve(entry.getKey());payloads.put(entry.getKey(),encoded(entry.getValue()));}
        for(var entry:images.entrySet()){
            if(!entry.getKey().toLowerCase(Locale.ROOT).endsWith(".png"))throw new IOException("Only PNG image drafts are writable");
            MaterialCanvas.previewPng(entry.getValue());assetPath(entry.getKey());payloads.put(entry.getKey(),entry.getValue());
        }
        var staged=new LinkedHashMap<Path,Path>();var backups=new LinkedHashMap<Path,byte[]>();var committed=new ArrayList<Path>();
        try{
            long bytes=0;
            for(var entry:payloads.entrySet()){
                Path file=assetPath(entry.getKey());byte[] value=entry.getValue();bytes+=value.length;
                if(bytes>32L*1024*1024)throw new IOException("Save exceeds 32 MiB source budget");
                if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Destination is not a regular source: "+file);
                if(Files.exists(file)&&Files.size(file)>4L*1024*1024)throw new IOException("Existing source exceeds limit");
                backups.put(file,Files.exists(file)?Files.readAllBytes(file):null);
                createDirectories(file.getParent());Path next=Files.createTempFile(file.getParent(),".greenmarine-save-",".tmp");staged.put(file,next);Files.write(next,value);
            }
            for(var entry:staged.entrySet()){
                checkAncestors(entry.getKey());Files.move(entry.getValue(),entry.getKey(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);committed.add(entry.getKey());
            }
        }catch(IOException failure){
            java.util.Collections.reverse(committed);
            for(Path file:committed)try{byte[] backup=backups.get(file);if(backup==null)Files.deleteIfExists(file);else Files.write(file,backup,LinkOption.NOFOLLOW_LINKS);}catch(IOException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }finally{for(Path next:staged.values())Files.deleteIfExists(next);}
    }
    private static boolean editable(Path file) {
        String name = file.getFileName().toString(); int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
    private static byte[] encoded(String text) throws IOException {
        if (text == null || text.length() > MAX_SOURCE_BYTES) throw new IOException("Source exceeds 256 KiB UTF-8 limit");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("Source exceeds 256 KiB UTF-8 limit");
        return bytes;
    }
    private static void checkAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not editable: " + current);
        }
    }
    private static void createDirectories(Path path) throws IOException {
        checkAncestors(path);
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try { Files.createDirectory(current); }
            catch (FileAlreadyExistsException ex) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Parent is not a regular directory: " + current, ex);
            }
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link parent: " + current);
        }
    }
}
