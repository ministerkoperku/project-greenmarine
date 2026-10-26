package com.koper.greenmarine.development;

import com.google.gson.*;
import com.koper.greenmarine.api.material.MaterialAddons;
import com.koper.greenmarine.api.pack.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;
import javax.imageio.ImageIO;

/** Read-only preflight of affected selectable packs and API1 materials. Never publishes runtime state. */
public final class ReloadValidator {
    private final Path shaderRoot, materialRoot;
    private final Map<Path,String> overlays;
    private final Function<String,String> textFallback;
    private final Function<String,byte[]> binaryFallback;
    private static final Set<String> SURFACES = Set.of("opaque","matte","stone","wood","metal","puddle","water","glass","ice","porous","gem","diamond","coated","screen","polished");

    public ReloadValidator(Path shaderRoot, Path materialRoot, Function<String,String> textFallback, Function<String,byte[]> binaryFallback) {
        this(shaderRoot,materialRoot,textFallback,binaryFallback,Map.of());
    }
    public ReloadValidator(Path shaderRoot,Path materialRoot,Function<String,String> textFallback,Function<String,byte[]> binaryFallback,Map<Path,String> overlays){
        var normalized=new HashMap<Path,String>();overlays.forEach((path,text)->normalized.put(path.toAbsolutePath().normalize(),text));this.overlays=Map.copyOf(normalized);
        this.shaderRoot = shaderRoot.toAbsolutePath().normalize(); this.materialRoot = materialRoot.toAbsolutePath().normalize();
        this.textFallback = Objects.requireNonNull(textFallback); this.binaryFallback = Objects.requireNonNull(binaryFallback);
    }
    /** Null means validation passed. Deleting an entire pack is permitted; deleting a referenced source is not. */
    public String validate(List<Path> changed) {
        try {
            var changes = new HashSet<Path>(); var packs = new TreeSet<Path>(); boolean materials = false;
            for (Path raw : changed) {
                Path file = raw.toAbsolutePath().normalize(); changes.add(file);
                if (file.startsWith(shaderRoot) && !file.equals(shaderRoot)) {
                    Path relative = shaderRoot.relativize(file);
                    if (relative.getNameCount() >= 2) packs.add(shaderRoot.resolve(relative.getName(0)));
                }
                if (file.startsWith(materialRoot)) materials = true;
            }
            var budget = new Budget(); int manifests = 0;
            for (Path pack : packs) {
                if (!exists(pack)) continue;
                var resolver = new Resolver(pack, changes, budget);
                for (Path manifest : discover(pack, "packs", 64)) {
                    if (++manifests > 64) throw new IOException("At most 64 shaderpack manifests are supported");
                    String json = resolver.fileText(manifest, 65536);
                    ShaderPack definition = ShaderPack.parse(JsonParser.parseString(json).getAsJsonObject());
                    var stages = new LinkedHashMap<String,ShaderCompiler.Stage>();
                    for (var pass : definition.passes()) {
                        if(pass.stage()==PackPass.Stage.COMPUTE)stages.put(PackCatalog.computeResource(pass.fragment()),ShaderCompiler.Stage.COMPUTE);else fragment(stages, pass.fragment()); if (pass.fallback() != null) fragment(stages, pass.fallback());
                        if (pass.spirv() != null) {
                            try { new ShaderBinary(resolver.binary(pass.spirv())); }
                            catch (RuntimeException error) { throw new IOException(pass.spirv() + ": " + error.getMessage(), error); }
                        }
                    }
                    for (var program : definition.programs()) {
                        fragment(stages, program.fragment()); if (program.surface() != null) fragment(stages, program.surface());
                        if (program.vertex() != null) stages.put(PackCatalog.shaderResource(program.vertex()).replaceFirst("\\.fsh$", ".vsh"), ShaderCompiler.Stage.VERTEX);
                    }
                    for (var stage : stages.entrySet()) {
                        String source = resolver.requireText(stage.getKey(), 262144);
                        String namespace = stage.getKey().substring(0, stage.getKey().indexOf(':'));
                        var result = ShaderCompiler.compile(ShaderCompiler.Language.GLSL, stage.getValue(), source, stage.getKey(), null, id -> {
                            int colon = id.indexOf(':'); String qualified = colon < 0 ? namespace + ":" + id : id;
                            ShaderPack.requireId(qualified); colon = qualified.indexOf(':');
                            return resolver.text(qualified.substring(0, colon + 1) + "shaders/include/" + qualified.substring(colon + 1), 262144);
                        });
                        if (!result.success()) throw new IOException("Compile failed for " + stage.getKey() + ": " + result.diagnostics());
                    }
                    if (definition.script() != null) resolver.requireText(definition.script(), 65536);
                    for (String id : definition.materialAddons()) {
                        try { validateMaterial(id, resolver.requireText(id, 65536), resolver, budget); }
                        catch (IOException | RuntimeException error) { throw new IOException(id + ": " + error.getMessage(), error); }
                    }
                }
            }
            if (materials && exists(materialRoot)) {
                var resolver = new Resolver(materialRoot, changes, budget);
                for (Path file : discover(materialRoot, "materials", 128)) {
                    try { validateMaterial(file.toString(), resolver.fileText(file, 262144), resolver, budget); }
                    catch (IOException | RuntimeException error) { throw new IOException(file + ": " + error.getMessage(), error); }
                }
            }
            return null;
        } catch (IOException | RuntimeException ex) {
            return "Auto reload rejected; last runtime snapshot retained: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }
    private static void fragment(Map<String,ShaderCompiler.Stage> stages, String id) { stages.put(PackCatalog.shaderResource(id), ShaderCompiler.Stage.FRAGMENT); }
    private static final class Budget {
        long bytes, maskPixels;
        void bytes(int count) throws IOException { bytes += count; if (bytes > 32L * 1024 * 1024) throw new IOException("Validation exceeds 32 MiB resource budget"); }
    }
    private final class Resolver {
        private final Path root;
        private final Set<Path> changed;
        private final Budget budget;
        private final Map<String,byte[]> binaries = new HashMap<>();
        private final Map<String,String> texts = new HashMap<>();
        Resolver(Path root, Set<Path> changed, Budget budget) { this.root = root; this.changed = changed; this.budget = budget; }
        Path path(String id) throws IOException {
            ShaderPack.requireId(id); int colon = id.indexOf(':');
            Path path = root.resolve("assets").resolve(id.substring(0,colon)).resolve(id.substring(colon+1)).normalize();
            if (!path.startsWith(root)) throw new IOException("Resource escapes pack: " + id);
            checkLinks(path); return path;
        }
        String fileText(Path file, int limit) throws IOException { return decode(read(file, limit)); }
        byte[] read(Path file, int limit) throws IOException {
            checkLinks(file); byte[] bytes;
            if(overlays.containsKey(file.toAbsolutePath().normalize())){
                bytes=overlays.get(file.toAbsolutePath().normalize()).getBytes(StandardCharsets.UTF_8);if(bytes.length>limit)throw new IOException("Draft exceeds resource limit: "+file);budget.bytes(bytes.length);return bytes;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing regular resource: " + file);
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(limit + 1); }
            if (bytes.length > limit) throw new IOException("Resource exceeds " + limit + " bytes: " + file);
            budget.bytes(bytes.length); return bytes;
        }
        String text(String id, int limit) {
            try {
                if (texts.containsKey(id)) return texts.get(id);
                Path path = path(id); String text;
                if (exists(path)) text = fileText(path, limit);
                else {
                    if (changed.contains(path)) throw new IOException("Deleted required resource: " + id);
                    text = textFallback.apply(id);
                    if (text != null) { int size = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; if (size > limit) throw new IOException("Mounted text exceeds limit: " + id); budget.bytes(size); }
                }
                texts.put(id,text); return text;
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        }
        String requireText(String id, int limit) throws IOException { String value = text(id,limit); if (value == null) throw new IOException("Missing required resource: " + id); return value; }
        byte[] binary(String id) {
            try {
                if (binaries.containsKey(id)) return binaries.get(id);
                Path path = path(id); byte[] bytes;
                if (exists(path)) bytes = read(path,4*1024*1024);
                else {
                    if (changed.contains(path)) throw new IOException("Deleted required mask/binary: " + id);
                    bytes = binaryFallback.apply(id);
                    if (bytes != null) { if (bytes.length > 4*1024*1024) throw new IOException("Mounted binary exceeds 4 MiB: " + id); budget.bytes(bytes.length); }
                }
                binaries.put(id,bytes); return bytes;
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        }
    }
    private static String decode(byte[] bytes) throws IOException {
        try { return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw new IOException("Resource is not valid UTF-8", ex); }
    }
    private static boolean exists(Path path) throws IOException {
        checkLinks(path);
        try { Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException ex) { return false; }
    }
    private static void checkLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Linked resource is unsupported: " + path);
    }
    /** Only actual assets/namespace/greenmarine/type documents, never preserved Iris originals. */
    private static List<Path> discover(Path pack, String type, int limit) throws IOException {
        Path assets = pack.resolve("assets"); if (!exists(assets)) return List.of();
        var result = new ArrayList<Path>(); int[] visits = {0};
        Files.walkFileTree(assets,EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<>() {
            private void bound() throws IOException { if (++visits[0] > 16384) throw new IOException("Resource traversal limit exceeded"); }
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attrs)throws IOException {
                bound();checkLinks(directory);
                return directory.getFileName().toString().startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                bound();Path relative=assets.relativize(file);
                if (relative.getNameCount() >= 4 && relative.getName(1).toString().equals("greenmarine") && relative.getName(2).toString().equals(type) && file.toString().endsWith(".json") && !file.getFileName().toString().startsWith(".")) {
                    checkLinks(file);if (!attrs.isRegularFile()) throw new IOException("Invalid resource file: " + file);
                    result.add(file);if (result.size() > limit) throw new IOException("Too many " + type + " definitions (maximum " + limit + ")");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.naturalOrder());return result;
    }
    /** API1 parsing mirrored here because MaterialAddons exposes no read-only semantic-validation method. */
    private static void validateMaterial(String origin,String json,Resolver resolver,Budget budget)throws IOException {
        // Snapshot checks schema/document bounds and freezes all referenced mask bytes without publishing a catalog.
        var source=MaterialAddons.snapshot(new MaterialAddons.Source(origin,json,resolver::binary,null));
        var root=JsonParser.parseString(source.json()).getAsJsonObject();
        var selectors=new HashSet<String>();
        if(root.has("shaderpacks")) for(var value:root.getAsJsonArray("shaderpacks")){String id=value.getAsString();ShaderPack.requireId(id);selectors.add(id);}
        if(selectors.size()>64)throw new IOException("At most 64 material shaderpack selectors");
        for(var entry:root.getAsJsonObject("textures").entrySet()) {
            ShaderPack.requireId(entry.getKey());var definition=entry.getValue().getAsJsonObject();
            if(definition.has("default"))validateProfile(definition.getAsJsonObject("default"));
            var colors=new HashSet<Integer>();
            if(definition.has("palette"))for(var color:definition.getAsJsonObject("palette").entrySet()){
                String hex=color.getKey().replaceFirst("^#","");if(!hex.matches("[0-9a-fA-F]{6}"))throw new IOException("Palette needs RGB hex colors");
                if(!colors.add(Integer.parseInt(hex,16)))throw new IOException("Duplicate palette color");
                validateProfile(color.getValue().getAsJsonObject());
            }
            if(colors.size()>256)throw new IOException("At most 256 palette colors");
            if(!definition.has("default")&&!definition.has("mask"))throw new IOException("Texture needs default or mask");
            if(definition.has("mask")) {
                String id=definition.get("mask").getAsString();byte[] bytes=source.binaries().apply(id);
                try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
                    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unreadable mask PNG: "+id);
                    var reader=readers.next();try{
                        if(!reader.getFormatName().equalsIgnoreCase("png"))throw new IOException("Mask must be PNG: "+id);
                        reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                        if(width<1||height<1||width>2048||height>2048)throw new IOException("Material dimensions must be 1..2048");
                        budget.maskPixels+=(long)width*height;if(budget.maskPixels>16L*1024*1024)throw new IOException("Material masks exceed 64 MiB decoded budget");
                        reader.read(0);
                    }finally{reader.dispose();}
                }
            }
        }
    }
    private static void validateProfile(JsonObject object)throws IOException {
        var surface=object.get("surface");if(surface==null||!surface.isJsonPrimitive())throw new IOException("Material needs surface");
        if(surface.getAsJsonPrimitive().isNumber()){
            int value=surface.getAsInt();if(value<1||value>9||surface.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(value))!=0)throw new IOException("Surface class must be integer 1..9");
        }else if(!SURFACES.contains(surface.getAsString().toLowerCase(Locale.ROOT)))throw new IOException("Unknown surface class");
        for(String name:List.of("roughness","reflection","emission")){
            var value=object.get(name);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Material needs numeric "+name);
            float number=value.getAsFloat();if(!Float.isFinite(number)||number<0||number>1)throw new IOException("Material "+name+" must be finite 0..1");
        }
    }
}
