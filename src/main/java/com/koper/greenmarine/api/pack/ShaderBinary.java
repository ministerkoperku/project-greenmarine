package com.koper.greenmarine.api.pack;

import java.nio.*;
import java.util.*;

/** Structural checks complement offline spirv-val; they are not full semantic validation. */
public final class ShaderBinary {
    public static final int MAX_BYTES=4*1024*1024;
    private final byte[] bytes;
    private final Set<Integer> capabilities;
    public ShaderBinary(byte[] source) {
        if(source==null || source.length<20 || source.length>MAX_BYTES || source.length%4!=0) throw new IllegalArgumentException("Invalid SPIR-V size");
        bytes=source.clone(); var words=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version=words.getInt(4),bound=words.getInt(12);
        if(words.getInt(0)!=0x07230203 || version<0x00010000 || version>0x00010600
            || (version&0xFF)!=0 || bound<1 || bound>1_000_000 || words.getInt(16)!=0) throw new IllegalArgumentException("Invalid SPIR-V header");
        var caps=new HashSet<Integer>(); boolean entry=false,memory=false,function=false,end=false;
        for(int offset=20;offset<bytes.length;) {
            int instruction=words.getInt(offset),count=instruction>>>16,opcode=instruction&0xFFFF;
            if(count<1 || (long)offset+count*4L>bytes.length) throw new IllegalArgumentException("Truncated SPIR-V instruction");
            if(opcode==17) { if(count!=2) throw new IllegalArgumentException("Invalid capability"); caps.add(words.getInt(offset+4)); }
            if(opcode==14) { if(count!=3) throw new IllegalArgumentException("Invalid memory model"); memory=true; }
            if(opcode==15) {
                if(count<5 || words.getInt(offset+4)!=4 || words.getInt(offset+8)<=0 || words.getInt(offset+8)>=bound) throw new IllegalArgumentException("SPIR-V must expose a fragment entry point");
                if(words.getInt(offset+12)==0x6E69616D && words.getInt(offset+16)==0) entry=true;
            }
            if(opcode==54) function=true; if(opcode==56) end=true;
            offset+=count*4;
        }
        if(!entry || !memory || !function || !end || !caps.contains(1)) throw new IllegalArgumentException("Incomplete fragment SPIR-V module");
        capabilities=Set.copyOf(caps);
    }
    public byte[] bytes() { return bytes.clone(); }
    public Set<Integer> capabilities() { return capabilities; }
    public boolean baselineCompatible() { return Set.of(1,43,50,51).containsAll(capabilities); }
}
