package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Exports the whole project; current editor text can be overlaid without saving it to the pack. */
final class PackExportScreen extends Screen {
    private final Screen parent;
    private final String source,draft;
    private ShaderProject project;
    private java.util.Map<String,String> projectDrafts;
    private java.util.Map<String,byte[]> imageDrafts=java.util.Map.of();
    private boolean includeDraft=true,busy;
    private String status="Exports shaders, includes, textures, scripts and pack manifests.";
    private Path exported;
    PackExportScreen(Screen parent,String source,String draft){super(Component.literal("Export shaderpack"));this.parent=parent;this.source=source;this.draft=draft;includeDraft=draft!=null;}
    PackExportScreen(Screen parent,ShaderProject project){this(parent,project.folder()+"/.project-anchor",null);this.project=project;projectDrafts=project.drafts();try{imageDrafts=project.imageDrafts();}catch(Exception e){status="Cannot snapshot image drafts: "+e.getMessage();}includeDraft=true;}
    @Override protected void init(){
        int x=14,w=width-28;
        addRenderableWidget(StudioUi.button(x,62,w,24,"Include unsaved edits: "+(includeDraft?"On":"Off"),"On includes unsaved source in the ZIP. The workspace files stay unchanged. Drafts may contain compilation errors.",()->{includeDraft=!includeDraft;rebuildWidgets();})).active=!busy&&(draft!=null||project!=null);
        addRenderableWidget(StudioUi.button(x,94,w,28,busy?"Exporting...":"Choose destination and export ZIP","Archive contains the project contents at ZIP root, ready for sharing or installation when the pack is runnable.",this::choose)).active=!busy;
        addRenderableWidget(StudioUi.button(x,height-32,w,24,"Back","Return to your editor.",this::onClose));
    }
    private void choose(){
        String projectName=source.substring(0,source.indexOf('/'));
        Path initial=exported==null?DevelopmentClient.gameRoot().resolve(projectName+".zip"):exported;
        minecraft.gui.setScreen(new PathBrowserScreen(this,initial,false,target->{
            busy=true;status="Exporting the complete project...";
            boolean overlay=includeDraft;
            CompletableFuture.supplyAsync(()->{
                try{return project==null?ShaderPackExporter.exportZip(DevelopmentClient.gameRoot().resolve("shaderpacks/greenmarine"),source,overlay?draft:null,target):ShaderPackExporter.exportProject(project.workspace().root(),source,overlay?projectDrafts:java.util.Map.of(),overlay?imageDrafts:java.util.Map.of(),target);}
                catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
            }).whenComplete((result,error)->minecraft.execute(()->{
                busy=false;
                if(error==null){exported=result.file();status="Exported "+result.files()+" files to "+exported;}
                else status="Export failed: "+(error.getCause()==null?error:error.getCause()).getMessage();
                if(minecraft.gui.screen()==this)rebuildWidgets();
            }));
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
        StudioUi.background(g,width,height,"Export shaderpack","Complete project / ZIP archive");
        super.extractRenderState(g,mx,my,tick);
        g.text(font,font.plainSubstrByWidth(status,width-28),14,height-52,status.startsWith("Export failed")?0xffff8888:StudioUi.MUTED,false);
        if(my>height-66)g.setTooltipForNextFrame(Component.literal(status),mx,my);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
