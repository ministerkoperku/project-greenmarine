package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Source-level Iris pipeline inventory. This is authoring metadata, never a claim that a translated
 * pack is executable. Program sets replace, rather than merge with, the root set. Within each set
 * fullscreen passes have numeric order; geometry passes are dispatched by Minecraft, and therefore
 * their position in the inventory does not prescribe their draw order or translucency boundaries.
 * Original properties and preprocessed stage text retain settings not interpreted by this parser.
 */
public record IrisPipelinePlan(List<World> worlds, Map<String, String> dimensionMappings, Map<String, String> optionMacros,
                               String rawProperties, List<Property> propertyEntries,
                               Map<String, String> properties, List<Image> images, List<Buffer> buffers,
                               List<Texture> textures, Map<String, String> renderSettings,
                               Map<String, String> unhandledProperties, List<Diagnostic> diagnostics) {
    public enum Phase { SETUP, BEGIN, SHADOW, SHADOWCOMP, PREPARE, GBUFFERS, DEFERRED, COMPOSITE, FINAL, INTEGRATION, UNKNOWN }
    public record Diagnostic(String code, String path, String message) {}
    /** Entries preserve declaration order and duplicate declarations; properties() contains effective values. */
    public record Property(String key, String value, String line) {}
    public record Constant(String type, String expression) {}
    /** Empty targets means no explicit RENDERTARGETS/DRAWBUFFERS directive, not an empty framebuffer. */
    public record Stage(String path, IrisStage kind, boolean preprocessed, String preprocessedText,
                        List<Integer> targets, Map<String, Constant> constants, Map<String, String> localSize,
                        String diagnostics) {
        public Stage { targets = List.copyOf(targets); constants = immutable(constants); localSize = immutable(localSize); }
    }
    /** computes() execute in listed order before the graphics stages, subject to allowConcurrentCompute. */
    public record Program(String name, Phase phase, int passIndex, boolean enabled, String enableExpression,
                          boolean optionalIntegration, Map<IrisStage, Stage> stages, List<Stage> computes) {
        public Program { stages = immutable(stages); computes = List.copyOf(computes); }
        public boolean hasGraphicsPair() { return stages.containsKey(IrisStage.VERTEX) && stages.containsKey(IrisStage.FRAGMENT); }
        /** Source presence only; this does not validate compilation, binding compatibility or host execution. */
        public boolean hasRequiredSources() { return phase == Phase.SETUP ? !computes.isEmpty() : hasGraphicsPair() || !computes.isEmpty(); }
    }
    public record World(String folder, List<Program> programs) {
        public World { programs = List.copyOf(programs); }
        /** Resolve the whole geometry program fallback; never combine stages of different programs. */
        public Optional<Program> resolve(String requested) {
            var visited = new HashSet<String>();
            for (String name = requested; name != null && visited.add(name); name = FALLBACKS.get(name)) {
                String candidate = name;
                var found = programs.stream().filter(p -> p.name().equals(candidate) && p.enabled() && p.hasRequiredSources()).findFirst();
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }
    }
    /** Dimensions contain one, two or three entries, preserving 1D/2D/3D custom images. */
    public record Image(String name, String sampler, String format, String internalFormat, String pixelType,
                        boolean clear, boolean relative, List<Double> dimensions, String raw) {
        public Image { dimensions = List.copyOf(dimensions); }
    }
    public record Buffer(int binding, long byteSize, boolean relative, double scaleX, double scaleY, String raw) {}
    /** Extra tokens preserve raw binary texture descriptors and resource-pack locations without guessing their format. */
    public record Texture(String key, String scope, String sampler, String path, List<String> extraTokens, String raw) {
        public Texture { extraTokens = List.copyOf(extraTokens); }
    }

    public IrisPipelinePlan {
        worlds = List.copyOf(worlds); dimensionMappings = immutable(dimensionMappings); optionMacros = immutable(optionMacros); propertyEntries = List.copyOf(propertyEntries);
        properties = immutable(properties); images = List.copyOf(images); buffers = List.copyOf(buffers); textures = List.copyOf(textures);
        renderSettings = immutable(renderSettings); unhandledProperties = immutable(unhandledProperties); diagnostics = List.copyOf(diagnostics);
    }

    /** Obtain an override set, or the root set when that dimension has no override. */
    public World world(String folder) {
        return worlds.stream().filter(w -> w.folder().equals(folder)).findFirst()
            .orElseGet(() -> worlds.stream().filter(w -> w.folder().isEmpty()).findFirst().orElseThrow());
    }

    /** Select a program set by dimension registry identifier, then wildcard, then the root set. */
    public World forDimension(String identifier) {
        String id = identifier.contains(":") ? identifier : "minecraft:" + identifier;
        String folder = dimensionMappings.get(id);
        if (folder != null) {
            var selected = worlds.stream().filter(w -> w.folder().equals(folder) && !w.programs().isEmpty()).findFirst();
            if (selected.isPresent()) return selected.get();
        }
        String fallback = dimensionMappings.getOrDefault("*", "");
        return worlds.stream().filter(w -> w.folder().equals(fallback) && !w.programs().isEmpty()).findFirst().orElseGet(() -> world(""));
    }

    private static final Pattern PASS = Pattern.compile("(setup|begin|shadowcomp|prepare|deferred|composite)([0-9]*)");
    private static final Pattern CONSTANT = Pattern.compile("\\bconst\\s+(\\w+)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern COMMENT_CONSTANT = Pattern.compile("(?m)^[ \\t]*const[ \\t]+(\\w+)[ \\t]+(\\w+)[ \\t]*=[ \\t]*([^;\\r\\n]+);");
    private static final Pattern CONSTANT_OR_COMMENT = Pattern.compile("(?m)^\\s*#\\s*pragma\\s+iris_meta_([0-9a-f]+)\\s*$|" + CONSTANT.pattern());
    private static final Pattern COMMENT_MARKER = Pattern.compile("(?m)^[ \\t]*#\\s*pragma\\s+iris_meta_[0-9a-f]+[^\\r\\n]*$");
    private static final Pattern TARGET = Pattern.compile("(?m)^\\s*#\\s*pragma\\s+iris_rt_([RD])_([0-9A-Za-z_]+)\\s*$");
    private static final Pattern LOCAL_SIZE = Pattern.compile("\\b(local_size_[xyz](?:_id)?)\\s*=\\s*([^,)]+)");
    private static final Map<String, String> FALLBACKS = fallbacks();

    public static IrisPipelinePlan parse(IrisSources sources) {
        // Match the existing converter's default option discovery; settings are not executed as a
        // program here. This deliberately exposes the resulting macros for diagnostics/editors.
        var macros = new LinkedHashMap<String, String>();
        IrisCoverage.propertyMacros(sources).forEach((key, value) -> macros.put(key, value.isBlank() ? "1" : value));
        macros.put("true", "1"); macros.put("false", "0");
        var diagnostics = new ArrayList<Diagnostic>();
        diagnostics.add(new Diagnostic("OPTION_DISCOVERY_SCOPE", "", "Default option macros use the converter's settings-named GLSL scan; profile expansion and arbitrary-file option discovery are not implemented."));
        var entries = new ArrayList<Property>();
        String raw = sources.text("shaders.properties").orElse("");
        var effective = parseProperties(raw, macros, entries, diagnostics, "shaders.properties");
        var images = new ArrayList<Image>(); var buffers = new ArrayList<Buffer>(); var textures = new ArrayList<Texture>();
        var render = new LinkedHashMap<String, String>(); var unhandled = new LinkedHashMap<String, String>();
        for (var property : effective.entrySet()) {
            String key = property.getKey(), value = property.getValue();
            try {
                if (key.startsWith("image.")) images.add(image(key.substring(6), value));
                else if (key.startsWith("bufferObject.")) buffers.add(buffer(key.substring(13), value));
                else if (key.startsWith("texture.") || key.startsWith("customTexture.")) textures.add(texture(key, value));
                else if (renderSetting(key)) render.put(key, value);
                else if (!key.startsWith("program.") || !key.endsWith(".enabled")) unhandled.put(key, value);
            } catch (IllegalArgumentException e) {
                unhandled.put(key, value);
                diagnostics.add(new Diagnostic("INVALID_RESOURCE", "shaders.properties", key + ": " + e.getMessage()));
            }
        }
        if (!unhandled.isEmpty()) diagnostics.add(new Diagnostic("UNHANDLED_PROPERTIES", "shaders.properties",
            unhandled.size() + " properties retained without runtime interpretation; inspect unhandledProperties."));

        var folders = new TreeSet<String>(); folders.add("");
        for (String path : sources.textPaths()) {
            int slash = path.indexOf('/');
            if (slash > 0 && path.substring(0, slash).matches("world-?[0-9]+")) folders.add(path.substring(0, slash));
        }
        // dimension.properties supersedes legacy folder-name mappings. A wildcard chooses the base
        // program set; explicit mappings choose complete overrides with no source-stage merging.
        var dimensionEntries = new ArrayList<Property>();
        var dimensions = parseProperties(sources.text("dimension.properties").orElse(""), macros, dimensionEntries, diagnostics, "dimension.properties");
        var dimensionMappings = new LinkedHashMap<String, String>();
        dimensions.forEach((key, value) -> {
            if (!key.startsWith("dimension.")) return;
            String folder = key.substring(10); folders.add(folder);
            for (String id : value.split("\\s+")) if (!id.isBlank())
                dimensionMappings.put(id.equals("*") || id.contains(":") ? id : "minecraft:" + id, folder);
        });
        if (dimensions.isEmpty()) {
            if (folders.contains("world0")) { dimensionMappings.put("minecraft:overworld", "world0"); dimensionMappings.put("*", "world0"); }
            if (folders.contains("world-1")) dimensionMappings.put("minecraft:the_nether", "world-1");
            if (folders.contains("world1")) dimensionMappings.put("minecraft:the_end", "world1");
        }

        var worlds = new ArrayList<World>();
        for (String folder : folders) worlds.add(programSet(sources, folder, effective, macros, diagnostics));
        diagnostics.add(new Diagnostic("INVENTORY_ONLY", "", "Source inventory does not establish runtime support, resource transitions, GPU barriers, geometry draw ordering or host bindings."));
        for (var world : worlds) if (!world.folder().isEmpty() && world.programs().isEmpty())
            diagnostics.add(new Diagnostic("EMPTY_PROGRAM_SET", world.folder(), "No program sources; explicit dimensions mapped here use the wildcard/root fallback. Empty directories are not represented by IrisSources."));
        return new IrisPipelinePlan(worlds, dimensionMappings, macros, raw, entries, effective, images, buffers, textures, render, unhandled, diagnostics);
    }

    private static World programSet(IrisSources sources, String folder, Map<String, String> properties,
                                    Map<String, String> optionMacros, List<Diagnostic> diagnostics) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        var paths = new TreeMap<String, List<String>>();
        for (String path : sources.textPaths()) {
            if (!path.startsWith(prefix)) continue;
            String file = path.substring(prefix.length());
            if (file.contains("/") || IrisStage.of(file) == null) continue;
            String name = file.substring(0, file.lastIndexOf('.'));
            // Only compute stages use letter siblings. These are one program, not separate passes.
            if (file.endsWith(".csh") && name.matches(".+_[a-z]")) name = name.substring(0, name.length() - 2);
            paths.computeIfAbsent(name, k -> new ArrayList<>()).add(path);
        }
        var evaluatorMacros = new LinkedHashMap<String, String>();
        optionMacros.forEach((key, value) -> evaluatorMacros.put(key, value.isBlank() ? "1" : value));
        var evaluator = new MiniPreprocessor(evaluatorMacros);
        var programs = new ArrayList<Program>();
        for (var entry : paths.entrySet()) {
            String name = entry.getKey();
            String expression = properties.getOrDefault("program." + prefix + name + ".enabled", "true");
            boolean enabled = expression.equals("true") || !expression.equals("false") && evaluator.truthy(expression);
            Phase phase = phase(name);
            var stages = new EnumMap<IrisStage, Stage>(IrisStage.class); var computes = new ArrayList<Stage>();
            for (String path : entry.getValue()) {
                Stage stage = stage(sources, path, diagnostics);
                if (stage.kind() == IrisStage.COMPUTE) computes.add(stage); else stages.put(stage.kind(), stage);
            }
            computes.sort(Comparator.comparing(Stage::path));
            var program = new Program(name, phase, passIndex(name), enabled, expression, optionalIntegration(name), stages, computes);
            if (!stages.isEmpty() && !program.hasGraphicsPair()) diagnostics.add(new Diagnostic("MISSING_STAGE_PAIR", prefix + name,
                "Graphics program requires both vertex and fragment source; stages are not borrowed from root or fallback programs."));
            if ((phase == Phase.GBUFFERS || phase == Phase.SHADOW) && !computes.isEmpty()) diagnostics.add(new Diagnostic("INVALID_COMPUTE_STAGE", prefix + name,
                "Geometry programs cannot contain compute stages."));
            if (phase == Phase.SETUP && !stages.isEmpty()) diagnostics.add(new Diagnostic("INVALID_GRAPHICS_STAGE", prefix + name,
                "Setup is compute-only."));
            if (phase == Phase.UNKNOWN) diagnostics.add(new Diagnostic("UNKNOWN_PROGRAM", prefix + name, "Preserved without scheduling semantics."));
            programs.add(program);
        }
        programs.sort(Comparator.comparing(Program::phase).thenComparingInt(Program::passIndex).thenComparing(Program::name));
        return new World(folder, programs);
    }

    private static Stage stage(IrisSources sources, String path, List<Diagnostic> diagnostics) {
        IrisStage kind = IrisStage.of(path);
        try {
            var result = IrisPreprocess.preprocess(markCommentConstants(IrisPreprocess.expandIncludes(sources, path)), kind, path, IrisCoverage.hostMacros());
            if (!result.success()) {
                diagnostics.add(new Diagnostic("STAGE_PREPROCESS_FAILED", path, result.diagnostics()));
                return new Stage(path, kind, false, "", List.of(), Map.of(), Map.of(), result.diagnostics());
            }
            var constants = new LinkedHashMap<String, Constant>();
            var matcher = CONSTANT_OR_COMMENT.matcher(result.text());
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    String[] declaration = new String(HexFormat.of().parseHex(matcher.group(1)), java.nio.charset.StandardCharsets.UTF_8).split(" ", 3);
                    constants.put(declaration[1], new Constant(declaration[0], declaration[2]));
                } else constants.put(matcher.group(3), new Constant(matcher.group(2), matcher.group(4).strip()));
            }
            var size = new LinkedHashMap<String, String>(); matcher = LOCAL_SIZE.matcher(result.text());
            while (matcher.find()) size.put(matcher.group(1), matcher.group(2).strip());
            var targets = new ArrayList<Integer>(); matcher = TARGET.matcher(result.text());
            if (matcher.find()) {
                if (matcher.group(1).equals("R")) for (String token : matcher.group(2).split("_")) targets.add(Integer.parseInt(token));
                else for (char token : matcher.group(2).toCharArray()) {
                    if (token == '_') continue;
                    int index = Character.digit(token, 16);
                    if (index < 0) throw new IllegalArgumentException("Unsupported DRAWBUFFERS token: " + token);
                    targets.add(index);
                }
                if (matcher.find()) diagnostics.add(new Diagnostic("MULTIPLE_TARGET_DIRECTIVES", path,
                    "Multiple active render-target directives retained in preprocessedText; first directive is inventoried."));
            }
            return new Stage(path, kind, true, COMMENT_MARKER.matcher(result.text()).replaceAll(""), targets, constants, size, result.diagnostics());
        } catch (RuntimeException e) {
            diagnostics.add(new Diagnostic("STAGE_PREPROCESS_FAILED", path, e.toString()));
            return new Stage(path, kind, false, "", List.of(), Map.of(), Map.of(), e.toString());
        }
    }

    /** Iris scans line-leading const directives inside block comments, most notably format names
     * which aren't legal GLSL enum tokens. Carry those as opaque pragmas through branch selection;
     * directives inside a comment itself remain inert, just as they do in the original source. */
    private static String markCommentConstants(String source) {
        return BLOCK_COMMENT.matcher(source).replaceAll(block -> {
            var declarations = COMMENT_CONSTANT.matcher(block.group()); var markers = new StringBuilder();
            while (declarations.find()) {
                String declaration = declarations.group(1) + " " + declarations.group(2) + " " + declarations.group(3).strip();
                markers.append("\n#pragma iris_meta_").append(HexFormat.of().formatHex(declaration.getBytes(java.nio.charset.StandardCharsets.UTF_8))).append('\n');
            }
            return java.util.regex.Matcher.quoteReplacement(block.group() + markers);
        });
    }

    private static Map<String, String> parseProperties(String raw, Map<String, String> macros, List<Property> entries,
                                                       List<Diagnostic> diagnostics, String path) {
        var result = new LinkedHashMap<String, String>();
        for (String line : new MiniPreprocessor(macros).process(raw)) {
            if (line.isBlank() || line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!")) continue;
            // java.util.Properties handles escaped separators and Unicode escapes, unlike splitting on '='.
            var parsed = new Properties();
            try { parsed.load(new java.io.StringReader(line)); }
            catch (java.io.IOException | IllegalArgumentException e) {
                diagnostics.add(new Diagnostic("INVALID_PROPERTY", path, line + ": " + e.getMessage())); continue;
            }
            for (String key : parsed.stringPropertyNames()) {
                String value = parsed.getProperty(key).strip();
                entries.add(new Property(key, value, line)); result.put(key, value);
            }
        }
        return result;
    }

    private static Image image(String name, String raw) {
        String[] tokens = tokens(raw);
        if (tokens.length < 7 || tokens.length > 9) throw new IllegalArgumentException("expected sampler, formats, clear/relative flags and 1–3 dimensions");
        boolean relative = bool(tokens[5]); var dimensions = new ArrayList<Double>();
        for (int i = 6; i < tokens.length; i++) {
            double size = Double.parseDouble(tokens[i]);
            if (!Double.isFinite(size) || size <= 0 || (!relative || i == 8) && size != Math.rint(size))
                throw new IllegalArgumentException("invalid image dimension " + tokens[i]);
            dimensions.add(size);
        }
        return new Image(name, tokens[0], tokens[1], tokens[2], tokens[3], bool(tokens[4]), relative, dimensions, raw);
    }

    private static Buffer buffer(String binding, String raw) {
        String[] tokens = tokens(raw); int index = Integer.parseInt(binding); long size = Long.parseLong(tokens[0]);
        if (index < 0 || size < 1 || tokens.length != 1 && tokens.length != 4) throw new IllegalArgumentException("invalid binding, size or relative buffer descriptor");
        boolean relative = tokens.length == 4 && bool(tokens[1]);
        double x = tokens.length == 4 ? Double.parseDouble(tokens[2]) : 1, y = tokens.length == 4 ? Double.parseDouble(tokens[3]) : 1;
        if (!Double.isFinite(x) || !Double.isFinite(y) || x <= 0 || y <= 0) throw new IllegalArgumentException("invalid buffer scale");
        return new Buffer(index, size, relative, x, y, raw);
    }

    private static Texture texture(String key, String raw) {
        var tokens = tokens(raw);
        if (tokens[0].isBlank()) throw new IllegalArgumentException("missing texture path");
        String id = key.substring(key.indexOf('.') + 1), scope = "", sampler = id;
        if (key.startsWith("texture.") && id.contains(".")) { int dot = id.indexOf('.'); scope = id.substring(0, dot); sampler = id.substring(dot + 1); }
        if (key.equals("texture.noise")) sampler = "noisetex";
        return new Texture(key, scope, sampler, tokens[0], Arrays.asList(tokens).subList(1, tokens.length), raw);
    }

    private static boolean renderSetting(String key) {
        return List.of("blend.", "alphaTest.", "scale.", "flip.", "size.buffer.", "backFace.", "shadow.", "frustum.", "occlusion.")
            .stream().anyMatch(key::startsWith)
            || Set.of("allowConcurrentCompute", "separateAo", "oldLighting", "clouds", "sky", "sun", "moon", "stars", "vignette",
                "rain.depth", "beacon.beam.depth", "underwaterOverlay", "dynamicHandLight", "separateEntityDraws", "particles.ordering").contains(key);
    }
    private static String[] tokens(String raw) { return raw.strip().split("\\s+"); }
    private static boolean bool(String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("expected boolean, got " + value);
    }
    private static Phase phase(String name) {
        if (name.startsWith("gbuffers_")) return Phase.GBUFFERS;
        if (name.equals("shadow") || name.startsWith("shadow_")) return Phase.SHADOW;
        if (name.equals("final")) return Phase.FINAL;
        var matcher = PASS.matcher(name);
        if (matcher.matches() && passIndex(name) <= 99) return Phase.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        if (optionalIntegration(name)) return Phase.INTEGRATION;
        return Phase.UNKNOWN;
    }
    private static boolean optionalIntegration(String name) {
        return name.startsWith("clrwl_") || name.startsWith("dh_") || name.startsWith("voxy") || name.startsWith("physics_") || name.endsWith("_voxels");
    }
    private static int passIndex(String name) {
        var matcher = PASS.matcher(name);
        if (!matcher.matches() || matcher.group(2).isEmpty()) return 0;
        try { return Integer.parseInt(matcher.group(2)); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }
    private static <K, V> Map<K, V> immutable(Map<K, V> values) { return Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    private static Map<String, String> fallbacks() {
        // Iris ProgramId's fallback graph; geometry event order remains host-owned.
        var values = new LinkedHashMap<String, String>();
        for (String name : List.of("solid", "cutout", "water", "entities", "block")) values.put("shadow_" + name, "shadow");
        values.put("shadow_lightning", "shadow_entities");
        String[][] groups = {{"basic", "line", "textured", "skybasic"},
            {"textured", "textured_lit", "skytextured", "clouds", "beaconbeam", "armor_glint", "spidereyes"},
            {"textured_lit", "terrain", "item", "entities", "particles", "hand", "weather"},
            {"terrain", "terrain_solid", "terrain_cutout", "damagedblock", "block", "water"},
            {"block", "block_translucent"}, {"entities", "entities_translucent", "lightning", "entities_glowing"},
            {"particles", "particles_translucent"}, {"hand", "hand_water"}};
        for (var group : groups) for (int i = 1; i < group.length; i++) values.put("gbuffers_" + group[i], "gbuffers_" + group[0]);
        return immutable(values);
    }
}
