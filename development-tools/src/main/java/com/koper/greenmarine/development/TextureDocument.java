package com.koper.greenmarine.development;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;

/** Bounded PNG document with stroke undo and an exact disk baseline for conflict detection. */
final class TextureDocument {
    private final int width,height;
    private int[] pixels,saved,stroke;
    private byte[] baseline;
    private final Deque<int[]> undo=new ArrayDeque<>();
    TextureDocument(int width,int height) {
        if(width<1||height<1||width>256||height>256)throw new IllegalArgumentException("Texture dimensions must be 1..256");
        this.width=width;this.height=height;pixels=new int[width*height];saved=pixels.clone();
    }
    static TextureDocument decode(byte[] bytes)throws IOException {
        MaterialCanvas.previewPng(bytes);
        var image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null)throw new IOException("Cannot decode PNG");
        var doc=new TextureDocument(image.getWidth(),image.getHeight());
        image.getRGB(0,0,doc.width,doc.height,doc.pixels,0,doc.width);doc.saved=doc.pixels.clone();doc.baseline=bytes.clone();return doc;
    }
    int width(){return width;} int height(){return height;}
    int pixel(int x,int y){return pixels[y*width+x];}
    boolean dirty(){return !Arrays.equals(saved,pixels);}
    byte[] baseline(){return baseline==null?null:baseline.clone();}
    void saved(byte[] bytes){baseline=bytes.clone();saved=pixels.clone();}
    void beginStroke(){if(stroke==null)stroke=pixels.clone();}
    void endStroke(){if(stroke!=null&&!Arrays.equals(stroke,pixels)){undo.addLast(stroke);while(undo.size()>32)undo.removeFirst();}stroke=null;}
    boolean undo(){endStroke();if(undo.isEmpty())return false;pixels=undo.removeLast();return true;}
    void paint(int x0,int y0,int x1,int y1,int argb,int diameter) {
        if(stroke==null)throw new IllegalStateException("Begin a stroke first");
        if(diameter<1||diameter>32)throw new IllegalArgumentException("Brush must be 1..32");
        x0=Math.clamp(x0,0,width-1);x1=Math.clamp(x1,0,width-1);y0=Math.clamp(y0,0,height-1);y1=Math.clamp(y1,0,height-1);
        int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,error=dx+dy;
        while(true){
            int start=-(diameter-1)/2;
            for(int by=start;by<start+diameter;by++)for(int bx=start;bx<start+diameter;bx++){
                int x=x0+bx,y=y0+by;if(x>=0&&x<width&&y>=0&&y<height)pixels[y*width+x]=argb;
            }
            if(x0==x1&&y0==y1)break;int twice=2*error;if(twice>=dy){error+=dy;x0+=sx;}if(twice<=dx){error+=dx;y0+=sy;}
        }
    }
    byte[] encode()throws IOException {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);
        var bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"PNG",bytes))throw new IOException("PNG encoder unavailable");return bytes.toByteArray();
    }
}
