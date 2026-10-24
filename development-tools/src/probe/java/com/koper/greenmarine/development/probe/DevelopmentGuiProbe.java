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
    private enum Stage {TITLE,WORKBENCH,WORKBENCH_CAPTURE,SHADER,COMPILE,SHADER_CAPTURE,RELOAD,SHADER_ERROR,SHADER_ERROR_CAPTURE,MATERIAL,MATERIAL_CAPTURE,AUTO_START,AUTO_BASELINE,AUTO_REJECT,AUTO_APPLY,DONE}
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
    private static String sourcePath,packId;
    private static long catalogGeneration,stageStarted=System.nanoTime(),autoGeneration;
    private static String validAutoSource;
    private static boolean autoEnabled,autoSourceBroken;
    @Override public void onInitializeClient(){
        started=System.nanoTime();
        LOG.info("GUI probe enabled; output={}",OUTPUT);
        try{writeReport("running",null);}catch(Exception failure){LOG.error("Cannot initialize GUI probe report",failure);}
    }
    public static void tick(Minecraft client){
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
                    var settings=new com.koper.greenmarine.client.ShaderScreen(client.gui.screen());client.gui.setScreen(settings);
                    var launcher=settings.children().stream().filter(child->child instanceof Button button&&button.getMessage().getString().equals("Dev tools")).map(child->(Button)child).findFirst().orElseThrow(()->new IllegalStateException("Touch launcher absent from GreenMarine settings"));
                    launcher.onPress(new MouseButtonInfo(0,0));require(client.gui.screen() instanceof WorkbenchScreen,"Touch launcher failed");
                    workbench=(WorkbenchScreen)client.gui.screen();evidence.put("settingsTouchLauncher",true);next(Stage.WORKBENCH);
                }
                case WORKBENCH -> {
                    require(client.gui.screen()==workbench,"Workbench did not open");if(ticks<5||!renderedFiveFrames())return;
                    evidence.put("workbenchOpened",true);capture(client,"workbench.png");next(Stage.WORKBENCH_CAPTURE);
                }
                case WORKBENCH_CAPTURE -> {
                    if(!captured)return;
                    sourcePath=DevelopmentClient.workspace().createExample();
                    String folder=sourcePath.substring(0,sourcePath.indexOf('/'));
                    String manifest=DevelopmentClient.workspace().read(folder+"/assets/development/greenmarine/packs/preview.json");
                    packId=JsonParser.parseString(manifest).getAsJsonObject().get("id").getAsString();
                    evidence.put("shaderSource",sourcePath);evidence.put("createdPackId",packId);
                    shader=new ShaderEditorScreen(workbench,sourcePath);client.gui.setScreen(shader);next(Stage.SHADER);
                }
                case SHADER -> {
                    require(client.gui.screen()==shader,"Shader editor did not open");if(ticks<5)return;
                    invoke(shader,"compile");next(Stage.COMPILE);
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
                    ((EditBox)field(material,"dimensions")).setValue("256x256");button(material,"New").onPress(new MouseButtonInfo(0,0));
                    require(((MaterialCanvas)field(material,"canvas")).width()==256,"256x256 canvas was not created");
                    // A broad visible white stroke stays legible in the sampled 256px preview.
                    button(material,"Brush: 1").onPress(new MouseButtonInfo(0,0));button(material,"Brush: 2").onPress(new MouseButtonInfo(0,0));
                    invoke(material,"loadAlbedo");require(field(material,"albedo")!=null,"Craftbot fullpack albedo did not load");
                    int x=(int)field(material,"canvasX"),y=(int)field(material,"canvasY"),size=(int)field(material,"canvasSize");
                    var press=new MouseButtonEvent(x+size/2.0,y+size/2.0,new MouseButtonInfo(0,0));
                    var drag=new MouseButtonEvent(x+size/2.0+8,y+size/2.0+8,new MouseButtonInfo(0,0));
                    require(material.mouseClicked(press,false),"Canvas click not consumed");require(material.mouseDragged(drag,8,8),"Canvas drag not consumed");require(material.mouseReleased(drag),"Canvas release not consumed");
                    button(material,"+").onPress(new MouseButtonInfo(0,0));require((int)field(material,"zoom")>1,"Touch zoom button did not zoom");
                    button(material,"Paint").onPress(new MouseButtonInfo(0,0));require((boolean)field(material,"touchPan"),"Touch pan toggle did not enable");
                    int beforePan=(int)field(material,"viewX");
                    require(material.mouseClicked(press,false),"Touch pan press not consumed");require(material.mouseDragged(drag,8,8),"Touch pan drag not consumed");require(material.mouseReleased(drag),"Touch pan release not consumed");
                    require((int)field(material,"viewX")!=beforePan,"Touch pan did not move viewport");
                    button(material,"Pan").onPress(new MouseButtonInfo(0,0));button(material,"-").onPress(new MouseButtonInfo(0,0));
                    require((int)field(material,"zoom")==1&&!(boolean)field(material,"touchPan"),"Touch controls did not restore painting view");
                    evidence.put("materialTouchZoomAndPan",true);
                    var canvas=(MaterialCanvas)field(material,"canvas");require(canvas.pixel(canvas.width()/2,canvas.height()/2)==0xffffffff,"White material stroke missing");
                    Button save=material.children().stream().filter(child->child instanceof Button button&&button.getMessage().getString().equals("Save pack")).map(child->(Button)child).findFirst().orElseThrow();
                    save.onPress(new MouseButtonInfo(0,0));require(!((String)field(material,"status")).startsWith("Error"),"Material save failed: "+field(material,"status"));
                    var saved=MaterialCanvas.paths(DevelopmentClient.gameRoot(),"mechanics_dream:textures/craftbot.png");
                    require(Files.isRegularFile(saved.json())&&Files.isRegularFile(saved.mask()),"Saved PNG/JSON missing");
                    var reopened=MaterialCanvas.load(DevelopmentClient.gameRoot(),"mechanics_dream:textures/craftbot.png");require(reopened.pixel(reopened.width()/2,reopened.height()/2)==0xffffffff,"Saved white mask cannot reopen");
                    evidence.put("craftbotAlbedoLoaded",true);evidence.put("materialMouseStroke",true);evidence.put("materialPngJsonSaved",true);
                    evidence.put("materialCanvasWidth",canvas.width());evidence.put("materialCanvasHeight",canvas.height());
                    evidence.put("materialPack",saved.pack().toString());evidence.put("materialPackAutoEnabled",false);
                    // Leave the painted editor visible for several complete render frames before capture.
                    next(Stage.MATERIAL_CAPTURE);
                }
                case MATERIAL_CAPTURE -> {
                    if(ticks>=5&&renderedFiveFrames()){
                        capture(client,"material.png");next(Stage.AUTO_START);
                    }
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
    private static double stageSeconds(){return (System.nanoTime()-stageStarted)/1_000_000_000.0;}
    private static void autoReload(boolean enabled)throws Exception{
        Class<?> preferences=Class.forName("com.koper.greenmarine.development.DevelopmentPreferences");
        var constructor=preferences.getDeclaredConstructor(boolean.class,boolean.class,String.class);constructor.setAccessible(true);
        var setter=DevelopmentClient.class.getDeclaredMethod("preferences",preferences);setter.setAccessible(true);
        setter.invoke(null,constructor.newInstance(enabled,false,""));
    }
    private static Button button(Screen screen,String label){return screen.children().stream().filter(child->child instanceof Button button&&button.getMessage().getString().equals(label)).map(child->(Button)child).findFirst().orElseThrow(()->new IllegalStateException("Button missing: "+label));}
    private static Object field(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
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
