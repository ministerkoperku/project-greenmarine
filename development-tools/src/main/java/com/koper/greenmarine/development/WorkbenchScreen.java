package com.koper.greenmarine.development;

import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.client.world.WorldPrograms;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Touch-accessible launcher; opening the addon never changes the selected shaderpack. */
public final class WorkbenchScreen extends Screen {
    private record Row(String label, String help, Runnable action) {
        Row(String label,Runnable action){this(label,"",action);}
    }
    private record Layout(int x, int width, int bodyY, int rowPitch, int rows, int navigationY, int doneY, int buttonHeight) {}
    private final Screen parent;
    private String view = "home", status = "Development Tools is a separate addon";
    private int page;
    private int columns=1,capacity,pitch;
    public WorkbenchScreen(Screen parent) { super(Component.literal("GreenMarine Development Tools")); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    private void open(String next) { view = next; page = 0; rebuildWidgets(); }
    private List<Row> rows() {
        var rows = new ArrayList<Row>();
        if (view.equals("home")) {
            rows.add(new Row("New shaderpack project", "GreenMarine SDK: fullscreen, compute or world starter with all files.",()->minecraft.gui.setScreen(new NewShaderProjectScreen(this))));
            rows.add(new Row("Open shaderpack project", "Open a project with file tree, document tabs and embedded SDK.", () -> open("files")));
            rows.add(new Row("Import Iris pack", "Convert supported sources and keep an import report for manual work.", () -> minecraft.gui.setScreen(new IrisImportScreen(this))));
            rows.add(new Row("Material studio", "Paint surface properties over a texture and export a resource pack.", () -> minecraft.gui.setScreen(new MaterialEditorScreen(this))));
        } else if (view.equals("files")) {
            try {
                for (String file : DevelopmentClient.workspace().files()) rows.add(new Row(file, () -> minecraft.gui.setScreen(new ShaderEditorScreen(this, file))));
                if (rows.isEmpty()) rows.add(new Row("No directory packs. Create an editable pack first.", () -> {}));
            } catch (Exception error) { rows.add(new Row("Read failed: " + error.getMessage(), () -> {})); }
        } else {
            rows.add(new Row("Renderer profiling: " + (ShaderRuntime.settings().profiling() ? "On" : "Off"), () -> {
                var s = ShaderRuntime.settings();
                ShaderRuntime.apply(new ShaderSettings(s.pack(), s.quality(), s.rays(), !s.profiling(), s.parameters()));
                rebuildWidgets();
            }));
            rows.add(new Row("Backend: " + ShaderRuntime.capabilities().backend(), () -> {}));
            rows.add(new Row("Renderer: " + ShaderRuntime.status().state(), () -> {}));
            rows.add(new Row(ShaderRuntime.status().detail(), () -> {}));
            rows.add(new Row("Ray path: " + ShaderRuntime.rays().effective() + " | " + ShaderRuntime.rays().reason(), () -> {}));
            rows.add(new Row("Material catalog generation: " + com.koper.greenmarine.api.material.MaterialAddons.generation(), () -> {}));
            timing(rows, "Pack CPU", PackRenderer.cpuTiming()); timing(rows, "Pack GPU", PackRenderer.gpuTiming());
            timing(rows, "World GPU", WorldPrograms.mainGpu()); timing(rows, "Surface rays GPU", RayWorld.surfaceGpuTiming());
            var pack = ShaderRuntime.active();
            if (pack != null) {
                rows.add(new Row("Active pack: " + pack.definition().id(), () -> {}));
                rows.add(new Row("Source snapshot: " + pack.sources().size() + " texts / " + pack.binaries().size() + " SPIR-V modules", () -> {}));
                for (var pass : pack.definition().passes()) rows.add(new Row("Declared pass " + pass.name() + " | " + pass.scale() + "x | " + pass.format() + " | inputs " + pass.inputs(), () -> {}));
            }
            rows.add(new Row("Refresh diagnostics", () -> rebuildWidgets()));
        }
        return rows;
    }
    private static void timing(List<Row> rows, String name, PerformanceSamples.Snapshot s) {
        rows.add(new Row(name + (s.count() == 0 ? ": no samples (enable profiling in settings)" : String.format(Locale.ROOT, ": %.2f ms / p95 %.2f ms", s.meanMs(), s.p95Ms())), () -> {}));
    }
    @Override protected void init() {
        addRenderableWidget(StudioUi.button(width-88,4,74,22,"UI size","Adjust the size of all Minecraft menus.",()->minecraft.gui.setScreen(new UiScaleScreen(this))));
        int w=Math.max(1,Math.min(700,width-28)),x=(width-w)/2;
        String[] labels={"Create","Projects","Diagnostics","Settings"};
        String[] targets={"home","files","diagnostics","settings"};
        int tab=w/4;
        for(int i=0;i<4;i++) {
            final int index=i;
            addRenderableWidget(StudioUi.button(x+i*tab,52,tab-4,24,(view.equals(targets[i])?"• ":"")+labels[i],"Open "+labels[i],()->{
                if(index==3)minecraft.gui.setScreen(new ToolsSettingsScreen(this));else open(targets[index]);
            }));
        }
        var rows=rows();boolean cards=view.equals("home");
        columns=cards&&width>=420?2:1;
        pitch=cards?(height<240?34:54):26;capacity=Math.max(1,(height-148)/pitch)*columns;
        int pages=Math.max(1,(rows.size()+capacity-1)/capacity);page=Math.clamp(page,0,pages-1);
        for(int i=page*capacity;i<Math.min(rows.size(),(page+1)*capacity);i++) {
            Row row=rows.get(i);int index=i-page*capacity,y=86+index/columns*pitch,cw=(w-8*(columns-1))/columns,cx=x+(index%columns)*(cw+8);
            addRenderableWidget(StudioUi.button(cx,y,cw,cards?pitch-4:24,row.label(),row.help().isBlank()?row.label():row.help(),row.action()));
        }
        if(pages>1) {
            addRenderableWidget(StudioUi.button(x,height-56,60,20,"Previous","Previous items",()->{page--;rebuildWidgets();})).active=page>0;
            addRenderableWidget(StudioUi.button(x+64,height-56,90,20,(page+1)+" / "+pages+"  Next","Next items",()->{page++;rebuildWidgets();})).active=page+1<pages;
        }
        if(cards&&pages==1){
            addRenderableWidget(StudioUi.button(x,height-56,96,20,"Reload","Reload textures, shaders and material packs.",()->{
                status="Reload requested";minecraft.reloadResourcePacks().whenComplete((v,e)->minecraft.execute(()->status=e==null?"Resources reloaded":"Reload failed: "+e.getMessage()));
            }));
            addRenderableWidget(StudioUi.button(x+100,height-56,96,20,"Renderer","Choose shader packs and tune quality.",()->minecraft.gui.setScreen(new ShaderScreen(this))));
        }
        addRenderableWidget(StudioUi.button(x+w-82,height-56,82,20,"Back","Return to the previous screen",this::onClose));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick) {
        StudioUi.background(g,width,height,"Development studio","Create / Edit / Preview");
        super.extractRenderState(g,mx,my,tick);
        int w=Math.min(700,width-28),x=(width-w)/2;
        if(view.equals("home")&&height>=240) {
            var rows=rows();int cw=(w-8*(columns-1))/columns;
            for(int i=page*capacity;i<Math.min(rows.size(),(page+1)*capacity);i++)if(!rows.get(i).help().isBlank()){
                int index=i-page*capacity,cx=x+(index%columns)*(cw+8),y=86+index/columns*pitch;
                String help=rows.get(i).help(),first=font.plainSubstrByWidth(help,cw-16);
                if(first.length()<help.length()&&first.lastIndexOf(' ')>0)first=first.substring(0,first.lastIndexOf(' '));
                g.text(font,first,cx+8,y+25,StudioUi.MUTED,false);
                if(first.length()<help.length())g.text(font,font.plainSubstrByWidth(help.substring(first.length()).stripLeading(),cw-16),cx+8,y+36,StudioUi.MUTED,false);
            }
        }
        g.text(font,font.plainSubstrByWidth(status+" | "+DevelopmentClient.reloadStatus(),width-28),14,height-21,StudioUi.MUTED,false);
    }
}
