package com.koper.greenmarine.development;

import com.google.gson.GsonBuilder;
import com.koper.greenmarine.development.iris.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Whole-program source conversion inside the importer's unpublished staging directory.
 * The resulting pipeline document is an editable conversion workspace, not a selectable pack.
 * Program ABI linking, strict stage compilation and host runtime support are reported separately. */
public final class IrisProjectExporter {
    public static final String METADATA = "conversion/pipeline.json";
    public static final String INPUT_METADATA="conversion/inputs.json";
    private static final long MAX_GENERATED_BYTES = 512L * 1024 * 1024;
    public record Coverage(int programs, int disabledPrograms, int enabledStages, int preprocessedStages,
                           int preprocessFailures, int linkedGroups, int linkFailures, int compiledStages,
                           int compileFailures, int optionalCompileFailures, int coreCompiledStages,
                           int coreCompileFailures, boolean runtimeReady) {}
    public record StageOutput(String source, IrisStage kind, String glsl, String spirv,
                              boolean linked, boolean compiled, List<Integer> targets, boolean explicitTargets,
                              Map<String,IrisPipelinePlan.Constant> constants, Map<String,String> localSize,
                              List<String> translationNotes, String diagnostics) {}
    public record Group(String id, String world, String program, String kind, boolean optionalIntegration,
                        boolean linked, List<StageOutput> stages, List<IrisTranslator.Uniform> uniforms,
                        List<IrisTranslator.Opaque> opaques, List<IrisProgramLinker.Varying> varyings,
                        List<IrisProgramLinker.ResourceBlock> blocks, String diagnostics) {}
    public record ProgramOutput(String world, String name, IrisPipelinePlan.Phase phase, int passIndex,
                                boolean enabled, String enableExpression, boolean optionalIntegration,
                                List<String> sourceStages, List<String> executionGroups) {}
    public record GeometryFallback(String world, String requested, String resolved) {}
    public record Document(int schema, String kind, String sourceRoot, String assetRoot, Coverage coverage,
                           Map<String,String> dimensionMappings, List<ProgramOutput> programs,
                           List<GeometryFallback> geometryFallbacks, List<Group> groups,
                           List<IrisPipelinePlan.Image> images, List<IrisPipelinePlan.Buffer> buffers,
                           List<IrisPipelinePlan.Texture> textures, Map<String,String> optionMacros,
                           String rawProperties, List<IrisPipelinePlan.Property> propertyEntries,
                           Map<String,String> properties, Map<String,String> renderSettings,
                           Map<String,String> unhandledProperties, List<IrisPipelinePlan.Diagnostic> diagnostics,
                           List<String> runtimeBlockers,String inputMetadata) {}
    public record Result(Coverage coverage, String report,boolean executable) {
        public Result(Coverage coverage,String report){this(coverage,report,false);}
    }
    private static final List<String> RUNTIME_BLOCKERS = List.of(
        "Whole Iris program sets are not installed as a GreenMarine execution graph by this converter.",
        "Host geometry/material dispatch, world and shadow render targets and camera conventions require runtime integration.",
        "IrisUniforms declarations must be connected to native per-program resources and their frame, draw and shadow input owners.",
        "Resource allocation, clears, history flips, mipmaps, compute dispatch sizes, barriers and pass transitions require runtime integration.",
        "Options/profiles must rebuild affected pipelines and resources; preserving their declarations does not implement their behavior.",
        "Optional mod programs require their owning host integrations. Compilation alone does not establish runtime rendering support."
    );
    private static final List<String> GEOMETRY_PROGRAMS = List.of("gbuffers_basic", "gbuffers_line", "gbuffers_textured", "gbuffers_textured_lit",
        "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds", "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout",
        "gbuffers_damagedblock", "gbuffers_block", "gbuffers_block_translucent", "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities",
        "gbuffers_entities_translucent", "gbuffers_lightning", "gbuffers_particles", "gbuffers_particles_translucent", "gbuffers_entities_glowing",
        "gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_hand", "gbuffers_weather", "gbuffers_water", "gbuffers_hand_water",
        "shadow", "shadow_solid", "shadow_cutout", "shadow_water", "shadow_entities", "shadow_lightning", "shadow_block");
    private IrisProjectExporter() {}
    public static List<String> geometryRequests(){return GEOMETRY_PROGRAMS;}

