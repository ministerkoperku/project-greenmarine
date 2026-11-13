package com.koper.greenmarine.api.render;

import java.nio.*;
import java.util.*;

/** Stage-aware structural SPIR-V validation. Device and interface checks happen during compilation. */
public final class StageBinary {
    private final GraphicsStage stage;
    private final byte[] bytes;
    public StageBinary(GraphicsStage stage,byte[] source) {
        this.stage=Objects.requireNonNull(stage);
        if(source==null || source.length<20 || source.length>4*1024*1024 || source.length%4!=0)throw new IllegalArgumentException("Invalid stage SPIR-V size");
        bytes=source.clone();var w=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version=w.getInt(4),bound=w.getInt(12);
        if(w.getInt(0)!=0x07230203 || version<0x10000 || version>0x10600 || (version&255)!=0 || bound<1 || bound>1_000_000 || w.getInt(16)!=0)throw new IllegalArgumentException("Invalid stage SPIR-V header");
        boolean entry=false,memory=false,function=false,end=false;Set<Integer> caps=new HashSet<>();
        for(int at=20;at<bytes.length;) {
            int instruction=w.getInt(at),n=instruction>>>16,op=instruction&65535;
            if(n<1 || (long)at+n*4L>bytes.length)throw new IllegalArgumentException("Truncated stage SPIR-V instruction");
            if(op==17) {if(n!=2)throw new IllegalArgumentException("Invalid stage capability");caps.add(w.getInt(at+4));}
            if(op==14) {if(n!=3)throw new IllegalArgumentException("Invalid stage memory model");memory=true;}
            if(op==15) {
                if(n<5 || entry || w.getInt(at+4)!=stage.executionModel() || w.getInt(at+8)<=0 || w.getInt(at+8)>=bound
                    || w.getInt(at+12)!=0x6e69616d || w.getInt(at+16)!=0)throw new IllegalArgumentException("SPIR-V must expose exactly one main entry point for "+stage);
                entry=true;
            }
            if(op==54)function=true;if(op==56)end=true;at+=n*4;
        }
        int needed=stage==GraphicsStage.GEOMETRY?2:stage==GraphicsStage.TESS_CONTROL || stage==GraphicsStage.TESS_EVALUATION?3:1;
        if(!entry || !memory || !function || !end || !caps.contains(needed))throw new IllegalArgumentException("Incomplete "+stage+" SPIR-V module");
    }
    public GraphicsStage stage() {return stage;}
    public byte[] bytes() {return bytes.clone();}
}
