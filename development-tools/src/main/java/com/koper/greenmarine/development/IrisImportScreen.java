package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Source conversion is explicit and preserves the Iris project for further editing. */
public final class IrisImportScreen extends Screen {
    private final Screen parent;
    private EditBox source;
    private String input = "", status = "Choose an Iris ZIP/directory. Original files are preserved.";
    private boolean busy;
    private IrisImporter.Report report;
    public IrisImportScreen(Screen parent) { super(Component.literal("Import Iris shaderpack")); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void onFilesDrop(List<Path> paths) {
        if (!busy && !paths.isEmpty()) { input = paths.getFirst().toAbsolutePath().toString(); source.setValue(input); }
    }
    @Override protected void init() {
        int x=14,w=width-28,top=height<240?32:52;
        var browse=addRenderableWidget(StudioUi.button(x,top,w,28,busy?"Importing...":"Browse and import Iris ZIP / folder","Choose a ZIP with one click, or open a directory and choose Import this folder. Downloads and Documents are available.",()->{
            Path initial=DevelopmentClient.gameRoot().resolve("shaderpacks");
            minecraft.gui.setScreen(PathBrowserScreen.iris(this,initial,chosen->{input=chosen.toAbsolutePath().toString();convert();}));
        }));browse.active=!busy;
        source=addRenderableWidget(new EditBox(font,x,top+35,Math.max(40,w-78),22,Component.literal("Source path (optional)")));
        source.setMaxLength(4096);source.setValue(input);source.setResponder(value->input=value);
        source.active=!busy;
        addRenderableWidget(StudioUi.button(x+w-74,top+35,74,22,"Import path","Import the path typed on the left.",this::convert)).active=!busy;
        int half=(w-4)/2;
        addRenderableWidget(StudioUi.button(x,height-58,half,24,"Open project","Open the converted source, or the first editable original when manual conversion is needed.",this::openProject)).active=!busy&&report!=null;
        addRenderableWidget(StudioUi.button(x+half+4,height-58,half,24,"Export ZIP","Archive this complete imported project, including preserved originals and compatibility report.",()->{
            String source=report.destination().getFileName()+"/IMPORT-REPORT.md";
            minecraft.gui.setScreen(new PackExportScreen(this,source,null));
        })).active=!busy&&report!=null;
        addRenderableWidget(StudioUi.button(x,height-28,w,24,"Back","Return to the studio.",this::onClose));
    }
    private void openProject(){
        String folder=report.destination().getFileName().toString();
        String converted=folder+(report.runnable()?"/assets/iris_imported/shaders/core/converted.fsh":"/conversion-candidate/converted.fsh");
        try{DevelopmentClient.workspace().read(converted);minecraft.gui.setScreen(new ShaderEditorScreen(this,converted));}
        catch(Exception missing){
            try{String first=DevelopmentClient.workspace().files().stream().filter(name->name.startsWith(folder+"/")).findFirst().orElseThrow();minecraft.gui.setScreen(new ShaderEditorScreen(this,first));}
            catch(Exception e){status="No editable source. Export ZIP includes IMPORT-REPORT.md and preserved originals.";}
        }
    }
    private void convert() {
        if (busy) return;
        if(input.isBlank()){status="Choose an Iris ZIP or directory first.";return;}
        final Path original;
        try { original = Path.of(input).toAbsolutePath(); }
        catch (Exception error) { status = "Choose a source path"; rebuildWidgets(); return; }
        Path output = DevelopmentClient.gameRoot().resolve("shaderpacks/greenmarine/iris-import-" + UUID.randomUUID().toString().substring(0, 8));
        busy = true; status = "Importing bounded source snapshot..."; rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try { return IrisImporter.convert(original, output); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((converted, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) { status = "Import failed: " + error.getCause().getMessage(); }
            else {
                report = converted;
                status = (converted.runnable() ? "Fullscreen candidate compiled" : "Manual work required; no selectable pack generated")
                    + " | " + converted.sourceFiles() + " original files preserved\n" + String.join("\n", converted.diagnostics());
                if (converted.runnable()) minecraft.reloadResourcePacks();
            }
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        StudioUi.background(graphics,width,height,title.getString(),"Development studio");
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.text(font,font.plainSubstrByWidth(status.replace('\n',' '),width-28),14,height-76,0xffffdd99,false);
        if(mouseY>=height-90)graphics.setTooltipForNextFrame(Component.literal(status),mouseX,mouseY);
    }
}