    /** Caller owns source preservation and atomic publication. Existing output files are never overwritten. */
    public static Result export(IrisSources sources, Path staging) throws IOException {
        var plan = IrisPipelinePlan.parse(sources); var writer = new Output(staging);
        var groups = new ArrayList<Group>(); var programs = new ArrayList<ProgramOutput>(); var fallbacks = new ArrayList<GeometryFallback>();
        int disabled = 0, enabledStages = 0, preprocessed = 0;
        for (var world : plan.worlds()) {
            for (String requested : GEOMETRY_PROGRAMS) world.resolve(requested).ifPresent(p -> fallbacks.add(new GeometryFallback(world.folder(), requested, p.name())));
            for (var program : world.programs()) {
                var sourceStages = new ArrayList<IrisPipelinePlan.Stage>(program.stages().values()); sourceStages.addAll(program.computes());
                var execution = new ArrayList<String>();
                if (!program.enabled()) disabled++;
                else {
                    enabledStages += sourceStages.size(); preprocessed += (int)sourceStages.stream().filter(IrisPipelinePlan.Stage::preprocessed).count();
                    // Compute siblings always precede this program's graphics group. Geometry groups
                    // remain host-dispatched; the list below does not fix their Minecraft draw order.
                    for (var compute : program.computes()) {
                        String name = filename(compute.path());
                        var group = group(world, program, name, "compute", Map.of(IrisStage.COMPUTE, compute), writer);
                        groups.add(group); execution.add(group.id());
                    }
                    if (!program.stages().isEmpty()) {
                        var group = group(world, program, program.name(), "graphics", program.stages(), writer);
                        groups.add(group); execution.add(group.id());
                    }
                }
                programs.add(new ProgramOutput(world.folder(), program.name(), program.phase(), program.passIndex(), program.enabled(),
                    program.enableExpression(), program.optionalIntegration(), sourceStages.stream().map(IrisPipelinePlan.Stage::path).toList(), List.copyOf(execution)));
            }
        }
        int compiled = 0, failures = 0, optionalFailures = 0, coreCompiled = 0, coreFailures = 0;
        for (var group : groups) for (var stage : group.stages()) {
            if (stage.compiled()) { compiled++; if (!group.optionalIntegration()) coreCompiled++; }
            else if (group.linked()) { failures++; if (group.optionalIntegration()) optionalFailures++; else coreFailures++; }
        }
        int linked = (int)groups.stream().filter(Group::linked).count();
        var executable=IrisExecutableExporter.export(sources,plan,groups,(path,text)->writer.write(path,text.getBytes(StandardCharsets.UTF_8)));
        var coverage = new Coverage(programs.size(), disabled, enabledStages, preprocessed, enabledStages-preprocessed, linked,
            groups.size()-linked, compiled, failures, optionalFailures, coreCompiled, coreFailures, false);
        var document = new Document(1, "greenmarine.iris.conversion-workspace", "iris-source/", "assets/iris_imported/shaders/iris/", coverage,
            plan.dimensionMappings(), programs, fallbacks, groups, plan.images(), plan.buffers(), plan.textures(), plan.optionMacros(),
            plan.rawProperties(), plan.propertyEntries(), plan.properties(), plan.renderSettings(), plan.unhandledProperties(), plan.diagnostics(), RUNTIME_BLOCKERS,INPUT_METADATA);
        var gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        writer.write(INPUT_METADATA,(gson.toJson(IrisNativeInputs.export(plan))+"\n").getBytes(StandardCharsets.UTF_8));
        String json = gson.toJson(document) + "\n";
        writer.write(METADATA, json.getBytes(StandardCharsets.UTF_8));
        String report = "## Whole-program editable conversion\n\nMetadata: `"+METADATA+"`. Native constant/expression configuration: `"+INPUT_METADATA+"`.\n\n"
            + "Programs inventoried: "+coverage.programs()+"; disabled programs preserved: "+disabled+".\n\n"
            + "Preprocessing: "+preprocessed+"/"+enabledStages+" enabled stages. Program ABI linking: "+linked+"/"+groups.size()+" groups.\n\n"
            + "Strict Vulkan compilation: "+compiled+" stages; failures: "+failures+" (optional integration failures: "+optionalFailures+"). "
            + "Core compiled stages: "+coreCompiled+"; core compile failures: "+coreFailures+". Link failures: "+coverage.linkFailures()+".\n\n"
            + "Native executable manifest: "+executable.executable()+". Runtime verification: false. Native candidates and exact unmet requirements are in `conversion/native/`.\n\n"
            + String.join("\n", executable.diagnostics().stream().map(s -> "- "+s).toList())+"\n";
        return new Result(coverage, report,executable.executable());
    }

