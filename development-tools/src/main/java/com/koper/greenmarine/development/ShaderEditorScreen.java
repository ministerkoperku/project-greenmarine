package com.koper.greenmarine.development;

import com.koper.greenmarine.client.ShaderRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Project workspace: explorer, shared documents and resizable editor groups. */
public final class ShaderEditorScreen extends Screen {
    private static final Map<String,ShaderProject> PROJECTS=new LinkedHashMap<>();
    private final Screen parent;
    private final ShaderWorkspace workspace;
    private ShaderProject project;
    private String path,text="",baseline="",diagnostic="Ready",compiledText;
    private ShaderCompiler.Result result;
    private ShaderCompiler.Language language=ShaderCompiler.Language.GLSL;
    private ShaderCompiler.Stage stage=ShaderCompiler.Stage.FRAGMENT;
    private CodeEditBox editor;
    private Button compile,apply,export,issue;
    private final List<CodeEditBox> codePanes=new ArrayList<>();
    private final List<String> codePaths=new ArrayList<>();
    private final List<Integer> codeGroups=new ArrayList<>();
    private final List<TextureCanvasWidget> imagePanes=new ArrayList<>();
    private record ImagePane(TextureCanvasWidget canvas,EditBox color,int group){}
    private final List<ImagePane> imageBindings=new ArrayList<>();
    private List<ShaderCompletions.Choice> contextChoices=List.of();
    private List<CompilerDiagnostics.Issue> issues=List.of();
    private List<ProjectValidator.Problem> projectProblems=List.of();
    private int issueIndex,projectProblemIndex;
    private boolean busy;
    private long generation;
    private boolean filesOpen=true,split,splitDown;
    private int sidebar=150,activeGroup,treeScroll,tabPage;
    private double splitRatio=.5;
    private String[] groups=new String[2];
    private final Set<String> expanded=new HashSet<>();
    private record TreeRow(ShaderProject.Entry entry,int depth){}
    private final List<TreeRow> treeRows=new ArrayList<>();
    private EditorLayout layout;
    private String selected,inlineKind,inlineSource,inlineText="",menu;
    private EditBox inline;
    private int inlineY,dragging;
    private boolean syncing;
    private final List<Button> menuButtons=new ArrayList<>();
    private final Map<String,Button> tabButtons=new LinkedHashMap<>();
    private String deleteCandidate;

