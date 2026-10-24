package com.koper.greenmarine.development.iris;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** IR-1 measurement: every default-enabled program stage through include expansion, preprocessing, translation and a strict compile. */
public final class IrisCoverage {
    public record StageResult(String world, String program, IrisStage stage, String phase, boolean success, String diagnostics) {
        /** Programs Iris only loads next to another mod (Colorwheel, Distant Horizons, Voxy, Physics Mod). */
        public boolean optional() { return program.startsWith("clrwl_") || program.startsWith("dh_") || program.startsWith("voxy") || program.startsWith("physics_")
            || program.endsWith("_voxels"); }
    }
    public static final List<String> WORLDS = List.of("world0", "world-1", "world1");

    public static Map<String, String> hostMacros() {
        var macros = new LinkedHashMap<String, String>();
        macros.put("MC_VERSION", "260300"); macros.put("MC_GL_VERSION", "460"); macros.put("MC_GLSL_VERSION", "460");
        macros.put("IS_IRIS", ""); macros.put("IRIS_VERSION", "11200"); macros.put("MC_OS_LINUX", "");
        macros.put("MC_RENDER_QUALITY", "1.0"); macros.put("MC_SHADOW_QUALITY", "1.0"); macros.put("MC_HAND_DEPTH", "0.125");
        for (String feature : List.of("SSBO", "COMPUTE_SHADERS", "CUSTOM_IMAGES", "SEPARATE_HARDWARE_SAMPLERS")) macros.put("IRIS_FEATURE_" + feature, "");
        String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
            "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROYED_BLOCKS", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT",
            "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"};
        for (int i = 0; i < stages.length; i++) macros.put("MC_RENDER_STAGE_" + stages[i], Integer.toString(i));
        return macros;
    }

    /** Option macros as shaders.properties sees them: the pack's settings files run through the same conditionals. */
    public static Map<String, String> propertyMacros(IrisSources sources) {
        var pre = new MiniPreprocessor(hostMacros());
        for (String path : sources.textPaths())
            if (path.toLowerCase(Locale.ROOT).contains("setting") && path.endsWith(".glsl")) pre.process(sources.text(path).orElse(""));
        return pre.macros();
    }

    public static Map<String, String> properties(IrisSources sources, Map<String, String> macros) {
        var values = new LinkedHashMap<String, String>();
        for (String line : new MiniPreprocessor(macros).process(sources.text("shaders.properties").orElse(""))) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.strip().startsWith("#")) continue;
            values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return values;
    }

    public static List<StageResult> run(IrisSources sources, Path dump) throws IOException {
        var macros = propertyMacros(sources);
        var properties = properties(sources, macros);
        var evaluator = new MiniPreprocessor(macros);
        var results = new ArrayList<StageResult>();
        for (String world : WORLDS) {
            var programs = new TreeMap<String, List<String>>();
            for (String path : sources.textPaths()) {
                if (!path.startsWith(world + "/") || path.indexOf('/', world.length() + 1) >= 0 || IrisStage.of(path) == null) continue;
                String file = path.substring(world.length() + 1);
                programs.computeIfAbsent(file.substring(0, file.lastIndexOf('.')), k -> new ArrayList<>()).add(path);
            }
            for (var program : programs.entrySet()) {
                String enabled = properties.get("program." + world + "/" + program.getKey() + ".enabled");
                if (enabled != null && (enabled.equals("false") || (!enabled.equals("true") && !evaluator.truthy(enabled)))) continue;
                for (String path : program.getValue()) results.add(stage(sources, world, program.getKey(), path, dump));
            }
        }
        return results;
    }

    private static StageResult stage(IrisSources sources, String world, String program, String path, Path dump) throws IOException {
        IrisStage stage = IrisStage.of(path);
        String expanded;
        try { expanded = IrisPreprocess.expandIncludes(sources, path); }
        catch (IllegalArgumentException e) { return new StageResult(world, program, stage, "include", false, e.getMessage()); }
        var pre = IrisPreprocess.preprocess(expanded, stage, path, hostMacros());
        if (!pre.success()) return new StageResult(world, program, stage, "preprocess", false, pre.diagnostics());
        IrisTranslator.Translation translation;
        try { translation = IrisTranslator.translate(pre.text(), stage); }
        catch (RuntimeException e) { return new StageResult(world, program, stage, "translate", false, e.toString()); }
        var compiled = IrisCompiler.compile(translation.text(), stage, path);
        if (dump != null) {
            Path out = dump.resolve(path + ".vk.glsl");
            Files.createDirectories(out.getParent());
            Files.writeString(out, translation.text());
            if (!compiled.success()) Files.writeString(dump.resolve(path + ".errors.txt"), compiled.diagnostics());
        }
        return new StageResult(world, program, stage, "compile", compiled.success(), compiled.diagnostics());
    }

    private static final Pattern NOISE = Pattern.compile("^[^:]*:\\d+: (error|warning): ");
    public static String report(List<StageResult> results) {
        var sb = new StringBuilder();
        var optional = results.stream().filter(StageResult::optional).toList();
        results = results.stream().filter(r -> !r.optional()).toList();
        long ok = results.stream().filter(StageResult::success).count();
        sb.append(String.format("IR-1 translate+compile: %d/%d stages (%.1f%%)%n", ok, results.size(), 100.0 * ok / Math.max(1, results.size())));
        sb.append(String.format("  mod-integration programs (not counted): %d/%d%n", optional.stream().filter(StageResult::success).count(), optional.size()));
        for (String world : WORLDS) {
            var w = results.stream().filter(r -> r.world().equals(world)).toList();
            if (w.isEmpty()) continue;
            long wok = w.stream().filter(StageResult::success).count();
            sb.append(String.format("  %-8s %d/%d%n", world, wok, w.size()));
        }
        var byStage = new TreeMap<IrisStage, long[]>();
        for (var r : results) { var c = byStage.computeIfAbsent(r.stage(), k -> new long[2]); c[1]++; if (r.success()) c[0]++; }
        byStage.forEach((stage, c) -> sb.append(String.format("  %-16s %d/%d%n", stage, c[0], c[1])));
        var blockers = new HashMap<String, Integer>();
        for (var r : results) {
            if (r.success()) continue;
            String first = Arrays.stream(r.diagnostics().split("\n")).filter(l -> l.contains("error")).findFirst().orElse(r.diagnostics().lines().findFirst().orElse("?"));
            first = r.phase() + ": " + NOISE.matcher(first.strip()).replaceFirst("");
            blockers.merge(first.length() > 160 ? first.substring(0, 160) : first, 1, Integer::sum);
        }
        sb.append("Failing stages:\n");
        for (var r : results) if (!r.success()) sb.append("  ").append(r.world()).append('/').append(r.program()).append('.').append(r.stage().extension)
            .append("  [").append(r.phase()).append("] ").append(r.diagnostics().lines().filter(l -> l.contains("error")).findFirst().orElse("").strip()).append('\n');
        sb.append("Top blockers (first error per stage):\n");
        blockers.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25)
            .forEach(e -> sb.append(String.format("  %4d  %s%n", e.getValue(), e.getKey())));
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) { System.err.println("usage: IrisCoverage <iris pack dir|zip> [dump dir]"); System.exit(2); }
        var results = run(IrisSources.load(Path.of(args[0])), args.length > 1 ? Path.of(args[1]) : null);
        System.out.print(report(results));
    }
}
