package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Links all preprocessed stages of one Iris program before compiling them separately to SPIR-V.
 * Locations and descriptor bindings are program ABI, never stage-local compiler guesses.
 * Unsupported or incompatible user interfaces throw {@link IllegalArgumentException}.
 */
public final class IrisProgramLinker {
    public record Varying(IrisStage producer, IrisStage consumer, String name, String type,
                          String array, int location, int locations) {}
    public record ResourceBlock(String storage, String name, String declaration, int binding) {}
    public record Program(Map<IrisStage, IrisTranslator.Translation> stages,
                          List<IrisTranslator.Uniform> uniforms, List<IrisTranslator.Opaque> opaques,
                          List<Varying> varyings, List<ResourceBlock> blocks) {}
    private static final List<IrisStage> GRAPHICS = List.of(IrisStage.VERTEX, IrisStage.TESS_CONTROL,
        IrisStage.TESS_EVALUATION, IrisStage.GEOMETRY, IrisStage.FRAGMENT);
    private static final Pattern BLOCK = Pattern.compile("layout\\(std140, set = 0, binding = 0\\) uniform IrisUniforms \\{[^}]*};\\s*");
    private static final Pattern LAYOUT = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)");
    private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)\\b");
    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)\\b");
    private static final Pattern SET = Pattern.compile("\\bset\\s*=\\s*(\\d+)\\b");
    private static final Set<String> QUALIFIERS = Set.of("in", "out", "flat", "smooth", "noperspective", "centroid",
        "sample", "patch", "invariant", "precise", "highp", "mediump", "lowp");
    private IrisProgramLinker() {}

    record VertexAttribute(int start, int end, String name, String type, String array) {}
    /** Linked declarations are split into one variable each; reuse the linker's top-level parser. */
    static List<VertexAttribute> vertexAttributes(String linkedVertex) {
        var result = new ArrayList<VertexAttribute>();
        for (var declaration : declarations(linkedVertex, IrisStage.VERTEX)) {
            if (!declaration.direction.equals("in")) continue;
            if (declaration.variables.size() != 1) throw invalid("terrain adapter requires linked vertex declarations");
            var variable = declaration.variables.getFirst();
            result.add(new VertexAttribute(declaration.start, declaration.end, variable.name, variable.type, variable.array));
        }
        return List.copyOf(result);
    }

    /**
     * Input must already have includes/macros expanded, as for {@link IrisTranslator#translate}.
     * Compute programs contain one stage; graphics programs require a vertex stage. A fragment stage
     * may be omitted for depth-only rendering. Tessellation stages must be supplied as a pair.
     * All descriptors use set zero. Loose values share binding zero; named blocks and opaque
     * uniforms retain compatible explicit bindings, with unbound named blocks allocated from 100.
     * Interface arrays require literal extents after preprocessing. Nested interface structures,
     * member locations, component packing and transform feedback layouts are rejected explicitly.
     * Shared resource struct graphs must have matching reachable definitions in every owning stage;
     * cycles, unknown member types and nonliteral struct array extents are rejected explicitly.
     * This method validates source interfaces; callers must still compile every returned stage.
     */
    public static Program link(Map<IrisStage, String> preprocessed) {
        if (preprocessed.isEmpty()) throw invalid("program has no stages");
        if (preprocessed.containsKey(IrisStage.COMPUTE) && preprocessed.size() != 1)
            throw invalid("compute and graphics stages cannot share a program");
        if (!preprocessed.containsKey(IrisStage.COMPUTE) && !preprocessed.containsKey(IrisStage.VERTEX))
            throw invalid("graphics program requires a vertex stage");
        if (preprocessed.containsKey(IrisStage.TESS_CONTROL) != preprocessed.containsKey(IrisStage.TESS_EVALUATION))
            throw invalid("tessellation control and evaluation stages must be supplied together");
        var translations = new EnumMap<IrisStage, IrisTranslator.Translation>(IrisStage.class);
        preprocessed.forEach((stage, source) -> translations.put(stage, IrisTranslator.translate(source, stage)));
        var uniforms = new TreeMap<String, IrisTranslator.Uniform>();
        for (var translation : translations.values()) for (var uniform : translation.uniforms()) {
            var previous = uniforms.putIfAbsent(uniform.name(), uniform);
            if (previous != null && !previous.equals(uniform)) throw invalid("incompatible uniform " + uniform.name());
        }
        var blockByName = new TreeMap<String, ResourceBlock>();
        var stageBlocks = new EnumMap<IrisStage, List<ResourceBlock>>(IrisStage.class);
        for (var entry : translations.entrySet()) {
            var blocks = resourceBlocks(entry.getValue().text()); stageBlocks.put(entry.getKey(), blocks);
            for (var block : blocks) {
                var previous = blockByName.putIfAbsent(block.name(), block);
                if (previous != null && (!blockSignature(previous).equals(blockSignature(block)) || previous.binding() >= 0 && block.binding() >= 0 && previous.binding() != block.binding()))
                    throw invalid("incompatible resource block " + block.name());
                if (previous != null && previous.binding() < 0 && block.binding() >= 0) blockByName.put(block.name(), block);
            }
        }
        validateResourceStructs(translations, uniforms.values(), stageBlocks);
        var opaqueByName = new TreeMap<String, IrisTranslator.Opaque>();
        var explicitBindings = new HashMap<String, Integer>();
        var occupiedBindings = new HashSet<Integer>(); occupiedBindings.add(IrisTranslator.BLOCK_BINDING);
        var blockBindingOwners = new HashMap<Integer, String>();
        for (var block : blockByName.values()) if (block.binding() >= 0) {
            if (occupiedBindings.contains(block.binding()) || blockBindingOwners.putIfAbsent(block.binding(), block.name()) != null)
                throw invalid("resource block binding collision at " + block.binding() + ": " + block.name());
            occupiedBindings.add(block.binding());
        }
        for (var translation : translations.values()) {
            for (var opaque : translation.opaques()) {
                if (uniforms.containsKey(opaque.name())) throw invalid("uniform changes between opaque and value: " + opaque.name());
                var previous = opaqueByName.putIfAbsent(opaque.name(), opaque);
                if (previous != null && (!previous.type().equals(opaque.type()) || !opaqueArray(previous).equals(opaqueArray(opaque)) || !opaqueLayout(previous).equals(opaqueLayout(opaque))))
                    throw invalid("incompatible opaque uniform " + opaque.name());
                var set = SET.matcher(opaque.declaration());
                if (set.find() && !set.group(1).equals("0")) throw invalid("only descriptor set 0 is supported: " + opaque.name());
                if (opaque.binding() < 0) {
                    var binding = BINDING.matcher(opaque.declaration());
                    if (!binding.find()) throw invalid("missing explicit binding: " + opaque.name());
                    int value = Integer.parseInt(binding.group(1));
                    Integer old = explicitBindings.putIfAbsent(opaque.name(), value);
                    if (old != null && old != value) throw invalid("incompatible explicit binding: " + opaque.name());
                }
            }
        }
        var bindingOwners = new HashMap<Integer, String>();
        for (var entry : explicitBindings.entrySet()) {
            String old = bindingOwners.putIfAbsent(entry.getValue(), entry.getKey());
            if (old != null || occupiedBindings.contains(entry.getValue())) throw invalid("descriptor binding collision at " + entry.getValue() + ": " + entry.getKey());
            occupiedBindings.add(entry.getValue());
        }
        var linkedOpaques = new TreeMap<String, IrisTranslator.Opaque>();
        int nextBinding = IrisTranslator.FIRST_OPAQUE_BINDING;
        for (var entry : opaqueByName.entrySet()) {
            while (occupiedBindings.contains(nextBinding)) nextBinding++;
            int binding = explicitBindings.getOrDefault(entry.getKey(), nextBinding);
            occupiedBindings.add(binding);
            var opaque = entry.getValue();
            linkedOpaques.put(entry.getKey(), new IrisTranslator.Opaque(rebind(opaque.declaration(), binding), opaque.type(), opaque.name(), binding));
        }
        var linkedBlocks = new TreeMap<String, ResourceBlock>();
        int nextBlockBinding = IrisTranslator.SSBO_BINDING_BASE;
        for (var block : blockByName.values()) {
            while (occupiedBindings.contains(nextBlockBinding)) nextBlockBinding++;
            int binding = block.binding() >= 0 ? block.binding() : nextBlockBinding;
            occupiedBindings.add(binding);
            linkedBlocks.put(block.name(), new ResourceBlock(block.storage(), block.name(), bindBlock(block, binding), binding));
        }
        var sources = new EnumMap<IrisStage, String>(IrisStage.class);
        var interfaces = new EnumMap<IrisStage, List<Declaration>>(IrisStage.class);
        for (var entry : translations.entrySet()) {
            String source = entry.getValue().text();
            for (var block : stageBlocks.get(entry.getKey())) source = source.replace(block.declaration(), bindBlock(block, linkedBlocks.get(block.name()).binding()));
            for (var opaque : entry.getValue().opaques()) source = source.replace(opaque.declaration(), rebind(opaque.declaration(), linkedOpaques.get(opaque.name()).binding()));
            source = sharedBlock(source, uniforms.values());
            sources.put(entry.getKey(), source);
            interfaces.put(entry.getKey(), declarations(source, entry.getKey()));
        }
        var varyings = new ArrayList<Varying>();
        var chain = GRAPHICS.stream().filter(translations::containsKey).toList();
        for (int i = 1; i < chain.size(); i++) {
            IrisStage producer = chain.get(i - 1), consumer = chain.get(i);
            connect(variables(interfaces.get(producer), "out"), variables(interfaces.get(consumer), "in"), producer, consumer, varyings);
        }
        // Attributes, fragment outputs and unused producer outputs also need explicit locations.
        for (var stage : interfaces.values()) for (String direction : List.of("in", "out")) allocate(variables(stage, direction));
        var result = new EnumMap<IrisStage, IrisTranslator.Translation>(IrisStage.class);
        for (var entry : translations.entrySet()) {
            var original = entry.getValue(); var source = new StringBuilder(sources.get(entry.getKey()));
            var declarations = interfaces.get(entry.getKey());
            for (int i = declarations.size() - 1; i >= 0; i--) {
                var declaration = declarations.get(i); var replacement = new StringBuilder();
                for (var variable : declaration.variables) {
                    String prefix = declaration.prefix;
                    if (declaration.explicitLocation == null) prefix = "layout(location = " + variable.location + ") " + prefix;
                    else if (declaration.variables.size() > 1) prefix = replaceLocation(prefix, variable.location);
                    replacement.append(prefix).append(declaration.type).append(' ').append(variable.name).append(variable.array).append(";\n");
                }
                source.replace(declaration.start, declaration.end, replacement.toString());
            }
            var stageOpaques = original.opaques().stream().map(o -> new IrisTranslator.Opaque(rebind(o.declaration(), linkedOpaques.get(o.name()).binding()), o.type(), o.name(), linkedOpaques.get(o.name()).binding())).toList();
            result.put(entry.getKey(), new IrisTranslator.Translation(source.toString(), original.targets(), List.copyOf(uniforms.values()), stageOpaques, original.notes()));
        }
        return new Program(Collections.unmodifiableMap(result), List.copyOf(uniforms.values()), List.copyOf(linkedOpaques.values()), List.copyOf(varyings), List.copyOf(linkedBlocks.values()));
    }

    private record StructMember(String type, String name, String array, String qualifiers) {}

    /** Compare the complete reachable type graph, rather than trusting same-named GLSL types. */
    private static void validateResourceStructs(Map<IrisStage, IrisTranslator.Translation> stages,
                                               Collection<IrisTranslator.Uniform> uniforms,
                                               Map<IrisStage, List<ResourceBlock>> blocks) {
        var validator = new StructValidator(stages);
        for (var uniform : uniforms) if (!builtinResourceType(uniform.type())) {
            checkedStructArray(uniform.array(), false, uniform.name());
            validator.compare(uniform.type(), stages.keySet());
        }
        var owners = new TreeMap<String, Set<IrisStage>>();
        var representatives = new TreeMap<String, ResourceBlock>();
        blocks.forEach((stage, resources) -> resources.forEach(block -> {
            owners.computeIfAbsent(block.name(), ignored -> EnumSet.noneOf(IrisStage.class)).add(stage);
            representatives.putIfAbsent(block.name(), block);
        }));
        for (var entry : representatives.entrySet()) {
            var block = entry.getValue(); String raw = block.declaration();
            for (var member : structMembers(raw.substring(raw.indexOf('{') + 1, raw.lastIndexOf('}')), block.name(), false))
                if (!builtinResourceType(member.type())) {
                    checkedStructArray(member.array(), block.storage().equals("buffer"), block.name() + "." + member.name());
                    validator.compare(member.type(), owners.get(entry.getKey()));
                }
        }
    }
    private static boolean builtinResourceType(String type) {
        return type.matches("(?:bool|int|uint|float|double|[biud]?vec[234]|d?mat[234](?:x[234])?|(?:u?int|float)(?:8|16|32|64)_t|[iuf](?:8|16|32|64)vec[234]|f(?:16|32|64)mat[234](?:x[234])?)");
    }
    private static List<StructMember> structMembers(String body, String context, boolean strictArrays) {
        if (body.contains("{") || body.contains("}")) throw invalid("nested inline struct definitions are unsupported: " + context);
        var members = new ArrayList<StructMember>(); var names = new HashSet<String>();
        for (String statement : body.split(";", -1)) {
            if (statement.isBlank()) continue;
            if (!body.stripTrailing().endsWith(";")) throw invalid("unterminated struct member: " + context);
            String declaration = statement.strip(); var qualifiers = new ArrayList<String>();
            var layouts = LAYOUT.matcher(declaration);
            while (layouts.find()) for (String item : layouts.group(1).split(",")) qualifiers.add("layout:" + item.replaceAll("\\s+", ""));
            declaration = LAYOUT.matcher(declaration).replaceAll(" ").strip();
            var words = Pattern.compile("(\\w+)\\s+(.*)", Pattern.DOTALL).matcher(declaration);
            while (words.matches() && Set.of("highp", "mediump", "lowp", "coherent", "volatile", "restrict", "readonly", "writeonly", "precise").contains(words.group(1))) {
                qualifiers.add(words.group(1)); declaration = words.group(2).strip(); words = Pattern.compile("(\\w+)\\s+(.*)", Pattern.DOTALL).matcher(declaration);
            }
            if (!words.matches()) throw invalid("unsupported struct member declaration: " + context + ": " + statement.strip());
            String type = words.group(1), declarators = words.group(2); qualifiers.sort(String::compareTo);
            for (String declarator : declarators.split(",", -1)) {
                var parsed = Pattern.compile("\\s*(\\w+)\\s*((?:\\[[^]]*]\\s*)*)\\s*").matcher(declarator);
                if (!parsed.matches()) throw invalid("unsupported struct member declaration: " + context + ": " + declarator.strip());
                String name = parsed.group(1), array = parsed.group(2).replaceAll("\\s+", "");
                if (!names.add(name)) throw invalid("duplicate struct member: " + context + "." + name);
                if (strictArrays) array = checkedStructArray(array, false, context + "." + name);
                members.add(new StructMember(type, name, array, String.join(",", qualifiers)));
            }
        }
        if (members.isEmpty()) throw invalid("empty struct/resource block: " + context);
        return members;
    }
    private static String checkedStructArray(String shape, boolean runtimeArray, String context) {
        var result = new StringBuilder(); var dimensions = Pattern.compile("\\[([^]]*)]").matcher(shape);
        while (dimensions.find()) {
            String extent = dimensions.group(1);
            if (extent.isEmpty() && runtimeArray) { result.append("[]"); runtimeArray = false; continue; }
            if (!extent.matches("[1-9][0-9]*[uU]?")) throw invalid("struct array needs a positive literal extent: " + context + shape);
            try { result.append('[').append(Integer.parseInt(extent.replaceAll("[uU]$", ""))).append(']'); }
            catch (NumberFormatException error) { throw invalid("struct array extent overflows: " + context + shape); }
        }
        return result.toString();
    }
    private static final class StructValidator {
        private final Map<IrisStage, Map<String, List<String>>> definitions = new EnumMap<>(IrisStage.class);
        private final Map<IrisStage, Map<String, String>> signatures = new EnumMap<>(IrisStage.class);
        StructValidator(Map<IrisStage, IrisTranslator.Translation> stages) {
            stages.forEach((stage, source) -> {
                var types = new HashMap<String, List<String>>(); definitions.put(stage, types); signatures.put(stage, new HashMap<>());
                walkStatements(source.text(), (start, end) -> {
                    String statement = source.text().substring(start, end).strip();
                    var definition = Pattern.compile("(?s)^struct\\s+(\\w+)\\s*\\{(.*)}[^;]*;\\s*$").matcher(statement);
                    if (definition.matches()) types.computeIfAbsent(definition.group(1), ignored -> new ArrayList<>()).add(definition.group(2));
                });
            });
        }
        void compare(String type, Collection<IrisStage> stages) {
            String expected = null; IrisStage first = null;
            for (var stage : stages) {
                String actual = signature(type, stage, new LinkedHashSet<>());
                if (expected == null) { expected = actual; first = stage; }
                else if (!expected.equals(actual)) throw invalid("incompatible struct " + type + " between " + first + " and " + stage);
            }
        }
        private String signature(String type, IrisStage stage, Set<String> visiting) {
            if (builtinResourceType(type)) return type;
            String cached = signatures.get(stage).get(type); if (cached != null) return cached;
            if (!visiting.add(type)) throw invalid("recursive struct type in " + stage + ": " + String.join(" -> ", visiting) + " -> " + type);
            var candidates = definitions.get(stage).get(type);
            if (candidates == null) throw invalid("missing struct definition " + type + " in " + stage);
            if (candidates.size() != 1) throw invalid("duplicate struct definition " + type + " in " + stage);
            var result = new StringBuilder(type).append('{');
            for (var member : structMembers(candidates.getFirst(), type, true))
                result.append(member.qualifiers()).append('|').append(signature(member.type(), stage, visiting)).append(' ').append(member.name()).append(member.array()).append(';');
            visiting.remove(type); String value = result.append('}').toString(); signatures.get(stage).put(type, value); return value;
        }
    }

    private static List<ResourceBlock> resourceBlocks(String source) {
        var result = new ArrayList<ResourceBlock>();
        walkStatements(source, (start, end) -> {
            String raw = source.substring(start, end).strip();
            var parsed = Pattern.compile("(?s)^((?:(?:layout\\s*\\([^)]*\\)|readonly|writeonly|coherent|volatile|restrict)\\s*)*)(uniform|buffer)\\s+(\\w+)\\s*\\{(.*)}([^;]*);$").matcher(raw);
            if (!parsed.matches() || parsed.group(3).equals("IrisUniforms")) return;
            var set = SET.matcher(parsed.group(1));
            if (set.find() && !set.group(1).equals("0")) throw invalid("only descriptor set 0 is supported: " + parsed.group(3));
            var binding = BINDING.matcher(parsed.group(1));
            result.add(new ResourceBlock(parsed.group(2), parsed.group(3), raw, binding.find() ? Integer.parseInt(binding.group(1)) : -1));
        });
        return result;
    }
    private static String blockSignature(ResourceBlock block) {
        String raw = block.declaration(); int open = raw.indexOf('{'), close = raw.lastIndexOf('}');
        var arrays = Pattern.compile("\\[[^]]*]").matcher(raw.substring(close + 1)); var shape = new StringBuilder();
        while (arrays.find()) shape.append(arrays.group());
        var layout = new ArrayList<String>(); var matcher = LAYOUT.matcher(raw.substring(0, open));
        while (matcher.find()) for (String item : matcher.group(1).split(",")) {
            String token = item.replaceAll("\\s+", "");
            if (!token.startsWith("binding=") && !token.startsWith("set=")) layout.add(token);
        }
        if (!layout.contains("std140") && !layout.contains("std430") && !layout.contains("scalar")) layout.add(block.storage().equals("uniform") ? "std140" : "std430");
        layout.sort(String::compareTo);
        return block.storage() + layout + raw.substring(open, close + 1).replaceAll("\\s+", "") + shape.toString().replaceAll("\\s+", "");
    }
    private static String bindBlock(ResourceBlock block, int binding) {
        String raw = block.declaration(); int open = raw.indexOf('{');
        var prefix = raw.substring(0, open); var explicit = BINDING.matcher(prefix);
        if (explicit.find()) return explicit.replaceAll("binding = " + binding) + raw.substring(open);
        var layout = LAYOUT.matcher(prefix);
        if (layout.find()) return raw.substring(0, layout.end() - 1) + ", binding = " + binding + raw.substring(layout.end() - 1);
        return "layout(" + (block.storage().equals("uniform") ? "std140" : "std430") + ", binding = " + binding + ") " + raw;
    }
    private static List<String> opaqueLayout(IrisTranslator.Opaque opaque) {
        var result = new ArrayList<String>(); var layout = LAYOUT.matcher(opaque.declaration());
        while (layout.find()) for (String part : layout.group(1).split(",")) {
            String value = part.replaceAll("\\s+", "");
            if (!value.startsWith("binding=") && !value.startsWith("set=")) result.add(value);
        }
        result.sort(String::compareTo); return result;
    }
    private static String opaqueArray(IrisTranslator.Opaque opaque) {
        var matcher = Pattern.compile("\\b" + Pattern.quote(opaque.name()) + "\\s*((?:\\[[^]]*]\\s*)*);").matcher(opaque.declaration());
        if (!matcher.find()) throw invalid("cannot parse opaque declaration: " + opaque.name());
        return matcher.group(1).replaceAll("\\s+", "");
    }
    private static String rebind(String declaration, int binding) {
        return BINDING.matcher(declaration).replaceAll("binding = " + binding);
    }
    private static String sharedBlock(String source, Collection<IrisTranslator.Uniform> uniforms) {
        if (uniforms.isEmpty()) return source;
        var block = new StringBuilder("layout(std140, set = 0, binding = 0) uniform IrisUniforms {\n");
        for (var uniform : uniforms) block.append("    ").append(uniform.type()).append(' ').append(uniform.name()).append(uniform.array()).append(";\n");
        block.append("};\n");
        var matcher = BLOCK.matcher(source);
        if (matcher.find()) return matcher.replaceFirst(Matcher.quoteReplacement(block.toString()));
        // This stage has no original block. Built-in scalar/vector/matrix types need no declarations.
        int at = source.indexOf('\n') + 1;
        var extension = Pattern.compile("(?m)^#extension[^\\n]*\\n").matcher(source);
        while (extension.find()) at = extension.end();
        return source.substring(0, at) + block + source.substring(at);
    }
    private static String replaceLocation(String prefix, int location) {
        return LOCATION.matcher(prefix).replaceAll("location = " + location);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Iris program link: " + message); }

    private static final class Declaration {
        int start, end;
        String prefix, type, direction;
        Integer explicitLocation;
        final List<Variable> variables = new ArrayList<>();
    }
    private static final class Variable {
        String name, key, array, shape, type, interpolation;
        boolean patch;
        int width, location = -1;
    }
    private static List<Variable> variables(List<Declaration> declarations, String direction) {
        return declarations.stream().filter(d -> d.direction.equals(direction)).flatMap(d -> d.variables.stream()).toList();
    }
    private static List<Declaration> declarations(String source, IrisStage stage) {
        var declarations = new ArrayList<Declaration>();
        walkStatements(source, (start, end) -> parseDeclaration(source, start, end, stage, declarations));
        return declarations;
    }
    private static void walkStatements(String source, java.util.function.BiConsumer<Integer, Integer> consumer) {
        int start = 0, braces = 0, parens = 0; boolean function = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '#' && braces == 0 && parens == 0) {
                int end = source.indexOf('\n', i); if (end < 0) break;
                i = end; start = end + 1; continue;
            }
            if (c == '(') parens++; else if (c == ')') parens--;
            if (c == '{') {
                if (braces == 0) { int p = i - 1; while (p >= start && Character.isWhitespace(source.charAt(p))) p--; function = p >= start && source.charAt(p) == ')'; }
                braces++;
            } else if (c == '}') {
                braces--;
                if (braces == 0 && function) { start = i + 1; function = false; }
            } else if (c == ';' && braces == 0 && parens == 0) {
                consumer.accept(start, i + 1); start = i + 1;
            }
        }
    }
    private static void parseDeclaration(String source, int start, int end, IrisStage stage, List<Declaration> result) {
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        String raw = source.substring(start, end), clean = LAYOUT.matcher(raw).replaceAll(" ");
        if (!Pattern.compile("\\b(in|out)\\b").matcher(clean).find()) return;
        if (clean.contains("{")) {
            if (Pattern.compile("\\bgl_PerVertex\\b").matcher(clean).find()) return;
            parseBlock(source, start, end, stage, result);
            return;
        }
        var words = Pattern.compile("\\b\\w+\\b").matcher(clean);
        var qualifiers = new HashSet<String>(); String type = null;
        while (words.find()) {
            String word = words.group();
            if (!QUALIFIERS.contains(word)) { type = word; break; }
            qualifiers.add(word);
        }
        if (!qualifiers.contains("in") && !qualifiers.contains("out")) return;
        if (type == null) return; // layout(triangles) in; / layout(vertices=3) out;
        if (qualifiers.contains("in") && qualifiers.contains("out")) throw invalid("ambiguous interface storage in " + stage);
        var declaration = new Declaration(); declaration.start = start; declaration.end = end;
        declaration.direction = qualifiers.contains("in") ? "in" : "out"; declaration.type = type;
        // Find the type in the original declaration after all layouts, preserving qualifiers verbatim.
        var typeToken = Pattern.compile("\\b" + Pattern.quote(type) + "\\b").matcher(raw);
        int typeAt = -1;
        while (typeToken.find()) { boolean inLayout = false; var layouts = LAYOUT.matcher(raw); while (layouts.find()) if (typeToken.start() >= layouts.start() && typeToken.start() < layouts.end()) inLayout = true; if (!inLayout) { typeAt = typeToken.start(); break; } }
        if (typeAt < 0) throw invalid("cannot parse interface type in " + stage);
        declaration.prefix = raw.substring(0, typeAt);
        var location = LOCATION.matcher(declaration.prefix);
        if (location.find()) declaration.explicitLocation = Integer.parseInt(location.group(1));
        if (Pattern.compile("\\b(component|index|xfb_buffer|xfb_offset)\\s*=").matcher(declaration.prefix).find())
            throw invalid("component/index/transform feedback interface layouts are unsupported in " + stage);
        String declarators = raw.substring(typeAt + type.length(), raw.length() - 1).strip();
        int offset = 0;
        for (String item : declarators.split(",")) {
            var parsed = Pattern.compile("\\s*(\\w+)\\s*((?:\\[[^]]*]\\s*)*)\\s*").matcher(item);
            if (!parsed.matches()) throw invalid("unsupported interface declaration in " + stage + ": " + item);
            String name = parsed.group(1); if (name.startsWith("gl_")) continue;
            var variable = new Variable(); variable.name = name; variable.key = name; variable.array = parsed.group(2).replaceAll("\\s+", ""); variable.type = type;
            variable.patch = qualifiers.contains("patch");
            variable.interpolation = qualifiers.contains("flat") ? "flat" : qualifiers.contains("noperspective") ? "noperspective" : "smooth";
            boolean perVertexArray = !variable.patch && ((declaration.direction.equals("in") && (stage == IrisStage.GEOMETRY || stage == IrisStage.TESS_CONTROL || stage == IrisStage.TESS_EVALUATION))
                || declaration.direction.equals("out") && stage == IrisStage.TESS_CONTROL);
            String shape = variable.array;
            if (perVertexArray) {
                if (!shape.startsWith("[")) throw invalid("missing per-vertex interface array: " + stage + " " + name);
                shape = shape.substring(shape.indexOf(']') + 1);
            }
            variable.shape = shape; variable.width = footprint(type, shape);
            if (declaration.explicitLocation != null) variable.location = Math.addExact(declaration.explicitLocation, offset);
            offset = Math.addExact(offset, variable.width); declaration.variables.add(variable);
        }
        if (!declaration.variables.isEmpty()) result.add(declaration);
    }
    private static void parseBlock(String source, int start, int end, IrisStage stage, List<Declaration> result) {
        String raw = source.substring(start, end);
        int open = raw.indexOf('{'), close = raw.lastIndexOf('}');
        if (close <= open || raw.substring(open + 1, close).contains("{")) throw invalid("nested interface structures are unsupported in " + stage);
        var blockName = Pattern.compile("(\\w+)\\s*\\{").matcher(raw);
        if (!blockName.find()) throw invalid("cannot parse interface block in " + stage);
        String name = blockName.group(1), prefix = raw.substring(0, blockName.start());
        String body = raw.substring(open + 1, close), tail = raw.substring(close + 1).strip();
        boolean anonymous = tail.equals(";");
        if (anonymous) tail = "iris_anonymous_" + name + ";";
        var proxy = new ArrayList<Declaration>();
        String proxySource = prefix + "vec4 " + tail;
        parseDeclaration(proxySource, 0, proxySource.length(), stage, proxy);
        if (proxy.size() != 1 || proxy.getFirst().variables.size() != 1) throw invalid("interface block must have one instance: " + name);
        var declaration = proxy.getFirst();
        int width = 0; var signature = new StringBuilder(name).append('{');
        for (String member : body.split(";")) {
            if (member.isBlank()) continue;
            if (LAYOUT.matcher(member).find()) throw invalid("member interface layouts are unsupported: " + name);
            var members = new ArrayList<Declaration>();
            String memberSource = "out " + member.strip() + ";";
            parseDeclaration(memberSource, 0, memberSource.length(), IrisStage.VERTEX, members);
            if (members.size() != 1) throw invalid("unsupported block member: " + name + ": " + member);
            for (var variable : members.getFirst().variables) {
                width = Math.addExact(width, variable.width);
                // Names and declaration order matter for a GLSL interface block; instance names do not.
                signature.append(variable.type).append(' ').append(variable.name).append(variable.shape).append(':').append(variable.interpolation).append(';');
            }
        }
        if (width == 0) throw invalid("empty interface block: " + name);
        declaration.start = start; declaration.end = end;
        declaration.type = raw.substring(blockName.start(), close + 1);
        var variable = declaration.variables.getFirst();
        variable.type = signature.append('}').toString(); variable.key = name;
        variable.width = Math.multiplyExact(width, footprint("float", variable.shape));
        if (anonymous) variable.name = "";
        result.add(declaration);
    }
    private static int footprint(String type, String shape) {
        int width;
        if (type.matches("(?:float|int|uint|bool|double|[biu]?vec[234]|dvec[234])")) width = type.matches("dvec[34]") ? 2 : 1;
        else {
            var matrix = Pattern.compile("(d?)mat([234])(?:x([234]))?").matcher(type);
            if (!matrix.matches()) throw invalid("unsupported interface type: " + type);
            int columns = Integer.parseInt(matrix.group(2)), rows = matrix.group(3) == null ? columns : Integer.parseInt(matrix.group(3));
            width = columns * (matrix.group(1).equals("d") && rows >= 3 ? 2 : 1);
        }
        var dimension = Pattern.compile("\\[([^]]*)]").matcher(shape);
        while (dimension.find()) {
            String size = dimension.group(1);
            if (!size.matches("[1-9][0-9]*[uU]?")) throw invalid("interface array needs a positive literal extent: " + shape);
            try { width = Math.multiplyExact(width, Integer.parseInt(size.replaceAll("[uU]$", ""))); }
            catch (ArithmeticException | NumberFormatException e) { throw invalid("interface location range overflows: " + shape); }
        }
        return width;
    }
    private static void connect(List<Variable> outputs, List<Variable> inputs, IrisStage producer, IrisStage consumer, List<Varying> varyings) {
        var pairs = new LinkedHashMap<Variable, Variable>();
        for (var input : inputs) {
            var named = outputs.stream().filter(o -> o.key.equals(input.key)).findFirst();
            Variable output = named.orElseGet(() -> input.location < 0 ? null : outputs.stream().filter(o -> o.location == input.location).findFirst().orElse(null));
            if (output == null) throw invalid("missing producer for " + consumer + " input " + input.name);
            if (!output.type.equals(input.type) || !output.shape.equals(input.shape) || output.patch != input.patch || !output.interpolation.equals(input.interpolation))
                throw invalid("incompatible interface " + producer + " -> " + consumer + ": " + input.name);
            if (output.location >= 0 && input.location >= 0 && output.location != input.location) throw invalid("explicit location mismatch: " + input.name);
            if (pairs.containsKey(output)) throw invalid("multiple consumers alias output " + output.name);
            if (output.location < 0) output.location = input.location;
            input.location = output.location; pairs.put(output, input);
        }
        allocate(outputs);
        for (var pair : pairs.entrySet()) {
            var output = pair.getKey(); var input = pair.getValue(); input.location = output.location;
            varyings.add(new Varying(producer, consumer, input.key, input.type, input.shape, input.location, input.width));
        }
        allocate(inputs);
    }
    private static void allocate(List<Variable> variables) {
        var occupied = new TreeMap<Integer, Variable>();
        for (var variable : variables) if (variable.location >= 0) reserve(occupied, variable);
        var sorted = variables.stream().sorted(Comparator.comparing(v -> v.name)).toList();
        for (var variable : sorted) if (variable.location < 0) {
            int at = 0;
            while (!free(occupied, at, variable.width)) at++;
            variable.location = at; reserve(occupied, variable);
        }
    }
    private static boolean free(Map<Integer, Variable> occupied, int start, int width) {
        for (int i = 0; i < width; i++) if (occupied.containsKey(Math.addExact(start, i))) return false;
        return true;
    }
    private static void reserve(Map<Integer, Variable> occupied, Variable variable) {
        for (int i = 0; i < variable.width; i++) {
            int location = Math.addExact(variable.location, i);
            var old = occupied.putIfAbsent(location, variable);
            if (old != null && old != variable) throw invalid("interface location collision at " + location + ": " + old.name + " / " + variable.name);
        }
    }
}
