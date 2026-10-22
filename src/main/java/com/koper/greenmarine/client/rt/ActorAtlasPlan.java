package com.koper.greenmarine.client.rt;

import java.util.*;
import java.nio.ByteBuffer;

/** Deterministic bounded atlas layout and affine native-UV remapping; no GPU or mod linkage. */
final class ActorAtlasPlan {
    static final int LIMIT=2048,GUTTER=2;
    record Source(String id,int width,int height) {}
    record Rect(int x,int y,int width,int height) {}
    record Plan(int width,int height,Map<String,Rect> rectangles) {}
    private ActorAtlasPlan() {}
    static Plan pack(List<Source> sources) {
        var unique=new LinkedHashMap<String,Source>();
        for(var source:sources) {
            if(source.id()==null || source.width()<1 || source.height()<1 || source.width()>LIMIT-2*GUTTER || source.height()>LIMIT-2*GUTTER)
                throw new IllegalArgumentException("Actor textures do not fit the 2048-pixel atlas with gutters");
            var old=unique.putIfAbsent(source.id(),source);
            if(old!=null && !old.equals(source))throw new IllegalArgumentException("Conflicting dimensions for actor texture "+source.id());
        }
        if(unique.isEmpty())throw new IllegalArgumentException("Empty actor texture atlas");
        var sorted=new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingInt(Source::height).reversed().thenComparing(Comparator.comparingInt(Source::width).reversed()).thenComparing(Source::id));
        int minimum=sorted.stream().mapToInt(s->s.width()+2*GUTTER).max().orElse(1);
        Plan best=null;
        for(int width=powerOfTwo(minimum);width<=LIMIT;width*=2) {
            int x=0,y=0,row=0;boolean fits=true;var rectangles=new LinkedHashMap<String,Rect>();
            for(var source:sorted) {
                int w=source.width()+2*GUTTER,h=source.height()+2*GUTTER;
                if(x+w>width) {x=0;y+=row;row=0;}
                if(y+h>LIMIT) {fits=false;break;}
                rectangles.put(source.id(),new Rect(x+GUTTER,y+GUTTER,source.width(),source.height()));
                x+=w;row=Math.max(row,h);
            }
            if(!fits)continue;
            int height=powerOfTwo(y+row);
            if(best==null || width*height<best.width()*best.height() || (width*height==best.width()*best.height() && Math.max(width,height)<Math.max(best.width(),best.height())))
                best=new Plan(width,height,Collections.unmodifiableMap(rectangles));
        }
        if(best==null)throw new IllegalArgumentException("Actor textures exceed the 2048x2048 atlas budget");
        return best;
    }
    private static int powerOfTwo(int value) {int out=1;while(out<value)out*=2;return out;}
    static void remap(ByteBuffer data,int first,int count,Rect rectangle,int atlasWidth,int atlasHeight) {
        int available=(data.limit()-16)/288;
        if(data.isReadOnly() || first<0 || count<0 || first>available-count || atlasWidth<1 || atlasHeight<1
            || rectangle.x()<0 || rectangle.y()<0 || rectangle.width()<1 || rectangle.height()<1
            || rectangle.x()>atlasWidth-rectangle.width() || rectangle.y()>atlasHeight-rectangle.height())
            throw new IllegalArgumentException("Invalid actor atlas UV range");
        float scaleU=rectangle.width()/(float)atlasWidth,scaleV=rectangle.height()/(float)atlasHeight;
        float offsetU=rectangle.x()/(float)atlasWidth,offsetV=rectangle.y()/(float)atlasHeight;
        for(int box=first;box<first+count;box++)for(int face=0;face<6;face++) {
            int u=16+box*288+96+face*16,v=16+box*288+192+face*16;
            for(int component=0;component<4;component++) {
                data.putFloat(u+component*4,data.getFloat(u+component*4)*scaleU+(component==3?offsetU:0));
                data.putFloat(v+component*4,data.getFloat(v+component*4)*scaleV+(component==3?offsetV:0));
            }
        }
    }
}
