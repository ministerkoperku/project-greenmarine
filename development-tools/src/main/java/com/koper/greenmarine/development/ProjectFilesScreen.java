package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

final class ProjectFilesScreen extends Screen {
    private final Screen parent;private final ShaderProject project;private final Consumer<String> open;
    private String selected,search="",status="";private int page;private boolean confirm;
    ProjectFilesScreen(Screen parent,ShaderProject project,String selected,Consumer<String> open){super(Component.literal("Project files"));this.parent=parent;this.project=project;this.selected=selected;this.open=open;}
    @Override protected void init(){
        int searchY=height<220?28:38,listY=searchY+28;
        var filter=addRenderableWidget(new EditBox(font,14,searchY,width-28,22,Component.literal("Find project file")));filter.setValue(search);filter.setResponder(v->{if(!v.equals(search)){search=v;page=0;rebuildWidgets();children().stream().filter(c->c instanceof EditBox).findFirst().ifPresent(this::setFocused);}});
        List<String> files;try{files=project.files().stream().filter(p->p.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList();}catch(Exception e){files=List.of();status=e.getMessage();}
        int count=Math.max(1,(height-104-listY)/22),pages=Math.max(1,(files.size()+count-1)/count);page=Math.clamp(page,0,pages-1);
        for(int i=page*count;i<Math.min(files.size(),(page+1)*count);i++){String file=files.get(i);addRenderableWidget(StudioUi.button(14,listY+(i-page*count)*22,width-28,20,(file.equals(selected)?"• ":"")+file.substring(project.folder().length()+1),file,()->{selected=file;confirm=false;rebuildWidgets();}));}
        addRenderableWidget(StudioUi.button(14,height-104,48,20,"<","Previous files",()->{page--;rebuildWidgets();})).active=page>0;
        addRenderableWidget(StudioUi.button(66,height-104,88,20,(page+1)+" / "+pages+" >","Next files",()->{page++;rebuildWidgets();})).active=page+1<pages;
        boolean hasSelection=selected!=null&&!selected.isBlank()&&files.contains(selected);
        int cw=(width-36)/3;
        addRenderableWidget(StudioUi.button(14,height-78,cw,22,"New file","Create any supported source/manifest with an SDK template.",()->minecraft.gui.setScreen(new NewProjectFileScreen(parent,project,open))));
        addRenderableWidget(StudioUi.button(18+cw,height-78,cw,22,"Import asset","Copy a texture, binary or source into this project.",()->minecraft.gui.setScreen(PathBrowserScreen.asset(this,DevelopmentClient.gameRoot(),source->{
            minecraft.execute(()->minecraft.gui.setScreen(new TextPromptScreen(this,"Import asset","assets/demo/textures/"+source.getFileName(),"Destination relative to this project",value->{try{selected=project.importAsset(source,value);status="Imported "+selected;}catch(Exception e){throw new IllegalArgumentException(e.getMessage());}})));
        }))));
        addRenderableWidget(StudioUi.button(22+2*cw,height-78,cw,22,"Rename","Rename or move this file within the project.",()->minecraft.gui.setScreen(new TextPromptScreen(this,"Rename file",selected.substring(project.folder().length()+1),"Project-relative destination",value->{try{selected=project.rename(selected,value);open.accept(selected);}catch(Exception e){throw new IllegalArgumentException(e.getMessage());}})))).active=hasSelection;
        addRenderableWidget(StudioUi.button(14,height-52,cw,22,"Open","Open this document. Binary assets remain in the project and export.",()->{try{project.open(selected);open.accept(selected);minecraft.gui.setScreen(parent);}catch(Exception e){status="Binary asset or unreadable source: "+selected;}})).active=hasSelection;
        addRenderableWidget(StudioUi.button(18+cw,height-52,cw,22,confirm?"Confirm delete":"Delete","Press twice to delete this selected project file.",()->{
            if(!confirm){confirm=true;status="Press Confirm delete to remove "+selected;rebuildWidgets();return;}
            try{project.delete(selected);selected=project.files().stream().filter(p->p.matches(".*\\.(fsh|vsh|csh|comp|glsl|json|lua|slang|md|txt|properties)$")).findFirst().orElse("");open.accept(selected);confirm=false;rebuildWidgets();}catch(Exception e){status=e.getMessage();}
        })).active=hasSelection;
        addRenderableWidget(StudioUi.button(22+2*cw,height-52,cw,22,"Back","Return to code with drafts preserved.",this::onClose));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){g.fill(0,0,width,height,StudioUi.BG);g.text(font,"Files / "+project.folder(),14,12,StudioUi.TEXT,false);super.extractRenderState(g,x,y,tick);g.text(font,font.plainSubstrByWidth(status,width-28),14,height-18,StudioUi.MUTED,false);if(!status.isEmpty()&&y>height-30)g.setTooltipForNextFrame(Component.literal(status),x,y);}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