    public ShaderEditorScreen(Screen parent,String source) {
        super(Component.literal("GreenMarine workspace"));this.parent=parent;path=source;workspace=DevelopmentClient.workspace();
        try {
            String key=workspace.root()+"::"+source.substring(0,source.indexOf('/'));
            project=PROJECTS.get(key);
            if(project==null){project=new ShaderProject(workspace,source);if(PROJECTS.size()>=8){String clean=PROJECTS.entrySet().stream().filter(e->!e.getValue().hasDrafts()).map(Map.Entry::getKey).findFirst().orElse(null);if(clean!=null)PROJECTS.remove(clean);}PROJECTS.put(key,project);}
            groups[0]=path;selected=path;expandParents(path);loadActive();
        }catch(Exception e){diagnostic="Cannot load project: "+e.getMessage();}
    }
    private void expandParents(String file){for(int slash=file.lastIndexOf('/');slash>0;slash=file.lastIndexOf('/',slash-1))expanded.add(file.substring(0,slash));}
    private void loadActive()throws Exception {
        path=groups[activeGroup];editor=null;
        if(!isImage(path)&&!path.endsWith("/.empty")){var doc=project.open(path);text=doc.text();baseline=doc.baseline();}
        else text=baseline="";
        language=path.endsWith(".slang")?ShaderCompiler.Language.SLANG:ShaderCompiler.Language.GLSL;
        stage=path.endsWith(".vsh")?ShaderCompiler.Stage.VERTEX:path.endsWith(".csh")||path.endsWith(".comp")?ShaderCompiler.Stage.COMPUTE:ShaderCompiler.Stage.FRAGMENT;
        contextChoices=ShaderCompletions.projectChoices(project);
    }
    private void remember(){
        try{for(int i=0;i<codePanes.size();i++){var pane=codePanes.get(i);if(!codePaths.get(i).equals(path)||pane==editor)project.edit(codePaths.get(i),pane.getValue(),pane.cursorPosition());}if(editor!=null)text=editor.getValue();for(var pane:imagePanes)pane.finish();}
        catch(Exception e){diagnostic=e.getMessage();}
    }
    private void openFile(String next){
        try{remember();if(next.isEmpty())next=project.folder()+"/.empty";groups[activeGroup]=next;selected=next;expandParents(next);loadActive();issues=List.of();result=null;compiledText=null;generation++;busy=false;menu=null;rebuildWidgets();}
        catch(Exception e){diagnostic="Cannot open: "+e.getMessage();}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){remember();minecraft.gui.setScreen(parent);}
    @Override public void removed(){generation++;busy=false;remember();}
    @Override protected void init(){
        if(!codePanes.isEmpty())remember();
        codePanes.clear();codePaths.clear();codeGroups.clear();imagePanes.clear();imageBindings.clear();menuButtons.clear();editor=null;
        layout=EditorLayout.calculate(width,height,filesOpen,sidebar,split,splitDown,splitRatio);
        int x=4;
        for(String label:(width<420?List.of("File","View","Create","Tools"):List.of("File","View","Create","Tools","Help"))){
            String name=label;int w=font.width(label)+18;
            addRenderableWidget(StudioUi.button(x,2,w,20,label,label+" menu",()->{remember();menu=Objects.equals(menu,name)?null:name;rebuildWidgets();}));x+=w;
        }
        int right=width-116;
        compile=addRenderableWidget(StudioUi.button(right,2,54,20,"Check","Validate the whole project · Ctrl+Enter",()->validateProject(false)));
        apply=addRenderableWidget(StudioUi.button(right+58,2,54,20,"Save","Save all changed files and reload · Ctrl+S",this::apply));
        export=StudioUi.button(0,0,1,1,"","",this::export);
        issue=addRenderableWidget(StudioUi.button(0,height-18,width,18,"","Click to jump to the next problem",this::nextIssue));
        buildTabs();
        if(project!=null){
            buildPane(0,layout.first());if(layout.second()!=null)buildPane(1,layout.second());
            if(filesOpen)buildTree();
        }
        if(inlineKind!=null)buildInline();
        enable();if(editor!=null)setInitialFocus(editor);if(inline!=null&&inlineKind!=null)setInitialFocus(inline);
        if(menu!=null)buildMenu();
    }
    private void buildPane(int group,EditorLayout.Rect rect){
        String file=groups[group];if(file==null||file.endsWith("/.empty"))return;
        try{
            if(isImage(file)){
                var canvas=addRenderableWidget(new TextureCanvasWidget(rect,project.openImage(file)));imagePanes.add(canvas);
                var hex=addRenderableWidget(new EditBox(font,rect.x()+6,rect.y()+22,Math.max(20,Math.min(76,rect.width()-12)),18,Component.literal("Brush color #RRGGBB")));
                imageBindings.add(new ImagePane(canvas,hex,group));
                hex.setMaxLength(9);hex.setValue("#ffffff");hex.setResponder(value->{try{if(value.matches("#?[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))canvas.color(value);}catch(Exception ignored){}});
                hex.setTooltip(Tooltip.create(Component.literal("Brush color #RRGGBB or #AARRGGBB. Right-click canvas to pick a color.")));
            }else{
                var doc=project.open(file);
                var pane=addRenderableWidget(new CodeEditBox(font,rect.x()+1,rect.y()+19,Math.max(1,rect.width()-2),Math.max(16,rect.height()-19),Component.literal(file)));
                pane.setCharacterLimit(262144);pane.setValue(doc.text());pane.moveCursor(doc.cursor());
                pane.setCompletionSource((value,position)->ShaderCompletions.suggest(value,position,file,contextChoices));
                pane.setValueListener(value->{
                    if(syncing)return;
                    try{
                        project.edit(file,value,pane.cursorPosition());if(group==activeGroup){text=value;invalidate();}
                        syncing=true;
                        for(int i=0;i<codePanes.size();i++)if(codePaths.get(i).equals(file)&&codePanes.get(i)!=pane){var other=codePanes.get(i);int cursor=other.cursorPosition();other.setValue(value);other.moveCursor(cursor);}
                    }catch(Exception e){diagnostic=e.getMessage();}finally{syncing=false;}
                });
                pane.setIssues(file.equals(path)?CompilerDiagnostics.byLine(issues):Map.of());codePanes.add(pane);codePaths.add(file);codeGroups.add(group);
                if(group==activeGroup)editor=pane;
            }
        }catch(Exception e){diagnostic="Cannot open "+file+": "+e.getMessage();}
    }
    private void buildTabs(){
        tabButtons.clear();
        if(project==null)return;var docs=project.openPaths();int start=layout.explorer()==0?0:layout.explorer()+3;
        int capacity=Math.max(1,(width-start-36)/110);tabPage=Math.clamp(tabPage,0,Math.max(0,(docs.size()-1)/capacity));
        for(int i=tabPage*capacity;i<Math.min(docs.size(),(tabPage+1)*capacity);i++){
            String file=docs.get(i);int tx=start+(i-tabPage*capacity)*110;
            tabButtons.put(file,addRenderableWidget(StudioUi.button(tx,24,108,19,(file.equals(path)?"• ":"")+name(file)+(project.dirty(file)?" *":""),file,()->openFile(file))));
        }
        if(docs.size()>capacity){addRenderableWidget(StudioUi.button(width-34,24,16,19,"<","Previous tabs",()->{remember();tabPage--;rebuildWidgets();}));addRenderableWidget(StudioUi.button(width-17,24,16,19,">","Next tabs",()->{remember();tabPage++;rebuildWidgets();}));}
    }
    private static boolean isImage(String file){return file.toLowerCase(Locale.ROOT).endsWith(".png");}
    private static String name(String file){return file.substring(file.lastIndexOf('/')+1);}
    private void buildTree(){
        treeRows.clear();try{appendTree("",0);}catch(Exception e){diagnostic=e.getMessage();}
        int visible=Math.max(1,(height-18-treeTop())/18);treeScroll=Math.clamp(treeScroll,0,Math.max(0,treeRows.size()-visible));
    }
    private void appendTree(String directory,int depth)throws Exception {
        if(depth>=64)return;
        for(var entry:project.children(directory)){
            if(treeRows.size()>=8192)return;treeRows.add(new TreeRow(entry,depth));
            if(entry.directory()&&expanded.contains(entry.path()))appendTree(entry.path().substring(project.folder().length()+1),depth+1);
        }
    }
    private int treeTop(){return inlineKind!=null&&!inlineKind.equals("rename")?70:48;}
    private String selectedDirectory(){
        if(selected==null)return "";
        try{if(Files.isDirectory(workspace.assetPath(selected)))return selected.substring(project.folder().length()+1);}catch(Exception ignored){}
        int slash=selected.lastIndexOf('/');return slash<=project.folder().length()?"":selected.substring(project.folder().length()+1,slash);
    }
    private void startInline(String kind){
        remember();menu=null;filesOpen=true;inlineKind=kind;inlineSource=selected;
        inlineText=kind.equals("rename")&&selected!=null?name(selected):"";
        rebuildWidgets();
    }
    private void buildInline(){
        inlineY=48;
        if(inlineKind.equals("rename"))for(int i=treeScroll;i<treeRows.size();i++)if(treeRows.get(i).entry().path().equals(inlineSource)){inlineY=treeTop()+(i-treeScroll)*18;break;}
        inlineY=Math.min(inlineY,height-36);
        inline=addRenderableWidget(new EditBox(font,6,inlineY,Math.max(36,layout.explorer()-12),18,Component.literal(inlineKind.equals("rename")?"Rename":"Name, then Enter")));
        inline.setMaxLength(512);inline.setValue(inlineText);inline.setResponder(v->inlineText=v);
        inline.setTooltip(Tooltip.create(Component.literal("Enter to confirm · Escape to cancel. File extensions choose the template; .png creates a texture.")));
    }
    private void commitInline(){
        try{
            String value=inlineText.strip();if(value.isEmpty())throw new IllegalArgumentException("Enter a name");
            String directory=selectedDirectory(),relative=directory.isEmpty()?value:directory+"/"+value;
            String next;
            if(inlineKind.equals("rename")){
                int slash=inlineSource.lastIndexOf('/');String parentPath=inlineSource.substring(project.folder().length()+1,slash<project.folder().length()+1?project.folder().length()+1:slash);
                next=project.rename(inlineSource,parentPath.isEmpty()?value:parentPath+"/"+value);
                codePanes.clear();codePaths.clear();codeGroups.clear();
                for(int i=0;i<groups.length;i++)if(groups[i]!=null)groups[i]=ShaderProject.relocated(groups[i],inlineSource,next);
                var moved=new HashSet<String>();for(String dir:expanded)moved.add(ShaderProject.relocated(dir,inlineSource,next));expanded.clear();expanded.addAll(moved);selected=next;loadActive();
            }else if(inlineKind.equals("folder")){
                next=project.createFolder(relative);selected=next;expandParents(next+"/.index");
            }else{
                next=value.toLowerCase(Locale.ROOT).endsWith(".png")?project.createImage(relative,32,32):project.create(relative,template(value));
                groups[activeGroup]=next;selected=next;expandParents(next);loadActive();
            }
            inlineKind=null;inline=null;diagnostic="Ready";rebuildWidgets();
        }catch(Exception e){diagnostic=e.getMessage();}
    }
    private String template(String file){
        String kind=file.endsWith(".fsh")?"scene":file.endsWith(".vsh")?"vertex":file.endsWith(".csh")||file.endsWith(".comp")?"compute":file.endsWith(".glsl")?"include":file.endsWith(".lua")?"lua":null;
        if(kind==null)return file.endsWith(".json")?"{\n}\n":"";
        return ShaderSdk.entries().stream().filter(e->e.id().equals("snippet/"+kind)).findFirst().map(ShaderSdk.Entry::text).orElse("");
    }
    private void split(boolean down){remember();split=true;splitDown=down;groups[1]=path;activeGroup=1;try{loadActive();}catch(Exception e){diagnostic=e.getMessage();}menu=null;rebuildWidgets();}
    private void action(String label,String help,Runnable action,int x,int y,int w,int h){var button=addRenderableWidget(StudioUi.button(x,y,w,h,label,help,()->{remember();menu=null;rebuildWidgets();action.run();}));menuButtons.add(button);}
    private void buildMenu(){
        int mx=switch(menu){case "View"->44;case "Create"->90;case "Tools"->144;case "Help"->190;default->4;};
        int w=Math.min(220,width-mx-4),y=24;
        var rows=new LinkedHashMap<String,Runnable>();
        switch(menu){
            case "File"->{rows.put("New file                 Ctrl+N",()->startInline("file"));rows.put("New folder",()->startInline("folder"));rows.put("Rename selected                 F2",()->startInline("rename"));rows.put(selected!=null&&selected.equals(deleteCandidate)?"Confirm delete selected":"Delete selected...",this::deleteSelected);rows.put("Import asset...",this::importAsset);rows.put("Save all                  Ctrl+S",this::apply);rows.put("Export project ZIP...",this::exportPack);rows.put("Close workspace",this::onClose);}
            case "View"->{rows.put((filesOpen?"Hide":"Show")+" explorer",()->{remember();filesOpen=!filesOpen;rebuildWidgets();});rows.put("Split editor right",()->split(false));rows.put("Split editor down",()->split(true));rows.put("Single editor",()->{remember();split=false;groups[0]=path;activeGroup=0;rebuildWidgets();});rows.put("Interface scale...",()->minecraft.gui.setScreen(new UiScaleScreen(this)));}
            case "Create"->{rows.put("File",()->startInline("file"));rows.put("Folder",()->startInline("folder"));rows.put("Texture PNG",()->{startInline("file");inlineText="texture.png";inline.setValue(inlineText);});rows.put("Material / block lighting",()->minecraft.gui.setScreen(new MaterialEditorScreen(this)));rows.put("From SDK template...",()->minecraft.gui.setScreen(new NewProjectFileScreen(this,project,this::openFile)));}
            case "Tools"->{rows.put("Check project",()->validateProject(false));rows.put("Compile current file",this::compile);rows.put("Export compiled SPIR-V...",this::export);rows.put("Save source copy...",this::saveCopy);rows.put("Stage: "+stage+"  (change)",()->{stage=ShaderCompiler.Stage.values()[(stage.ordinal()+1)%ShaderCompiler.Stage.values().length];invalidate();diagnostic="Compile stage: "+stage;rebuildWidgets();});rows.put("Language: "+language+"  (change)",()->{language=language==ShaderCompiler.Language.GLSL?ShaderCompiler.Language.SLANG:ShaderCompiler.Language.GLSL;invalidate();rebuildWidgets();});rows.put("SDK reference & snippets",()->minecraft.gui.setScreen(new ShaderSdkScreen(this,source->{if(editor!=null){editor.insertSnippet(source);remember();}})));rows.put("Development settings...",()->minecraft.gui.setScreen(new ToolsSettingsScreen(this)));}
            case "Help"->{rows.put("SDK reference & snippets",()->minecraft.gui.setScreen(new ShaderSdkScreen(this,source->{if(editor!=null){editor.insertSnippet(source);remember();}})));rows.put("Shortcuts",()->{diagnostic="Ctrl+N new file · Ctrl+S save all · Ctrl+Enter check · Ctrl+\\ split · F2 rename";rebuildWidgets();});}
        }
        int rowHeight=Math.min(21,Math.max(14,(height-44)/Math.max(1,rows.size())));
        for(var row:rows.entrySet()){action(row.getKey(),row.getKey(),row.getValue(),mx,y,w,rowHeight);y+=rowHeight;}
    }
    private void deleteSelected(){
        if(selected==null)return;remember();
        if(!selected.equals(deleteCandidate)){deleteCandidate=selected;diagnostic="Choose File > Confirm delete selected to remove "+name(selected);rebuildWidgets();return;}
        try{
            String removed=selected;project.delete(removed);deleteCandidate=null;
            String fallback=project.files().stream().filter(p->p.matches(".*\\.(fsh|vsh|csh|glsl|json|lua|md|txt|png)$")).findFirst().orElse(project.folder()+"/.empty");
            codePanes.clear();codePaths.clear();codeGroups.clear();
            for(int i=0;i<groups.length;i++)if(Objects.equals(groups[i],removed))groups[i]=fallback;
            selected=fallback;loadActive();diagnostic="Deleted "+name(removed);rebuildWidgets();
        }catch(Exception e){diagnostic="Delete failed: "+e.getMessage();}
    }
    private void importAsset(){
        remember();minecraft.gui.setScreen(PathBrowserScreen.asset(this,DevelopmentClient.gameRoot(),source->{
            String prefix=selectedDirectory();String destination=(prefix.isEmpty()?"":prefix+"/")+source.getFileName();
            minecraft.execute(()->minecraft.gui.setScreen(new TextPromptScreen(this,"Import asset",destination,"Project-relative destination",value->{try{String next=project.importAsset(source,value);if(isImage(next))openFile(next);else{selected=next;expandParents(next);rebuildWidgets();}}catch(Exception e){throw new IllegalArgumentException(e.getMessage());}})));
        }));
    }
    private void focusGroup(int group){
        if(group==activeGroup)return;remember();activeGroup=group;
        generation++;busy=false;result=null;compiledText=null;issues=List.of();diagnostic="Ready";
        try{loadActive();for(int i=0;i<codeGroups.size();i++)if(codeGroups.get(i)==group){editor=codePanes.get(i);break;}}
        catch(Exception e){diagnostic=e.getMessage();}
    }
    @Override public boolean keyPressed(KeyEvent event){
        if(inlineKind!=null){if(event.key()==InputConstants.KEY_RETURN||event.key()==InputConstants.KEY_NUMPADENTER){commitInline();return true;}if(event.key()==InputConstants.KEY_ESCAPE){inlineKind=null;inline=null;rebuildWidgets();return true;}return super.keyPressed(event);}
        if(event.hasControlDown()){
            if(event.key()==InputConstants.KEY_S){remember();apply();return true;}
            if(event.key()==InputConstants.KEY_N){startInline("file");return true;}
            if(event.key()==InputConstants.KEY_RETURN){remember();validateProject(false);return true;}
            if(event.key()==InputConstants.KEY_BACKSLASH){split(false);return true;}
        }
        if(event.key()==InputConstants.KEY_F2&&selected!=null){startInline("rename");return true;}
        if(event.key()==InputConstants.KEY_ESCAPE&&menu!=null){menu=null;rebuildWidgets();return true;}
        if(editor!=null&&editor.hasCompletion()&&event.key()==InputConstants.KEY_ESCAPE)return editor.keyPressed(event);
        return super.keyPressed(event);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(menu!=null){for(var button:menuButtons)if(button.isMouseOver(event.x(),event.y()))return button.mouseClicked(event,doubleClick);menu=null;rebuildWidgets();return true;}
        if(inlineKind!=null&&event.y()>=inlineY&&event.y()<inlineY+18&&event.x()<layout.explorer())return super.mouseClicked(event,doubleClick);
        if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&filesOpen&&Math.abs(event.x()-layout.explorer())<4&&event.y()>44&&event.y()<height-18){remember();dragging=1;return true;}
        if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&layout.second()!=null){var first=layout.first();boolean divider=layout.down()?Math.abs(event.y()-first.bottom())<4&&event.x()>=first.x():Math.abs(event.x()-first.right())<4&&event.y()>=44;if(divider){remember();dragging=2;return true;}}
        if(filesOpen&&event.x()<layout.explorer()&&event.y()>=24&&event.y()<height-18){
            if(event.y()<44){if(event.x()>layout.explorer()-26)startInline("folder");else if(event.x()>layout.explorer()-50)startInline("file");return true;}
            int index=treeScroll+(int)((event.y()-treeTop())/18);
            if(event.y()>=treeTop()&&index>=0&&index<treeRows.size()){
                var entry=treeRows.get(index).entry();selected=entry.path();
                if(doubleClick){startInline("rename");return true;}
                if(entry.directory()){remember();if(!expanded.add(entry.path()))expanded.remove(entry.path());rebuildWidgets();}
                else if(event.button()==InputConstants.MOUSE_BUTTON_RIGHT)startInline("rename");else openFile(entry.path());return true;
            }
            return true;
        }
        if(layout.second()!=null&&layout.second().contains(event.x(),event.y()))focusGroup(1);else if(layout.first().contains(event.x(),event.y()))focusGroup(0);
        for(var pane:codePanes)if(pane.clickCompletion(event)){editor=pane;setFocused(pane);return true;}
        for(var pane:codePanes)pane.dismissCompletions();
        for(var child:children())if(child instanceof EditBox field&&field.visible&&field.isMouseOver(event.x(),event.y())){setFocused(field);return field.mouseClicked(event,doubleClick);}
        return super.mouseClicked(event,doubleClick);
    }
    @Override public void mouseMoved(double x,double y){for(var pane:imagePanes)pane.mouseMoved(x,y);super.mouseMoved(x,y);}
    private void resizeWorkspace(){
        layout=EditorLayout.calculate(width,height,filesOpen,sidebar,split,splitDown,splitRatio);
        if(filesOpen)sidebar=layout.explorer();
        for(int i=0;i<codePanes.size();i++){
            var rect=codeGroups.get(i)==0?layout.first():layout.second();
            codePanes.get(i).resizeEditor(rect.x()+1,rect.y()+19,Math.max(1,rect.width()-2),Math.max(16,rect.height()-19));
        }
        for(var pane:imageBindings){
            var rect=pane.group()==0?layout.first():layout.second();
            pane.canvas().setRectangle(rect.width(),rect.height()-20,rect.x(),rect.y()+20);
            pane.color().setRectangle(Math.max(20,Math.min(76,rect.width()-12)),18,rect.x()+6,rect.y()+22);
        }
        int start=layout.explorer()==0?0:layout.explorer()+3;
        int tabWidth=Math.min(110,Math.max(16,(width-start-36)/Math.max(1,tabButtons.size()))),index=0;
        for(var tab:tabButtons.values()){tab.setX(start+index++*tabWidth);tab.setWidth(tabWidth-2);}
        if(inline!=null&&inlineKind!=null)inline.setWidth(Math.max(36,layout.explorer()-12));
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&dragging==1){sidebar=(int)event.x();resizeWorkspace();return true;}
        if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&dragging==2){splitRatio=layout.down()?(event.y()-44)/(height-62.0):(event.x()-layout.first().x())/(width-layout.first().x());resizeWorkspace();return true;}
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&dragging!=0){dragging=0;return true;}return super.mouseReleased(event);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(filesOpen&&x<layout.explorer()&&y>=44){remember();treeScroll=Math.max(0,treeScroll-(int)(vertical*3));rebuildWidgets();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void exportPack(){remember();minecraft.gui.setScreen(new PackExportScreen(this,project));}
    private void invalidate() {compiledText=null;result=null;issues=List.of();projectProblems=List.of();if(editor!=null)editor.setIssues(Map.of());if(apply!=null)enable();}
    private void showIssues(String diagnostics) {
        issues = CompilerDiagnostics.parse(diagnostics, path); issueIndex = 0;
        if (editor != null) editor.setIssues(CompilerDiagnostics.byLine(issues));
    }
    private void nextIssue() {
        if(!projectProblems.isEmpty()){
            var problem=projectProblems.get(projectProblemIndex);if(problem.path().startsWith(project.folder()+"/")&&!problem.path().equals(path))openFile(problem.path());
            if(editor!=null)editor.goToLine(problem.line());projectProblemIndex=(projectProblemIndex+1)%projectProblems.size();enable();return;
        }
        if (issues.isEmpty()||editor==null) return;
        editor.goToLine(issues.get(issueIndex).line()); setFocused(editor);
        issueIndex = (issueIndex + 1) % issues.size(); enable();
    }
    private void enable() {
        compile.active = !busy;
        boolean valid = !busy && result != null && result.success() && text.equals(compiledText);
        apply.active = !busy;
        export.active = valid && result.spirv().length > 0;
        compile.setTooltip(Tooltip.create(Component.literal(diagnostic)));
        apply.setTooltip(Tooltip.create(Component.literal("Validate all project sources, save every dirty document, and reload resources. Runtime renderer status remains authoritative.")));
        export.setTooltip(Tooltip.create(Component.literal("Export the last successfully compiled SPIR-V to a file you choose. Does not bind the binary to a pack.")));
        issue.visible = !issues.isEmpty()||!projectProblems.isEmpty();
        if(!projectProblems.isEmpty()){
            var problem=projectProblems.get(projectProblemIndex);String label="["+(projectProblemIndex+1)+"/"+projectProblems.size()+"] "+problem.path()+":"+problem.line()+" "+problem.message().replace('\n',' ');
            issue.setMessage(Component.literal(font.plainSubstrByWidth(label,issue.getWidth()-8)));issue.setTooltip(Tooltip.create(Component.literal(label)));
        }

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
        if(editor==null){diagnostic="Select a shader source to compile";return;}
        String candidate = text;String selectedPath=path; var selectedLanguage = language; var selectedStage = stage;
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
            candidate, selectedPath, executable, name->ShaderIncludes.read(workspace,selectedPath,name))).whenComplete((compiled, error) -> minecraft.execute(() -> {
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
    private void apply(){validateProject(true);}
    private void validateProject(boolean save){
        if(busy||project==null)return;remember();var drafts=project.drafts();long request=++generation;busy=true;diagnostic="Validating whole project...";enable();
        java.util.concurrent.CompletableFuture.supplyAsync(()->ProjectValidator.validate(project,drafts,id->mountedText(id),id->mountedBinary(id))).whenComplete((validation,error)->minecraft.execute(()->{
            if(request!=generation||minecraft.gui.screen()!=this)return;busy=false;
            if(error!=null){diagnostic="Validation failed: "+error.getMessage();enable();return;}
            diagnostic=validation.summary();
            if(!validation.valid()){
                projectProblems=validation.problems();projectProblemIndex=0;
                var problem=projectProblems.getFirst();
                if(problem.path().startsWith(project.folder()+"/")&&!problem.path().equals(path)){openFile(problem.path());}
                if(editor!=null)editor.goToLine(problem.line());diagnostic=validation.summary();enable();return;
            }
            if(!drafts.equals(project.drafts())){diagnostic="Project changed during validation; check again.";enable();return;}
            if(save)try{
                project.saveAll();baseline=text;DevelopmentClient.resetWatcher();diagnostic="Saved all sources; reloading resources.";
                minecraft.reloadResourcePacks().whenComplete((v,e)->minecraft.execute(()->{diagnostic=e==null?"Reloaded. "+ShaderRuntime.status().detail():"Reload failed: "+e.getMessage();if(minecraft.gui.screen()==this)enable();}));
            }catch(Exception e){diagnostic="Save failed: "+e.getMessage();}
            enable();
        }));
    }
    private String mountedText(String id){byte[] bytes=mountedBinary(id);return bytes==null?null:new String(bytes,StandardCharsets.UTF_8);}
    private byte[] mountedBinary(String id){try{var resource=minecraft.getResourceManager().getResource(Identifier.parse(id));if(resource.isEmpty())return null;try(var input=resource.get().open()){byte[] bytes=input.readNBytes(4*1024*1024+1);return bytes.length>4*1024*1024?null:bytes;}}catch(Exception e){return null;}}
    private void saveCopy(){
        if(editor==null){diagnostic="Select a source document";return;}
        String name=path.substring(path.lastIndexOf('/')+1);
        minecraft.gui.setScreen(new PathBrowserScreen(this,DevelopmentClient.gameRoot().resolve(name),false,target->{
            try{java.nio.file.Files.writeString(target,text,StandardCharsets.UTF_8);diagnostic="Source copy saved to "+target;}
            catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}
        }));
    }
    private void export() {
        if (!export.active) return;
        byte[] bytes=result.spirv();
        Path initial=DevelopmentClient.gameRoot().resolve("greenmarine-development/compiled/shader.spv");
        minecraft.gui.setScreen(new PathBrowserScreen(this,initial,false,target->{
            try{Files.write(target,bytes);diagnostic="Exported SPIR-V to "+target;}
            catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick) {
        g.fill(0,0,width,height,StudioUi.BG);g.fill(0,0,width,24,0xff303033);g.fill(0,24,width,44,StudioUi.PANEL);
        if(filesOpen){
            g.fill(0,24,layout.explorer(),height-18,StudioUi.PANEL);
            g.text(font,"EXPLORER",8,30,StudioUi.MUTED,false);
            g.text(font,"+F",layout.explorer()-47,30,StudioUi.TEXT,false);g.text(font,"+D",layout.explorer()-23,30,StudioUi.TEXT,false);
            g.enableScissor(0,44,layout.explorer(),height-18);
            for(int i=treeScroll;i<treeRows.size();i++){
                int y=treeTop()+(i-treeScroll)*18;if(y+18>height-18)break;var row=treeRows.get(i);var entry=row.entry();
                if(entry.path().equals(selected))g.fill(0,y,layout.explorer(),y+18,0xff37373d);
                if(inlineKind!=null&&inlineKind.equals("rename")&&entry.path().equals(inlineSource))continue;
                String icon=entry.directory()?(expanded.contains(entry.path())?"v ":"> "):isImage(entry.path())?"▧ ":"  ";
                g.text(font,font.plainSubstrByWidth(icon+entry.name()+(project.dirty(entry.path())?" *":""),Math.max(1,layout.explorer()-16-row.depth()*10)),8+row.depth()*10,y+5,entry.directory()?StudioUi.TEXT:StudioUi.MUTED,false);
            }
            g.disableScissor();g.fill(layout.explorer(),44,layout.explorer()+3,height-18,StudioUi.BORDER);
        }
        for(int i=0;i<(layout.second()==null?1:2);i++){
            var rect=i==0?layout.first():layout.second();g.fill(rect.x(),rect.y(),rect.right(),rect.y()+19,StudioUi.BG);
            g.text(font,font.plainSubstrByWidth(groups[i]==null?"":groups[i].substring(groups[i].indexOf('/')+1),rect.width()-12),rect.x()+6,rect.y()+5,i==activeGroup?StudioUi.TEXT:StudioUi.MUTED,false);
            if(i==activeGroup)g.fill(rect.x(),rect.y(),rect.right(),rect.y()+1,StudioUi.ACCENT);
        }
        if(layout.second()!=null){var first=layout.first();if(layout.down())g.fill(first.x(),first.bottom(),width,first.bottom()+3,StudioUi.BORDER);else g.fill(first.right(),44,first.right()+3,height-18,StudioUi.BORDER);}
        g.fill(0,height-18,width,height,0xff1d4d72);
        if(issues.isEmpty()&&projectProblems.isEmpty())g.text(font,font.plainSubstrByWidth((busy?"Working…  ":"")+diagnostic.replace('\n',' '),width-12),6,height-12,0xffe0e8f0,false);
        for(var entry:tabButtons.entrySet())entry.getValue().setMessage(Component.literal((entry.getKey().equals(path)?"• ":"")+name(entry.getKey())+(project.dirty(entry.getKey())?" *":"")));
        super.extractRenderState(g,mx,my,tick);
        if(filesOpen&&my>=24&&my<44&&mx>layout.explorer()-50&&mx<layout.explorer())g.setTooltipForNextFrame(Component.literal(mx>layout.explorer()-26?"New folder · type a name and press Enter":"New file · type a name and press Enter"),mx,my);
        for(var pane:codePanes)pane.extractCompletionPopup(g,mx,my);
    }
}