    private static Group group(IrisPipelinePlan.World world, IrisPipelinePlan.Program program, String name, String kind,
                               Map<IrisStage,IrisPipelinePlan.Stage> sourceStages, Output output) throws IOException {
        String id = world.folder()+"/"+name+":"+kind;
        var preprocessed = new EnumMap<IrisStage,String>(IrisStage.class);
        var errors = new ArrayList<String>();
        for (var stage : sourceStages.values()) {
            if (stage.preprocessed()) preprocessed.put(stage.kind(), stage.preprocessedText());
            else errors.add(stage.path()+": "+stage.diagnostics());
        }
        IrisProgramLinker.Program linked = null;
        if (errors.isEmpty()) try { linked = IrisProgramLinker.link(preprocessed); }
        catch (RuntimeException error) { errors.add(error.toString()); }
        var stages = new ArrayList<StageOutput>();
        for (var entry : sourceStages.entrySet()) {
            IrisStage stage = entry.getKey(); var original = entry.getValue();
            IrisTranslator.Translation translated = linked == null ? null : linked.stages().get(stage);
            String diagnostics = original.diagnostics();
            if (translated == null && original.preprocessed()) try { translated = IrisTranslator.translate(original.preprocessedText(), stage); }
            catch (RuntimeException error) { diagnostics += "\n"+error; }
            String glsl = "", spirv = ""; boolean compiled = false;
            if (translated != null) {
                String folder = world.folder().isEmpty() ? "root" : "dimensions/"+segment(world.folder());
                glsl = "assets/iris_imported/shaders/iris/"+folder+"/"+segment(name)+"."+stage.extension;
                output.write(glsl, translated.text().getBytes(StandardCharsets.UTF_8));
                if (linked != null) {
                    var result = IrisCompiler.compileExplicit(translated.text(), stage, original.path());
                    diagnostics += result.diagnostics(); compiled = result.success();
                    if (compiled) { spirv = glsl+".spv"; output.write(spirv, result.spirv()); }
                } else diagnostics += "\nUnlinked editable candidate; program ABI link failed. No SPIR-V published for this stage.";
            }
            if (!errors.isEmpty()) diagnostics += "\n"+String.join("\n", errors);
            if (!diagnostics.isBlank()) output.write("conversion/diagnostics/"+segment(id)+"."+stage.extension+".txt", diagnostics.getBytes(StandardCharsets.UTF_8));
            List<Integer> targets = original.targets();
            if (targets.isEmpty() && stage == IrisStage.FRAGMENT && program.phase() != IrisPipelinePlan.Phase.FINAL)
                targets = List.of(0,1,2,3,4,5,6,7); // Iris's implicit MRT mapping, not translator output-location inference.
            stages.add(new StageOutput(original.path(), stage, glsl, spirv, linked != null, compiled, targets, !original.targets().isEmpty(),
                original.constants(), original.localSize(), translated == null ? List.of() : translated.notes(), diagnostics.strip()));
        }
        return new Group(id, world.folder(), program.name(), kind, program.optionalIntegration(), linked != null, List.copyOf(stages),
            linked == null ? List.of() : linked.uniforms(), linked == null ? List.of() : linked.opaques(), linked == null ? List.of() : linked.varyings(),
            linked == null ? List.of() : linked.blocks(), String.join("\n",errors));
    }

    private static String filename(String path) { String file=path.substring(path.lastIndexOf('/')+1); return file.substring(0,file.lastIndexOf('.')); }
    /** Injective encoding for untrusted folder/program names; even '.'/'..' and '%' cannot traverse or collide. */
    private static String segment(String text) {
        var result=new StringBuilder();
        for (byte raw:text.getBytes(StandardCharsets.UTF_8)) {
            int c=Byte.toUnsignedInt(raw);
            if (c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='_'||c=='-') result.append((char)c);
            else result.append('%').append(HexFormat.of().toHexDigits(raw));
        }
        return result.isEmpty()?"%00":result.toString();
    }
    private static final class Output {
        private final Path root; private long bytes;
        Output(Path root) { this.root=root.toAbsolutePath().normalize(); }
        void write(String relative, byte[] data) throws IOException {
            bytes += data.length; if (bytes>MAX_GENERATED_BYTES) throw new IOException("Generated conversion exceeds 512 MiB");
            Path file=root.resolve(relative).normalize();
            if (!file.startsWith(root)) throw new IOException("Generated asset escapes workspace");
            Files.createDirectories(file.getParent()); Files.write(file,data,StandardOpenOption.CREATE_NEW);
        }
    }
}
