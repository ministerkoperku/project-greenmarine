package com.koper.greenmarine.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Pure layout and navigation policies, independent of Minecraft's GUI lifecycle. */
final class SettingsUiModel {
    record Layout(int x,int width,int bodyY,int rowPitch,int rows,int navigationY,int doneY,int buttonHeight) {}
    record Page(int index,int pages,int start,int end) {}
    record PackChoice(String id,String name) {}
    private SettingsUiModel() {}

    static Layout layout(int width,int height) {
        int contentWidth=Math.max(1,Math.min(460,width-16));
        int buttonHeight=height>=160?28:Math.max(16,Math.min(24,(height-64)/3));
        int pitch=buttonHeight+4;
        int doneY=Math.max(0,height-buttonHeight-6);
        int navigationY=Math.max(0,doneY-pitch);
        int bodyY=Math.min(66,Math.max(18,navigationY-pitch));
        int rows=Math.max(1,(navigationY-bodyY)/pitch);
        return new Layout((width-contentWidth)/2,contentWidth,bodyY,pitch,rows,navigationY,doneY,buttonHeight);
    }
    static Page page(int requested,int count,int capacity) {
        if(count<0 || capacity<1) throw new IllegalArgumentException("Invalid pagination");
        int pages=Math.max(1,(int)((count+(long)capacity-1)/capacity));
        int index=Math.clamp(requested,0,pages-1);
        int start=index*capacity;
        return new Page(index,pages,start,Math.min(count,start+capacity));
    }
    static List<PackChoice> packChoices(List<PackChoice> catalog,String selected) {
        var choices=new ArrayList<PackChoice>();
        choices.add(new PackChoice("greenmarine:none","Shaders off"));
        for(var pack:catalog) if(choices.stream().noneMatch(p->p.id().equals(pack.id()))) choices.add(pack);
        if(choices.stream().noneMatch(p->p.id().equals(selected))) choices.add(new PackChoice(selected,"Unavailable: "+selected));
        return List.copyOf(choices);
    }
    static String friendlyName(String name) {
        String spaced=name.replaceAll("([a-z0-9])([A-Z])","$1 $2").replace('_',' ').trim();
        var words=spaced.split(" +");
        for(int i=0;i<words.length;i++) if(!words[i].isEmpty()) words[i]=words[i].substring(0,1).toUpperCase(Locale.ROOT)+words[i].substring(1);
        return String.join(" ",words);
    }
    static final class TimedCache<T> {
        private final long interval;
        private final Supplier<T> source;
        private boolean initialized;
        private long sampled;
        private T value;
        TimedCache(long interval,Supplier<T> source) { this.interval=interval;this.source=source; }
        T get(long now) {
            if(!initialized || now-sampled>=interval) {value=source.get();sampled=now;initialized=true;}
            return value;
        }
    }
}
