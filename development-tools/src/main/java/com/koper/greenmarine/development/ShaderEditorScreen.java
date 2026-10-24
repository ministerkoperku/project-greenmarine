package com.koper.greenmarine.development;

import com.koper.greenmarine.client.ShaderRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Candidate compilation never writes a pack; application is a separate explicit action. */
public final class ShaderEditorScreen extends Screen {
    private record Draft(String disk, String text) {}
    private static final Map<String, Draft> DRAFTS = new LinkedHashMap<>();
    private final Screen parent;
    private final String path;
    private final ShaderWorkspace workspace;
    private String text = "", diagnostic = "Compile before applying. Compute and Slang binaries export separately.";
    private String compiledText;
    private String baseline = "";
    private ShaderCompiler.Result result;
    private ShaderCompiler.Language language;
    private ShaderCompiler.Stage stage;
    private CodeEditBox editor;
    private Button compile, apply, export, issue;
    private List<CompilerDiagnostics.Issue> issues = List.of();
    private int issueIndex;
    private boolean busy;
    private long generation;
    public ShaderEditorScreen(Screen parent, String path) {
        super(Component.literal("Shader editor")); this.parent = parent; this.path = path; workspace = DevelopmentClient.workspace();
        language = path.endsWith(".slang") ? ShaderCompiler.Language.SLANG : ShaderCompiler.Language.GLSL;
        stage = path.endsWith(".vsh") ? ShaderCompiler.Stage.VERTEX : path.endsWith(".comp") ? ShaderCompiler.Stage.COMPUTE : ShaderCompiler.Stage.FRAGMENT;
        try {
            baseline = workspace.read(path); text = baseline;
            var draft = DRAFTS.get(path);
            if (draft != null) {
                text = draft.text();
                diagnostic = draft.disk().equals(baseline) ? "Restored unsaved session draft" : "Source changed externally; restored unsaved draft. Save replaces the external version.";
            }
        }
        catch (Exception e) { diagnostic = "Cannot load source: " + e.getMessage(); }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void removed() {
        generation++; busy = false;
        if (text.equals(baseline)) DRAFTS.remove(path);
        else {
            if (DRAFTS.size() >= 16 && !DRAFTS.containsKey(path)) DRAFTS.remove(DRAFTS.keySet().iterator().next());
            DRAFTS.put(path, new Draft(baseline, text));
        }
    }
    @Override protected void init() {
        int x = 8, w = Math.max(80, width - 16), gap = 4, third = (w - 2 * gap) / 3;
        addRenderableWidget(Button.builder(Component.literal(language.name()), b -> {
            if (busy) return; language = language == ShaderCompiler.Language.GLSL ? ShaderCompiler.Language.SLANG : ShaderCompiler.Language.GLSL; invalidate(); rebuildWidgets();
        }).bounds(x, 27, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal(stage.name()), b -> {
            if (busy) return; stage = ShaderCompiler.Stage.values()[(stage.ordinal() + 1) % ShaderCompiler.Stage.values().length]; invalidate(); rebuildWidgets();
        }).bounds(x + third + gap, 27, third, 20).build());
        compile = addRenderableWidget(Button.builder(Component.literal(busy ? "Compiling..." : path.endsWith(".json") ? "Validate JSON" : "Compile"), b -> compile())
            .bounds(x + 2 * (third + gap), 27, w - 2 * (third + gap), 20).build());
        editor = addRenderableWidget(new CodeEditBox(font, x, 51, w, Math.max(36, height - 127), Component.literal("Shader source")));
        editor.setCharacterLimit(262144); editor.setValue(text);
        editor.setValueListener(value -> { text = value; invalidate(); });
        editor.setIssues(CompilerDiagnostics.byLine(issues));
        issue = addRenderableWidget(Button.builder(Component.empty(), b -> nextIssue()).bounds(x, height - 72, w, 20).build());
        apply = addRenderableWidget(Button.builder(Component.literal("Save + reload"), b -> apply())
            .bounds(x, height - 48, third, 20).build());
        export = addRenderableWidget(Button.builder(Component.literal("Export SPIR-V"), b -> export())
            .bounds(x + third + gap, height - 48, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back (keep draft)"), b -> onClose())
            .bounds(x + 2 * (third + gap), height - 48, w - 2 * (third + gap), 20).build());
        enable();
    }
    private void invalidate() { compiledText = null; result = null; if (apply != null) enable(); }
    private void showIssues(String diagnostics) {
        issues = CompilerDiagnostics.parse(diagnostics, path); issueIndex = 0;
        if (editor != null) editor.setIssues(CompilerDiagnostics.byLine(issues));
    }
    private void nextIssue() {
        if (issues.isEmpty()) return;
        editor.goToLine(issues.get(issueIndex).line()); setFocused(editor);
        issueIndex = (issueIndex + 1) % issues.size(); enable();
    }
    private void enable() {
        compile.active = !busy;
        boolean valid = !busy && result != null && result.success() && text.equals(compiledText);
        apply.active = valid && EditorPolicy.canApply(path, language, stage);
        export.active = valid && result.spirv().length > 0;
        compile.setTooltip(Tooltip.create(Component.literal(diagnostic)));
        issue.visible = !issues.isEmpty();
        if (!issues.isEmpty()) {
            var current = issues.get(issueIndex);
            String label = (current.error() ? "Error" : "Warning") + " line " + current.line() + ": " + current.message()
                + (issues.size() > 1 ? "  [" + (issueIndex + 1) + "/" + issues.size() + "]" : "");
            issue.setMessage(Component.literal(font.plainSubstrByWidth(label, issue.getWidth() - 8)));
            issue.setTooltip(Tooltip.create(Component.literal(label)));
        }
    }
    private Path slangCompiler() {
        try {
            var config = DevelopmentClient.gameRoot().resolve("config/greenmarine-development.json");
            if (!Files.isRegularFile(config) || Files.size(config) > 4096) return null;
            var object = com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            String configured = object.has("slangCompiler") ? object.get("slangCompiler").getAsString() : "";
            return configured.isBlank() ? null : Path.of(configured);
        } catch (Exception e) { return null; }
    }
    private void compile() {
        String candidate = text; var selectedLanguage = language; var selectedStage = stage;
        if (path.endsWith(".json")) {
            try {
                var json = com.google.gson.JsonParser.parseString(candidate);
                if (path.contains("/greenmarine/packs/")) com.koper.greenmarine.api.pack.ShaderPack.parse(json.getAsJsonObject());
                result = new ShaderCompiler.Result(true, "JSON validated; runtime resource checks run on reload", new byte[0]);
                compiledText = candidate; diagnostic = result.diagnostics();
            } catch (Exception e) { result = null; compiledText = null; diagnostic = "Invalid JSON: " + e.getMessage(); }
            enable(); return;
        }
        long request = ++generation; busy = true; diagnostic = "Compiling candidate; pack files unchanged"; enable();
        var executable = slangCompiler();
        CompletableFuture.supplyAsync(() -> ShaderCompiler.compile(selectedLanguage, selectedStage,
            candidate, path, executable, this::include)).whenComplete((compiled, error) -> minecraft.execute(() -> {
                if (request != generation || minecraft.gui.screen() != this) return;
                busy = false;
                if (error != null) { diagnostic = "Compilation failed: " + error.getMessage(); result = null; showIssues(""); }
                else {
                    result = compiled; compiledText = candidate;
                    diagnostic = compiled.success() ? "Compiled " + compiled.spirv().length + " bytes. " + compiled.diagnostics() : compiled.diagnostics();
                    showIssues(compiled.diagnostics());
                }
                if (DevelopmentClient.preferences().verboseDebug()) org.slf4j.LoggerFactory.getLogger("greenmarine-development")
                    .info("[development] compiler {} {} {}: {}", selectedLanguage, selectedStage, path, diagnostic);
                enable();
            }));
    }
    private String include(String name) { return ShaderIncludes.read(workspace, path, name); }
    private void apply() {
        if (!apply.active) return;
        try {
            workspace.save(path, text);
            baseline = text; DRAFTS.remove(path);
            DevelopmentClient.resetWatcher();
            diagnostic = "Saved; reloading resources. Runtime renderer status determines successful application.";
            minecraft.reloadResourcePacks().whenComplete((v, e) -> minecraft.execute(() -> {
                diagnostic = e == null ? "Reloaded. " + ShaderRuntime.status().detail() : "Reload failed: " + e.getMessage();
                if (minecraft.gui.screen() == this) enable();
            }));
        } catch (Exception e) { diagnostic = "Save failed: " + e.getMessage(); }
    }
    private void export() {
        if (!export.active) return;
        try {
            byte[] bytes = result.spirv();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Path directory = DevelopmentClient.gameRoot().resolve("greenmarine-development/compiled");
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent())) throw new java.io.IOException("Linked export directory is unsupported");
            Path output = directory.resolve(hash + ".spv");
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
            diagnostic = "Exported greenmarine-development/compiled/" + output.getFileName() + "; not automatically bound to a pack";
        } catch (FileAlreadyExistsException e) { diagnostic = "This SPIR-V module has already been exported"; }
        catch (Exception e) { diagnostic = "Export failed: " + e.getMessage(); }
        enable();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, font.plainSubstrByWidth(path, width - 16), width / 2, 10, 0xFFFFFFFF);
        if (issues.isEmpty()) graphics.text(font, font.plainSubstrByWidth(diagnostic.replace('\n', ' '), width - 16), 8, height - 66, result != null && result.success() ? 0xFFAAEEAA : 0xFFFFDD99);
    }
}
