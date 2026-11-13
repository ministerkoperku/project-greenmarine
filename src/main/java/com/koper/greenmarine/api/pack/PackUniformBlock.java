package com.koper.greenmarine.api.pack;

import com.koper.greenmarine.api.render.UniformLayout;
import java.nio.ByteBuffer;
import java.util.*;

/** Immutable, declaration-ordered std140 block with explicit host-value sources. No GPU calls. */
public final class PackUniformBlock {
    public static final int MAX_BYTES=65_536;

    /** A zero arrayLength denotes a nonarray member; source is an exact host-map key. */
    public record Member(String name,String type,int arrayLength,String source) {
        public Member(String name,String type,String source) { this(name,type,0,source); }
        public Member {
            new UniformLayout.Member(name,type,arrayLength);
            if(source==null || !source.matches("[A-Za-z_][A-Za-z0-9_.]{0,191}"))
                throw new IllegalArgumentException("Invalid uniform host source: "+source);
        }
    }

    private final List<Member> members;
    private final UniformLayout layout;
    private final Map<String,String> sources;

    public PackUniformBlock(List<Member> members) {
        this.members=List.copyOf(members);
        layout=UniformLayout.of(this.members.stream()
            .map(member -> new UniformLayout.Member(member.name(),member.type(),member.arrayLength())).toList());
        if(layout.byteSize()>MAX_BYTES)throw new IllegalArgumentException("Uniform block exceeds 64 KiB");
        var sources=new LinkedHashMap<String,String>();
        for(var member:this.members)sources.put(member.name(),member.source());
        this.sources=Collections.unmodifiableMap(sources);
    }

    public List<Member> members() { return members; }
    public UniformLayout layout() { return layout; }
    /** Member name to exact host-map key, in declaration order. */
    public Map<String,String> sources() { return sources; }

    /** Extra host keys are permitted; every declared source must have a complete, valid value. */
    public ByteBuffer pack(Map<String,?> hostValues) { return layout.pack(resolve(hostValues)); }
    /** Validates every source before writing, preserving the destination on failure. */
    public void write(ByteBuffer target,Map<String,?> hostValues) { layout.write(target,resolve(hostValues)); }

    private Map<String,Object> resolve(Map<String,?> hostValues) {
        if(hostValues==null)throw new IllegalArgumentException("Missing uniform host values");
        var values=new LinkedHashMap<String,Object>();
        for(var source:sources.entrySet()) {
            Object value=hostValues.get(source.getValue());
            if(value==null)throw new IllegalArgumentException("Missing uniform host source: "+source.getValue()+" (member "+source.getKey()+")");
            values.put(source.getKey(),value);
        }
        return values;
    }
}
