package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.client.world.WorldPrograms;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Touch-sized, paginated controls; changes retain all per-pack overrides. */
public final class ShaderScreen extends Screen {
    private enum View { SETTINGS, PACKS, QUALITY, RAYS, PERFORMANCE, STATUS }
    private record Row(String label,String help,Runnable action,boolean enabled) {}
    private record Metric(String name,PerformanceSamples.Snapshot samples) {}
    private final Screen parent;
    private View view=View.SETTINGS;
    private int page;
    private int columns=1;
    private List<Row> displayedRows=List.of();
    private SettingsUiModel.Layout layout;
    private SettingsUiModel.Page pagination;
    private final List<Button> metricButtons=new ArrayList<>();
    private List<Metric> displayedMetrics;
    private PackStatus displayedStatus;
    private RenderPolicy.RaySelection displayedRays;
    private long statusRefresh;
    private final SettingsUiModel.TimedCache<List<Metric>> timings=new SettingsUiModel.TimedCache<>(500_000_000L,()->List.of(
        new Metric("Pack CPU",PackRenderer.cpuTiming()),new Metric("Pack GPU",PackRenderer.gpuTiming()),
        new Metric("World GPU",WorldPrograms.mainGpu()),new Metric("World copy CPU",WorldPrograms.copyCpu()),
        new Metric("Surface rays CPU",RayWorld.surfaceCpuTiming()),new Metric("Surface rays GPU",RayWorld.surfaceGpuTiming()),
        new Metric("Ray scene CPU",RayWorld.cpuTiming()),new Metric("Ray scene GPU",RayWorld.gpuTiming())));
    public ShaderScreen() { this(null); }
    public ShaderScreen(Screen parent) { super(Component.literal("GreenMarine"));this.parent=parent; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int w=Math.max(1,Math.min(700,width-28)),x=(width-w)/2;
        columns=view==View.SETTINGS&&width>=380?2:1;
        int rowHeight=height>=220?38:24,pitch=rowHeight+4,body=84,nav=height-56;
        int capacity=Math.max(1,(nav-body)/pitch);
        layout=new SettingsUiModel.Layout(x,w,body,pitch,capacity,nav,height-28,24);
        metricButtons.clear();displayedMetrics=null;
        String[] tabs={"Overview","Shader packs","Lighting","Status"};View[] views={View.SETTINGS,View.PACKS,View.RAYS,View.STATUS};
        for(int i=0;i<tabs.length;i++){
            final View target=views[i];int tw=w/4;
            addRenderableWidget(MarineUi.button(x+i*tw,52,tw-4,24,(view==target?"• ":"")+tabs[i],"Open "+tabs[i],()->open(target)));
        }
        displayedStatus=ShaderRuntime.status();displayedRays=ShaderRuntime.rays();
        var rows=rows();displayedRows=rows;pagination=SettingsUiModel.page(page,rows.size(),layout.rows()*columns);page=pagination.index();
        for(int i=pagination.start();i<pagination.end();i++) {
            var row=rows.get(i);
            var button=button(row.label(),row.help(),layout.bodyY()+(i-pagination.start())/columns*layout.rowPitch(),row.action());
            int column=(i-pagination.start())%columns;
            button.setX(layout.x()+column*((layout.width()+6)/columns));
            button.setWidth((layout.width()-6*(columns-1))/columns);
            button.setHeight(layout.rowPitch()-4);
            button.setMessage(Component.literal(font.plainSubstrByWidth(row.label(),button.getWidth()-14)));
            button.active=row.enabled();
            if(view==View.PERFORMANCE && i>0) metricButtons.add(button);
        }
        int gap=4,third=(layout.width()-2*gap)/3;
        var previous=addRenderableWidget(MarineUi.builder(Component.literal("<"),b->{page--;rebuildWidgets();})
            .bounds(layout.x(),layout.navigationY(),third,layout.buttonHeight()).build());
        previous.active=page>0;previous.setTooltip(Tooltip.create(Component.literal("Previous page")));
        var middle=addRenderableWidget(MarineUi.builder(Component.literal((page+1)+" / "+pagination.pages()),b->{page=0;rebuildWidgets();})
            .bounds(layout.x()+third+gap,layout.navigationY(),third,layout.buttonHeight()).build());
        middle.setTooltip(Tooltip.create(Component.literal("Page "+(page+1)+" of "+pagination.pages()+". Press to return to the first page.")));
        var next=addRenderableWidget(MarineUi.builder(Component.literal(">"),b->{page++;rebuildWidgets();})
            .bounds(layout.x()+2*(third+gap),layout.navigationY(),layout.width()-2*(third+gap),layout.buttonHeight()).build());
        next.active=page+1<pagination.pages();next.setTooltip(Tooltip.create(Component.literal("Next page")));
        button(view==View.SETTINGS?"Done":"Back to settings","Return to the previous screen",layout.doneY(),()->{
            if(view==View.SETTINGS) onClose();else open(View.SETTINGS);
        });
    }
    private Button button(String label,String help,int y,Runnable action) {
        var button=addRenderableWidget(MarineUi.builder(Component.literal(fit(label)),b->action.run())
            .bounds(layout.x(),y,layout.width(),layout.buttonHeight()).build());
        button.setTooltip(Tooltip.create(Component.literal(label+"\n"+help)));return button;
    }
    private String fit(String text) { return font.plainSubstrByWidth(text,Math.max(1,layout.width()-16)); }
    private void open(View next) {view=next;page=0;rebuildWidgets();}
    private void change(Consumer<ShaderSettings> action) {action.accept(ShaderRuntime.settings());rebuildWidgets();}
    private static String name(Enum<?> value) {return SettingsUiModel.friendlyName(value.name().toLowerCase(Locale.ROOT));}
    private static String packName(String id) {return id.equals("greenmarine:none")?"Shaders off":ResourcePacks.CATALOG.get(id).map(p->p.definition().name()).orElse("Unavailable: "+id);}
    private Row row(String label,String help,Runnable action) {return new Row(label,help,action,true);}
    private List<Row> rows() {
        var settings=ShaderRuntime.settings();var rows=new ArrayList<Row>();
        switch(view) {
            case SETTINGS -> {
                rows.add(row("Shader pack: "+packName(settings.pack()),"Choose a pack by name. Changes apply immediately; pack parameters are retained.",()->open(View.PACKS)));
                rows.add(row("Quality: "+name(settings.quality()),"Controls pack pass quality and ray budget. Higher settings may increase GPU cost.",()->open(View.QUALITY)));
                rows.add(row("Ray lighting: "+name(settings.rays()),"Select a requested path. Selected path and actual renderer readiness appear in Renderer status.",()->open(View.RAYS)));
                var pack=ResourcePacks.CATALOG.get(settings.pack());
                rows.add(new Row("Pack parameters", "Tune this pack's values. Each pack keeps its own overrides.",()->pack.ifPresent(p->minecraft.gui.setScreen(new ParameterScreen(this,p.definition()))),pack.map(p->!p.definition().parameters().isEmpty()).orElse(false)));
                rows.add(row("Performance", "Separate CPU and GPU measurements, refreshed twice per second. These are stage costs, not total frame time.",()->open(View.PERFORMANCE)));
                rows.add(row("Renderer status",ShaderRuntime.status().detail()+"\n"+ShaderRuntime.rays().reason(),()->open(View.STATUS)));
            }
            case PACKS -> {
                var catalog=ResourcePacks.CATALOG.packs().stream().map(p->new SettingsUiModel.PackChoice(p.definition().id(),p.definition().name())).toList();
                for(var pack:SettingsUiModel.packChoices(catalog,settings.pack())) rows.add(row((pack.id().equals(settings.pack())?"[Selected] ":"")+pack.name(),pack.id(),()->{
                    change(s->ShaderRuntime.apply(new ShaderSettings(pack.id(),s.quality(),s.rays(),s.profiling(),s.parameters())));
                }));
            }
            case QUALITY -> {
                for(var quality:Quality.values()) rows.add(row((quality==settings.quality()?"[Selected] ":"")+name(quality),switch(quality) {
                    case LOW -> "Lowest pack pass budget. Auto ray lighting stays off.";
                    case MEDIUM -> "Balanced raster preset. Auto ray lighting stays off.";
                    case HIGH -> "Higher pack quality. Auto can enable an available ray path.";
                    case ULTRA -> "Highest declared pack quality and ray budget; more GPU work.";
                },()->change(s->ShaderRuntime.apply(new ShaderSettings(s.pack(),quality,s.rays(),s.profiling(),s.parameters())))));
            }
            case RAYS -> {
                for(var mode:RayMode.values()) rows.add(row((mode==settings.rays()?"[Selected] ":"")+name(mode),switch(mode) {
                    case OFF -> "Use raster lighting without ray tracing.";
                    case AUTO -> "Choose a compatible path for this pack and quality. Low and Medium keep rays off.";
                    case VOXEL -> "Use portable voxel rays where this pack supports them.";
                    case HARDWARE -> "Request implemented hardware rays on an enabled host device. Unsupported packs or devices use a fallback.";
                },()->change(s->ShaderRuntime.apply(new ShaderSettings(s.pack(),s.quality(),mode,s.profiling(),s.parameters())))));
                rows.add(row("Selected path: "+name(ShaderRuntime.rays().effective()),ShaderRuntime.rays().reason()+"\n"+ShaderRuntime.status().detail(),()->open(View.STATUS)));
            }
            case STATUS -> {
                rows.add(row("Requested rays: "+name(settings.rays()),"Requested setting",()->open(View.RAYS)));
                rows.add(row("Selected path: "+name(ShaderRuntime.rays().effective()),"Policy-selected path; renderer readiness and fallback details follow below.",()->{}));
                rows.add(row("Renderer: "+name(ShaderRuntime.status().state()),"Actual runtime readiness; hardware selection alone does not establish an active dispatch.",()->{}));
                for(String text:List.of(ShaderRuntime.rays().reason(),ShaderRuntime.status().detail())) {
                    String remaining=text;
                    while(!remaining.isEmpty()) {
                        String line=font.plainSubstrByWidth(remaining,Math.max(1,layout.width()-18));
                        if(line.isEmpty()) break;
                        rows.add(row(line,text,()->{}));remaining=remaining.substring(line.length()).stripLeading();
                    }
                }
            }
            case PERFORMANCE -> {
                rows.add(row("Profiling: "+(settings.profiling()?"On":"Off"),"Enable CPU/GPU samples. GPU queries warm up for 40 frames. Old samples may remain when disabled; these stage timings must not be added as a whole-frame total.",()->change(s->ShaderRuntime.apply(new ShaderSettings(s.pack(),s.quality(),s.rays(),!s.profiling(),s.parameters())))));
                for(var metric:timings.get(System.nanoTime())) rows.add(row(metricLabel(metric),metricHelp(metric),()->{}));
            }
        }
        return rows;
    }
    private static String metricLabel(Metric metric) {
        var s=metric.samples();return metric.name()+": "+(s.count()==0?"No samples":String.format(Locale.ROOT,"%.2f ms | p95 %.2f",s.meanMs(),s.p95Ms()));
    }
    private static String metricHelp(Metric metric) {
        var s=metric.samples();return String.format(Locale.ROOT,"Recent stage samples: %d\nMean %.3f ms | p95 %.3f ms\nMin %.3f ms | Max %.3f ms\nNo samples can mean disabled profiling, warmup, an inactive stage, or unavailable GPU timing.",s.count(),s.meanMs(),s.p95Ms(),s.minMs(),s.maxMs());
    }
    @Override public void tick() {
        if(view==View.STATUS && System.nanoTime()-statusRefresh>=500_000_000L) {
            statusRefresh=System.nanoTime();
            if(!ShaderRuntime.status().equals(displayedStatus) || !ShaderRuntime.rays().equals(displayedRays)) rebuildWidgets();
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        if(view==View.PERFORMANCE) {
            var metrics=timings.get(System.nanoTime());int index=0;
            if(metrics!=displayedMetrics) for(int i=Math.max(1,pagination.start());i<pagination.end();i++) {
                var metric=metrics.get(i-1);var button=metricButtons.get(index++);
                button.setMessage(Component.literal(fit(metricLabel(metric))));button.setTooltip(Tooltip.create(Component.literal(metric.name()+"\n"+metricHelp(metric))));
            }
            displayedMetrics=metrics;
        }
        MarineUi.background(graphics,width,height,"Shaders / "+name(view),name(ShaderRuntime.status().state())+" | "+ShaderRuntime.capabilities().backend());
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
        if(layout.rowPitch()>=40)for(int i=pagination.start();i<pagination.end();i++){
            var row=displayedRows.get(i);int index=i-pagination.start(),cw=(layout.width()-6*(columns-1))/columns;
            String help=row.help().replace('\n',' ');
            graphics.text(font,font.plainSubstrByWidth(help,cw-16),layout.x()+(index%columns)*((layout.width()+6)/columns)+7,layout.bodyY()+index/columns*layout.rowPitch()+24,MarineUi.MUTED,false);
        }
    }
}
