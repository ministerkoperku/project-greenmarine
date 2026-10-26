package com.koper.greenmarine.development.probe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.koper.greenmarine.client.ResourcePacks;
import com.koper.greenmarine.client.ShaderRuntime;
import com.koper.greenmarine.development.*;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

/** Isolated title-menu GUI acceptance probe. It does not assert world rendering. */
public final class DevelopmentGuiProbe implements ClientModInitializer {
    private enum Stage {TITLE,SETTINGS,SETTINGS_CAPTURE,WORKBENCH,WORKBENCH_CAPTURE,SHADER,POPUP,POPUP_CAPTURE,COMPILE,SHADER_CAPTURE,RELOAD,SHADER_ERROR,SHADER_ERROR_CAPTURE,MATERIAL,MATERIAL_CAPTURE,MATERIAL_EXPORT,MATERIAL_EXPORT_CAPTURE,IRIS,IRIS_WAIT,IRIS_CAPTURE,PACK_EXPORT,PACK_WAIT,SDK_WIZARD,SDK_CREATED,SDK_CHECK,WORLD_CHECK,SDK_REFERENCE,SDK_REFERENCE_CAPTURE,AUTO_START,AUTO_BASELINE,AUTO_REJECT,AUTO_APPLY,DONE}
    private static final Path OUTPUT=Path.of(System.getProperty("greenmarine.development.probe.output","probe-output")).toAbsolutePath();
    private static final Map<String,Object> evidence=new LinkedHashMap<>();
    private static long started=System.nanoTime();
    private static Stage stage=Stage.TITLE;
    private static int ticks;
    private static long frames,stageFrame,compiledFrame=-1;
    public static void renderedFrame(){frames++;}
    private static boolean renderedFiveFrames(){return frames-stageFrame>=5;}
    private static boolean stopped;
    private static String lastScreen="";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("greenmarine-development-probe");
    private static volatile boolean captured;
    private static volatile Throwable asynchronousFailure;
    private static WorkbenchScreen workbench;
    private static ShaderEditorScreen shader;
    private static MaterialEditorScreen material;
    private static IrisImportScreen importer;
    private static Screen packExport;
    private static String sourcePath,packId,originalEditorSource;
    private static ShaderEditorScreen sdkEditor;
    private static long catalogGeneration,stageStarted=System.nanoTime(),autoGeneration;
    private static String validAutoSource;
    private static boolean autoEnabled,autoSourceBroken;
    @Override public void onInitializeClient(){
        started=System.nanoTime();
        LOG.info("GUI probe enabled; output={}",OUTPUT);
        try{writeReport("running",null);}catch(Exception failure){LOG.error("Cannot initialize GUI probe report",failure);}
    }
    public static void tick(Minecraft client){
        if(Boolean.getBoolean("greenmarine.development.workspaceProbe")){WorkspaceGuiProbe.tick(client);return;}
        if(stopped)return;
        try{
            if(asynchronousFailure!=null)throw new IllegalStateException("Asynchronous screenshot failed",asynchronousFailure);
            if(System.nanoTime()-started>120_000_000_000L)throw new IllegalStateException("120-second timeout at "+stage+"; screen="+client.gui.screen()+"; overlay="+client.gui.overlay());
            var current=client.gui.screen();String screenName=current==null?"none":current.getClass().getSimpleName();
            if(!screenName.equals(lastScreen)){
                lastScreen=screenName;evidence.put("lastScreen",screenName);LOG.info("GUI probe stage={}, screen={}, overlay={}",stage,screenName,client.gui.overlay());
                writeReport("running",null);
            }
            ticks++;
            switch(stage){
                case TITLE -> {
                    if(client.gui.overlay()==null&&screenName.equals("AccessibilityOnboardingScreen")){
                        evidence.put("firstRunOnboardingHandled",true);
                        for(var child:current.children())if(child instanceof Button button&&button.getMessage().getString().equals("Continue")){
                            LOG.info("GUI probe continuing first-run accessibility onboarding");button.onPress(new KeyEvent(InputConstants.KEY_RETURN,0,0));ticks=0;return;
                        }
                        // The isolated probe also supports localized onboarding labels.
                        client.options.onboardingAccessibilityFinished();client.gui.setScreen(new TitleScreen());ticks=0;return;
                    }
                    if(!(client.gui.screen() instanceof TitleScreen)||client.gui.overlay()!=null){ticks=0;return;}
                    if(ticks<10)return;
                    Files.createDirectories(OUTPUT);evidence.put("backend",ShaderRuntime.capabilities().backend());evidence.put("worldDrawVerified",false);
                    if(System.getenv("GREENMARINE_INPUT_ONLY")!=null){material=new MaterialEditorScreen(client.gui.screen());client.gui.setScreen(material);next(Stage.MATERIAL);return;}
                    var settings=new com.koper.greenmarine.client.ShaderScreen(client.gui.screen());client.gui.setScreen(settings);
                    next(Stage.SETTINGS);
                }
                case SETTINGS -> {
                    if(ticks<5||!renderedFiveFrames())return;capture(client,"settings.png");next(Stage.SETTINGS_CAPTURE);
                }
                case SETTINGS_CAPTURE -> {
                    if(!captured)return;
                    button(client.gui.screen(),"Dev tools").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(client.gui.screen() instanceof WorkbenchScreen,"Touch launcher failed");
                    workbench=(WorkbenchScreen)client.gui.screen();evidence.put("settingsTouchLauncher",true);next(Stage.WORKBENCH);
                }
                case WORKBENCH -> {
                    require(client.gui.screen()==workbench,"Workbench did not open");if(ticks<5||!renderedFiveFrames())return;
                    checkLayouts(workbench);evidence.put("workbenchOpened",true);capture(client,"workbench.png");next(Stage.WORKBENCH_CAPTURE);
                }
                case WORKBENCH_CAPTURE -> {
                    if(!captured)return;
                    int originalScale=client.options.guiScale().get(),originalWidth=workbench.width;
                    button(workbench,"UI size").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    var scaleScreen=client.gui.screen();checkLayouts(scaleScreen);
                    button(scaleScreen,"1x").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(client.getWindow().getGuiScale()==1,"UI scale did not apply");
                    scaleScreen.onClose();require(workbench.width>originalWidth,"Smaller UI did not enlarge workspace");
                    client.options.guiScale().set(originalScale);client.options.save();client.resizeGui();
                    evidence.put("uiScaleChangesActualWorkspace",true);
                    sourcePath=DevelopmentClient.workspace().createExample();
                    String folder=sourcePath.substring(0,sourcePath.indexOf('/'));
                    String manifest=DevelopmentClient.workspace().read(folder+"/assets/development/greenmarine/packs/preview.json");
                    packId=JsonParser.parseString(manifest).getAsJsonObject().get("id").getAsString();
                    evidence.put("shaderSource",sourcePath);evidence.put("createdPackId",packId);
                    shader=new ShaderEditorScreen(workbench,sourcePath);client.gui.setScreen(shader);next(Stage.SHADER);
                }
                case SHADER -> {
                    require(client.gui.screen()==shader,"Shader editor did not open");if(ticks<5)return;
                    var editor=(CodeEditBox)field(shader,"editor");
                    checkLayouts(shader);editor=(CodeEditBox)field(shader,"editor");
                    require(editor.getHeight()>=shader.height/2,"Code area still uses less than half the window");
                    originalEditorSource=editor.getValue();
                    editor.setValue("void main() {");editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    require(editor.getValue().equals("void main() {\n    "),"SDL Enter did not auto-indent");editor.keyPressed(new KeyEvent(InputConstants.KEY_TAB,0,0));
                    require(editor.getValue().endsWith("        "),"SDL Tab did not indent");evidence.put("sdlKeyboardIndentation",true);
                    editor.setValue("void main(){ vec3 v = ");for(int cp:"norm".codePoints().toArray())shader.charTyped(new net.minecraft.client.input.CharacterEvent(cp));
                    require(editor.hasCompletion(),"Typing did not produce suggestions");next(Stage.POPUP);
                }
                case POPUP -> {
                    if(ticks<5||!renderedFiveFrames())return;
                    var editor=(CodeEditBox)field(shader,"editor");require(field(editor,"popup")!=null,"Automatic caret popup did not render");
                    capture(client,"automatic-completion.png");next(Stage.POPUP_CAPTURE);
                }
                case POPUP_CAPTURE -> {
                    if(!captured)return;var editor=(CodeEditBox)field(shader,"editor");var popup=field(editor,"popup");
                    int x=(int)field(popup,"x"),y=(int)field(popup,"y");
                    var click=new MouseButtonEvent(x+6,y+6,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));shader.mouseClicked(click,false);shader.mouseReleased(click);
                    require(shader.getFocused()==editor,"Popup click lost text focus");require(editor.getValue().equals("void main(){ vec3 v = normalize()"),"Popup insertion changed wrong source: "+editor.getValue());
                    editor.setValue("norm");shader.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE,0,0));
                    require(client.gui.screen()==shader&&!editor.hasCompletion(),"Escape closed editor instead of popup");evidence.put("automaticCaretPopupAndEscape",true);
                    editor.setValue(originalEditorSource+"\n// fragment draft\n");editor.moveCursor(5);
                    String vertex=sourcePath.replace(".fsh",".vsh"),oldVertex=DevelopmentClient.workspace().read(vertex);
                    invokeString(shader,"openFile",vertex);((CodeEditBox)field(shader,"editor")).setValue(oldVertex+"\n// vertex draft\n");
                    invokeString(shader,"openFile",sourcePath);editor=(CodeEditBox)field(shader,"editor");
                    require(editor.getValue().contains("fragment draft")&&editor.cursorPosition()==5,"File switch lost draft or caret");
                    Object project=field(shader,"project");var draftsMethod=project.getClass().getDeclaredMethod("drafts");draftsMethod.setAccessible(true);
                    require(((Map<?,?>)draftsMethod.invoke(project)).size()==2,"Multiple file drafts were not retained");
                    editor.setValue(originalEditorSource);invokeString(shader,"openFile",vertex);((CodeEditBox)field(shader,"editor")).setValue(oldVertex);invokeString(shader,"openFile",sourcePath);
                    button(shader,"Files").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));checkLayouts(shader);
                    button(shader,"Manage files...").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));checkLayouts(client.gui.screen());client.gui.screen().onClose();
                    button(shader,"Files").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    evidence.put("multipleDocumentDraftsAndCaret",true);invoke(shader,"compile");next(Stage.COMPILE);
                }
                case COMPILE -> {
                    ShaderCompiler.Result result=(ShaderCompiler.Result)field(shader,"result");
                    if((boolean)field(shader,"busy"))return;
                    require(result!=null&&result.success(),"Shader compile failed: "+field(shader,"diagnostic"));
                    Button apply=(Button)field(shader,"apply");require(apply.active,"Save + reload is inactive after successful compile");
                    evidence.put("shaderCompiled",true);evidence.put("spirvBytes",result.spirv().length);evidence.put("saveReloadEnabled",true);
                    if(compiledFrame<0)compiledFrame=frames;
                    if(ticks<5||frames-compiledFrame<5)return;capture(client,"shader-editor.png");next(Stage.SHADER_CAPTURE);
                }
                case SHADER_CAPTURE -> {
                    if(!captured)return;catalogGeneration=ResourcePacks.CATALOG.generation();invoke(shader,"apply");next(Stage.RELOAD);
                }
                case RELOAD -> {
                    String diagnostic=(String)field(shader,"diagnostic");
                    require(!diagnostic.startsWith("Save failed")&&!diagnostic.startsWith("Reload failed"),diagnostic);
                    if(client.gui.overlay()!=null||!diagnostic.startsWith("Reloaded.")||ResourcePacks.CATALOG.generation()<=catalogGeneration)return;
                    require(ResourcePacks.CATALOG.get(packId).isPresent(),"Reloaded catalog omits created shaderpack");
                    evidence.put("shaderSavedAndReloaded",true);evidence.put("catalogGeneration",ResourcePacks.CATALOG.generation());
                    shader.onClose();
                    String externallyEdited=DevelopmentClient.workspace().read(sourcePath)+"\n// external editor change\n";
                    DevelopmentClient.workspace().save(sourcePath,externallyEdited);
                    shader=new ShaderEditorScreen(workbench,sourcePath);client.gui.setScreen(shader);
                    require(externallyEdited.equals(field(shader,"text")),"Clean shader reopen hid an external file change behind a stale draft");
                    evidence.put("cleanReopenSeesExternalEdit",true);
                    ((CodeEditBox)field(shader,"editor")).setValue(externallyEdited+"void broken(){ nope; }\n");
                    invoke(shader,"compile");next(Stage.SHADER_ERROR);
                }
                case SHADER_ERROR -> {
                    if((boolean)field(shader,"busy"))return;
                    require(field(shader,"result") instanceof ShaderCompiler.Result r&&!r.success(),"Broken source compiled");
                    var issues=(java.util.List<?>)field(shader,"issues");
                    require(!issues.isEmpty(),"Compiler error was not mapped to an editor line: "+field(shader,"diagnostic"));
                    evidence.put("errorLine",((CompilerDiagnostics.Issue)issues.getFirst()).line());
                    if(ticks<5||!renderedFiveFrames())return;capture(client,"shader-editor-error.png");next(Stage.SHADER_ERROR_CAPTURE);
                }
                case SHADER_ERROR_CAPTURE -> {
                    if(!captured)return;
                    ((CodeEditBox)field(shader,"editor")).setValue(DevelopmentClient.workspace().read(sourcePath));shader.onClose();
                    material=new MaterialEditorScreen(workbench);client.gui.setScreen(material);next(Stage.MATERIAL);
                }
                case MATERIAL -> {
                    if(ticks<5)return;require(client.gui.screen()==material,"Material editor did not open");
                    checkLayouts(material);
                    button(material,"Texture").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    material.mouseScrolled(100,150,0,-3);
                    ((EditBox)field(material,"dimensions")).setValue("256x256");
                    require(button(material,"New canvas").visible,"New canvas inaccessible after scrolling");
                    button(material,"New canvas").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(((MaterialCanvas)field(material,"canvas")).width()==256,"256x256 canvas was not created");
                    require((int)field(material,"brush")==1,"Regression requires a one-texel brush");
                    invoke(material,"loadAlbedo");require(field(material,"albedo")!=null,"Craftbot fullpack albedo did not load");
                    int x=(int)field(material,"canvasX"),y=(int)field(material,"canvasY"),size=(int)field(material,"canvasSize");
                    // Virtual touchpad: tap sends press+release, then cursor movement without a held button.
                    var window=client.getWindow();
                    double startX=x+size/3.0,startY=y+size/3.0,endX=startX+12,endY=startY+12;
                    movePointer(client,startX,startY);
                    client.mouseHandler.onButton(window.handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0),1);
                    client.mouseHandler.onButton(window.handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0),0);
                    movePointer(client,endX,endY);
                    var pointerCanvas=(MaterialCanvas)field(material,"canvas");
                    int expectedX=(int)((endX-x)*pointerCanvas.width()/size),expectedY=(int)((endY-y)*pointerCanvas.height()/size);
                    require(pointerCanvas.pixel(expectedX,expectedY)==0xffffffff,"Virtual touchpad tap-release-move does not draw");
                    client.mouseHandler.onButton(window.handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0),1);
                    client.mouseHandler.onButton(window.handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0),0);
                    evidence.put("virtualMouseTapMovePainted",true);
                    button(material,"Pen").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    var press=new MouseButtonEvent(x+size/2.0,y+size/2.0,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    var drag=new MouseButtonEvent(x+size/2.0+8,y+size/2.0+8,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(material.mouseClicked(press,false),"Canvas click not consumed");require(material.mouseDragged(drag,8,8),"Canvas drag not consumed");require(material.mouseReleased(drag),"Canvas release not consumed");
                    button(material,"+").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));require((int)field(material,"zoom")>1,"Touch zoom button did not zoom");
                    button(material,"Drag").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));require((boolean)field(material,"touchPan"),"Touch pan toggle did not enable");
                    int beforePan=(int)field(material,"viewX");
                    require(material.mouseClicked(press,false),"Touch pan press not consumed");require(material.mouseDragged(drag,8,8),"Touch pan drag not consumed");require(material.mouseReleased(drag),"Touch pan release not consumed");
                    require((int)field(material,"viewX")!=beforePan,"Touch pan did not move viewport");
                    button(material,"Pan").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));button(material,"-").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require((int)field(material,"zoom")==1&&!(boolean)field(material,"touchPan"),"Touch controls did not restore painting view");
                    evidence.put("materialTouchZoomAndPan",true);
                    var canvas=(MaterialCanvas)field(material,"canvas");require(canvas.pixel(canvas.width()/2,canvas.height()/2)==0xffffffff,"White material stroke missing");
                    Button save=material.children().stream().filter(child->child instanceof Button button&&button.getMessage().getString().equals("Save")).map(child->(Button)child).findFirst().orElseThrow();
                    save.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));require(!((String)field(material,"status")).startsWith("Error"),"Material save failed: "+field(material,"status"));
                    var saved=MaterialCanvas.paths(DevelopmentClient.gameRoot(),"mechanics_dream:textures/craftbot.png");
                    require(Files.isRegularFile(saved.json())&&Files.isRegularFile(saved.mask()),"Saved PNG/JSON missing");
                    var reopened=MaterialCanvas.load(DevelopmentClient.gameRoot(),"mechanics_dream:textures/craftbot.png");require(reopened.pixel(reopened.width()/2,reopened.height()/2)==0xffffffff,"Saved white mask cannot reopen");
                    evidence.put("craftbotAlbedoLoaded",true);evidence.put("materialMouseStroke",true);evidence.put("materialPngJsonSaved",true);
                    evidence.put("materialCanvasWidth",canvas.width());evidence.put("materialCanvasHeight",canvas.height());
                    evidence.put("materialPack",saved.pack().toString());evidence.put("materialPackAutoEnabled",false);
                    button(material,"Mask").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    next(Stage.MATERIAL_CAPTURE);
                }
                case MATERIAL_CAPTURE -> {
                    if(ticks>=5&&renderedFiveFrames()){
                        int[] pixels=(int[])field(material,"previewPixels");
                        require(java.util.Arrays.stream(pixels).anyMatch(pixel->pixel==0xffffffff),"One-texel white stroke invisible in rendered preview");
                        evidence.put("oneTexelStrokeVisibleInPreview",true);
                        capture(client,"material.png");next(Stage.MATERIAL_EXPORT);
                    }
                }
                case MATERIAL_EXPORT -> {
                    if(!captured)return;
                    button(material,"Save as").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    next(Stage.MATERIAL_EXPORT_CAPTURE);
                }
                case MATERIAL_EXPORT_CAPTURE -> {
                    if(ticks<5||!renderedFiveFrames())return;
                    var picker=client.gui.screen();checkLayouts(picker);require(picker.getClass().getSimpleName().equals("PathBrowserScreen"),"Save as did not open path browser");
                    if(ticks==5){capture(client,"save-browser.png");return;}
                    if(!captured)return;
                    Path chosen=OUTPUT.resolve("chosen material pack");
                    ((EditBox)field(picker,"filename")).setValue(chosen.toString());
                    button(picker,"Save in this pack").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    if(client.gui.screen()==picker)button(picker,"Save in this pack").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(client.gui.screen()==material,"Path picker did not return to painter");
                    require(Files.isRegularFile(chosen.resolve("pack.mcmeta")),"Custom pack destination was not saved");
                    require(MaterialCanvas.loadFromPack(chosen,"mechanics_dream:textures/craftbot.png").pixel(128,128)==0xffffffff,"Custom saved pack lost stroke");
                    evidence.put("customMaterialDestination",chosen.toString());
                    importer=new IrisImportScreen(workbench);client.gui.setScreen(importer);next(Stage.IRIS);
                }
                case IRIS -> {
                    if(ticks<5)return;
                    checkLayouts(importer);
                    Path fixture=OUTPUT.resolve("gui-iris-fixture.zip");
                    try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(fixture))){
                        zip.putNextEntry(new java.util.zip.ZipEntry("Wrapped/shaders/final.fsh"));zip.write("#version 120\nuniform sampler2D colortex0; varying vec2 texcoord; void main(){gl_FragColor=texture2D(colortex0,texcoord);}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                        zip.putNextEntry(new java.util.zip.ZipEntry("Wrapped/LICENSE.txt"));zip.write("fixture license".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                    }
                    button(importer,"Browse and import Iris ZIP / folder").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    var browser=client.gui.screen();checkLayouts(browser);
                    ((EditBox)field(browser,"location")).setValue(OUTPUT.toString());button(browser,"Go").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    button(browser,"ZIP  gui-iris-fixture.zip").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(client.gui.screen()==importer,"Iris browser did not return to importer");next(Stage.IRIS_WAIT);
                }
                case IRIS_WAIT -> {
                    require(stageSeconds()<30,"Iris conversion timeout");if((boolean)field(importer,"busy")||client.gui.overlay()!=null){stageFrame=frames;return;}
                    var report=(IrisImporter.Report)field(importer,"report");require(report!=null&&report.runnable(),"Wrapped Iris import failed: "+field(importer,"status"));
                    require(Files.readString(report.destination().resolve("iris-source/LICENSE.txt")).equals("fixture license"),"Iris import lost license");
                    evidence.put("irisBrowserImportWrappedZip",true);if(ticks<5||!renderedFiveFrames())return;
                    capture(client,"iris-import.png");next(Stage.IRIS_CAPTURE);
                }
                case IRIS_CAPTURE -> {
                    if(!captured)return;button(importer,"Export ZIP").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));packExport=client.gui.screen();checkLayouts(packExport);next(Stage.PACK_EXPORT);
                }
                case PACK_EXPORT -> {
                    button(packExport,"Choose destination and export ZIP").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));var browser=client.gui.screen();
                    ((EditBox)field(browser,"filename")).setValue(OUTPUT.resolve("complete-shaderpack.zip").toString());
                    button(browser,"Save file").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));if(client.gui.screen()==browser)button(browser,"Save file").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    next(Stage.PACK_WAIT);
                }
                case PACK_WAIT -> {
                    require(stageSeconds()<20,"Pack export timeout");if((boolean)field(packExport,"busy"))return;
                    Path file=(Path)field(packExport,"exported");require(file!=null,"Pack export failed: "+field(packExport,"status"));
                    try(var zip=new java.util.zip.ZipFile(file.toFile())){
                        require(zip.getEntry("assets/iris_imported/greenmarine/packs/imported.json")!=null,"Exported ZIP has no manifest");
                        require(zip.getEntry("assets/iris_imported/shaders/core/converted.fsh")!=null,"Exported ZIP has no converted shader");
                        require(zip.getEntry("iris-source/shaders/final.fsh")!=null&&zip.getEntry("IMPORT-REPORT.md")!=null,"Exported ZIP omitted originals/report");
                    }
                    evidence.put("completeShaderpackZipExport",file.toString());capture(client,"pack-export.png");next(Stage.SDK_WIZARD);
                }
                case SDK_WIZARD -> {
                    if(!captured)return;client.gui.setScreen(workbench);button(workbench,"New shaderpack project").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    var wizard=client.gui.screen();checkLayouts(wizard);
                    for(var child:wizard.children())if(child instanceof EditBox box){if(box.getMessage().getString().equals("Namespace"))box.setValue("sdk_gui_"+Long.toHexString(System.nanoTime()&0xffffff));else if(box.getMessage().getString().equals("Pack name"))box.setValue("SDK GUI compute project");}
                    button(wizard,"compute").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));button(wizard,"Create project").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(client.gui.screen() instanceof ShaderEditorScreen,"SDK project wizard failed");sdkEditor=(ShaderEditorScreen)client.gui.screen();next(Stage.SDK_CREATED);
                }
                case SDK_CREATED -> {
                    checkLayouts(sdkEditor);button(sdkEditor,"Check").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));next(Stage.SDK_CHECK);
                }
                case SDK_CHECK -> {
                    require(stageSeconds()<25,"SDK project validation timeout");if((boolean)field(sdkEditor,"busy"))return;
                    String diagnostic=(String)field(sdkEditor,"diagnostic");require(diagnostic.startsWith("Validation passed"),"SDK generated project did not validate: "+diagnostic);
                    evidence.put("sdkComputeProjectCreatedAndValidated",true);
                    client.gui.setScreen(workbench);button(workbench,"New shaderpack project").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));var worldWizard=client.gui.screen();
                    button(worldWizard,"world").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));button(worldWizard,"Create project").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    require(client.gui.screen() instanceof ShaderEditorScreen,"World wizard failed");sdkEditor=(ShaderEditorScreen)client.gui.screen();button(sdkEditor,"Check").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));next(Stage.WORLD_CHECK);
                }
                case WORLD_CHECK -> {
                    require(stageSeconds()<30,"World project validation timed out");if((boolean)field(sdkEditor,"busy"))return;
                    String status=(String)field(sdkEditor,"diagnostic");require(status.startsWith("Validation passed"),"SDK world starter validation failed: "+status);evidence.put("sdkWorldProjectValidated",true);
                    button(sdkEditor,"SDK").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));checkLayouts(client.gui.screen());
                    var browser=client.gui.screen();for(var child:browser.children())if(child instanceof EditBox box){box.setValue("reference/authoring");break;}
                    var entry=browser.children().stream().filter(c->c instanceof Button b&&!b.getMessage().getString().equals("Back to project")&&!b.getMessage().getString().equals("Previous")&&!b.getMessage().getString().contains("Next")).map(c->(Button)c).findFirst().orElseThrow();entry.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
                    next(Stage.SDK_REFERENCE);
                }
                case SDK_REFERENCE -> {
                    if(ticks<5||!renderedFiveFrames())return;checkLayouts(client.gui.screen());capture(client,"embedded-sdk.png");next(Stage.SDK_REFERENCE_CAPTURE);
                }
                case SDK_REFERENCE_CAPTURE -> {
                    if(!captured)return;evidence.put("embeddedSdkSearchAndReader",true);next(Stage.AUTO_START);
                }
                case AUTO_START -> {
                    if(!captured)return;
                    validAutoSource=DevelopmentClient.workspace().read(sourcePath);
                    autoReload(true);autoEnabled=true;next(Stage.AUTO_BASELINE);
                }
                case AUTO_BASELINE -> {
                    String status=DevelopmentClient.reloadStatus().toLowerCase(Locale.ROOT);
                    require(stageSeconds()<10,"Auto reload baseline timeout: "+DevelopmentClient.reloadStatus());
                    if(!status.contains("baseline")&&!status.contains("no changes"))return;
                    autoGeneration=ResourcePacks.CATALOG.generation();
                    DevelopmentClient.workspace().save(sourcePath,"#version 450\nthis is intentionally broken GLSL;\n");autoSourceBroken=true;
                    next(Stage.AUTO_REJECT);
                }
                case AUTO_REJECT -> {
                    String raw=DevelopmentClient.reloadStatus(),status=raw.toLowerCase(Locale.ROOT);
                    require(ResourcePacks.CATALOG.generation()==autoGeneration,"Broken shader triggered a runtime catalog reload");
                    require(stageSeconds()<10,"Auto reload did not reject broken shader: "+raw);
                    String name=sourcePath.substring(sourcePath.lastIndexOf('/')+1);
                    if(!(status.contains("failed")||status.contains("reject"))||!raw.contains(name))return;
                    evidence.put("autoReloadRejectedBrokenShader",true);evidence.put("autoReloadRejection",raw);
                    DevelopmentClient.workspace().save(sourcePath,validAutoSource+"\n// auto reload valid edit\n");autoSourceBroken=false;
                    next(Stage.AUTO_APPLY);
                }
                case AUTO_APPLY -> {
                    String status=DevelopmentClient.reloadStatus().toLowerCase(Locale.ROOT);
                    require(stageSeconds()<15,"Auto reload did not apply valid shader: "+DevelopmentClient.reloadStatus());
                    if(client.gui.overlay()!=null||ResourcePacks.CATALOG.generation()<=autoGeneration||!status.contains("reload")||!status.contains("complete"))return;
                    require(ResourcePacks.CATALOG.get(packId).isPresent(),"Auto reload dropped generated shaderpack");
                    evidence.put("autoReloadAppliedValidEdit",true);evidence.put("autoReloadCatalogGeneration",ResourcePacks.CATALOG.generation());
                    autoReload(false);autoEnabled=false;next(Stage.DONE);
                }
                case DONE -> {if(captured)finish(client,null);}
            }
        }catch(Throwable failure){finish(client,failure);}
    }
    private static void movePointer(Minecraft client,double x,double y){
        var w=client.getWindow();double px=x*w.getWidth()/w.getGuiScaledWidth(),py=y*w.getHeight()/w.getGuiScaledHeight();
        client.mouseHandler.onMove(w.handle(),px,py,px-client.mouseHandler.xpos(),py-client.mouseHandler.ypos());
        client.mouseHandler.handleAccumulatedMovement();
    }
    private static void checkLayouts(Screen screen) {
        int originalWidth=screen.width,originalHeight=screen.height;
        for(int[] size:new int[][]{{320,180},{360,240},{480,270},{854,480}}){
            screen.init(size[0],size[1]);
            var widgets=screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.AbstractWidget w&&w.visible).map(c->(net.minecraft.client.gui.components.AbstractWidget)c).toList();
            for(var widget:widgets){
                require(widget.getX()>=0&&widget.getY()>=0&&widget.getRight()<=size[0]&&widget.getBottom()<=size[1],screen.getClass().getSimpleName()+" offscreen "+widget.getMessage().getString()+" at "+size[0]+"x"+size[1]);
                for(var other:widgets)if(other!=widget){
                    boolean overlap=widget.getX()<other.getRight()&&widget.getRight()>other.getX()&&widget.getY()<other.getBottom()&&widget.getBottom()>other.getY();
                    require(!overlap,screen.getClass().getSimpleName()+" overlapping "+widget.getMessage().getString()+" / "+other.getMessage().getString()+" at "+size[0]+"x"+size[1]);
                }
            }
        }
        screen.init(originalWidth,originalHeight);
        evidence.put(screen.getClass().getSimpleName()+"ResponsiveLayout",true);
    }
    private static double stageSeconds(){return (System.nanoTime()-stageStarted)/1_000_000_000.0;}
    private static void autoReload(boolean enabled)throws Exception{
        Class<?> preferences=Class.forName("com.koper.greenmarine.development.DevelopmentPreferences");
        var constructor=preferences.getDeclaredConstructor(boolean.class,boolean.class,String.class);constructor.setAccessible(true);
        var setter=DevelopmentClient.class.getDeclaredMethod("preferences",preferences);setter.setAccessible(true);
        setter.invoke(null,constructor.newInstance(enabled,false,""));
    }
    private static Button button(Screen screen,String label){return screen.children().stream().filter(child->child instanceof Button button&&button.getMessage().getString().equals(label)).map(child->(Button)child).findFirst().orElseThrow(()->new IllegalStateException("Button missing: "+label));}
    private static Object field(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void invokeString(Object object,String name,String value)throws Exception{var method=object.getClass().getDeclaredMethod(name,String.class);method.setAccessible(true);try{method.invoke(object,value);}catch(InvocationTargetException e){throw new IllegalStateException(name+" failed",e.getCause());}}
    private static void invoke(Object object,String name)throws Exception{
        var method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);
        try{method.invoke(object);}catch(InvocationTargetException failure){throw new IllegalStateException(name+" failed",failure.getCause());}
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static void next(Stage next)throws Exception{stage=next;ticks=0;stageFrame=frames;stageStarted=System.nanoTime();LOG.info("GUI probe stage={}",stage);writeReport("running",null);}
    private static void capture(Minecraft client,String name){
        captured=false;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try{Files.createDirectories(OUTPUT);image.writeToFile(OUTPUT.resolve(name));captured=true;}
            catch(Throwable failure){asynchronousFailure=failure;}finally{image.close();}
        });
    }
    private static void writeReport(String outcome,Throwable failure)throws Exception{
        var report=new LinkedHashMap<String,Object>(evidence);report.put("outcome",outcome);report.put("stage",stage.name());report.put("seconds",(System.nanoTime()-started)/1_000_000_000.0);
        report.put("scope","Title-menu GUI rendering, shader compilation/reload and editable material files; no world draw assertion");
        if(failure!=null){var text=new StringWriter();failure.printStackTrace(new PrintWriter(text));report.put("error",failure.toString());report.put("stack",text.toString());}
        Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("probe-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static void finish(Minecraft client,Throwable failure){
        if(stopped)return;stopped=true;
        try{
            if(autoEnabled){autoReload(false);autoEnabled=false;}
            if(autoSourceBroken&&validAutoSource!=null){DevelopmentClient.workspace().save(sourcePath,validAutoSource);autoSourceBroken=false;}
        }catch(Exception cleanup){if(failure!=null)failure.addSuppressed(cleanup);else failure=cleanup;}
        try{writeReport(failure==null?"passed":"failed",failure);}catch(Exception reportFailure){reportFailure.printStackTrace();}
        if(failure!=null)failure.printStackTrace();client.stop();
    }
}
