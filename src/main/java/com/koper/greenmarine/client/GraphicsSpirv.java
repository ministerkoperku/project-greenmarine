package com.koper.greenmarine.client;

import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.*;
import java.util.*;

/** Bounded reflection of the actual binaries, including interface blocks and stage-implied arrays. */
public final class GraphicsSpirv {
    public record Slot(boolean patch,int location,int component) {}
    public record Value(int scalar,int width,int signed,int vector,boolean flat,boolean noPerspective,boolean centroid,boolean sample) {}
    private record Type(int op,int[] args) {}
    private record Variable(int pointer,int id,int storage) {}
    private record Decoration(int value,int word) {}
    private final GraphicsStage stage;
    private final ByteBuffer data;
    private final Map<Integer,Type> types=new HashMap<>();
    private final Map<Integer,Integer> constants=new HashMap<>();
    private final Map<Integer,String> names=new HashMap<>();
    private final Map<Long,Map<Integer,Decoration>> decorations=new HashMap<>();
    private final Map<Integer,int[]> modes=new HashMap<>();
    private final Set<Integer> capabilities=new HashSet<>();
    private final List<Variable> variables=new ArrayList<>();
    private final Map<Slot,Value> inputs=new LinkedHashMap<>(),outputs=new LinkedHashMap<>();
    private final List<SpvModule.Reflection.Descriptor> descriptors=new ArrayList<>();
    private int pushConstantSize;

    public GraphicsSpirv(GraphicsStage stage,byte[] bytes) {
        new StageBinary(stage,bytes);this.stage=stage;data=ByteBuffer.wrap(bytes.clone()).order(ByteOrder.LITTLE_ENDIAN);
        try{parse();reflect();}catch(IndexOutOfBoundsException|ArithmeticException failure){throw invalid("Malformed reflection operands");}
    }
    public GraphicsStage stage() {return stage;}
    public byte[] bytes() {return data.array().clone();}
    public Map<Slot,Value> inputs() {return Collections.unmodifiableMap(inputs);}
    public Map<Slot,Value> outputs() {return Collections.unmodifiableMap(outputs);}
    /** Geometry limits cover declared built-ins as well as location-decorated outputs. */
    public int geometryOutputComponents() {
        int count=outputs.values().stream().mapToInt(Value::vector).sum();
        for(var variable:variables)if(variable.storage==3)
            count=Math.addExact(count,builtInComponents(type(variable.pointer).args[1],dec(variable.id,-1),0));
        return count;
    }
    private int builtInComponents(int id,Map<Integer,Decoration> decoration,int depth) {
        if(depth>32)throw invalid("Built-in interface nesting exceeds 32");
        if(decoration.containsKey(11))return scalarComponents(id,depth+1);
        var t=type(id);int count=0;
        if(t.op==30)for(int i=0;i<t.args.length;i++)
            count=Math.addExact(count,builtInComponents(t.args[i],dec(id,i),depth+1));
        return count;
    }
    private int scalarComponents(int id,int depth) {
        if(depth>32)throw invalid("Built-in interface nesting exceeds 32");
        var t=type(id);
        return switch(t.op) {
            case 20,21,22 -> 1;
            case 23,24 -> Math.multiplyExact(scalarComponents(t.args[0],depth+1),t.args[1]);
            case 28 -> Math.multiplyExact(scalarComponents(t.args[0],depth+1),length(t.args[1]));
            default -> throw invalid("Unsupported built-in interface type");
        };
    }
    public List<SpvModule.Reflection.Descriptor> descriptors() {return List.copyOf(descriptors);}
    public Set<Integer> capabilities() {return Set.copyOf(capabilities);}
    public int modeArgument(int mode,int argument,int fallback) {var v=modes.get(mode);return v==null || argument>=v.length?fallback:v[argument];}
    public boolean mode(int mode) {return modes.containsKey(mode);}
    public int pushConstantSize() {return pushConstantSize;}
    private int word(int at) {return data.getInt(at*4);}
    private void parse() {
        for(int at=5;at<data.capacity()/4;) {
            int instruction=word(at),n=instruction>>>16,op=instruction&65535;int[] a=new int[n-1];for(int k=0;k<a.length;k++)a[k]=word(at+k+1);
            switch(op) {
                case 5 -> names.put(a[0],string(at+2,n-2));
                case 17 -> capabilities.add(a[0]);
                case 16 -> modes.put(a[1],Arrays.copyOfRange(a,2,a.length));
                case 19,20,21,22,23,24,25,26,27,28,29,30,32 -> types.put(a[0],new Type(op,Arrays.copyOfRange(a,1,a.length)));
                case 43 -> {if(a.length==3)constants.put(a[1],a[2]);}
                case 59 -> variables.add(new Variable(a[0],a[1],a[2]));
                case 71 -> decorate(key(a[0],-1),a[1],a.length>2?a[2]:1,at+3);
                case 72 -> decorate(key(a[0],a[1]),a[2],a.length>3?a[3]:1,at+4);
                case 73,74,75 -> throw invalid("Decoration groups are not supported");
                default -> {}
            }
            at+=n;
        }
    }
    private String string(int at,int count) {var b=new java.io.ByteArrayOutputStream();for(int i=0;i<count*4;i++){byte v=data.get(at*4+i);if(v==0)break;b.write(v);}return b.toString(java.nio.charset.StandardCharsets.UTF_8);}
    private static long key(int id,int member) {return ((long)id<<32)|(member+1L&0xffffffffL);}
    private void decorate(long key,int kind,int value,int word) {decorations.computeIfAbsent(key,k->new HashMap<>()).put(kind,new Decoration(value,word));}
    private Map<Integer,Decoration> dec(int id,int member) {return decorations.getOrDefault(key(id,member),Map.of());}
    private static int value(Map<Integer,Decoration> d,int kind,int fallback) {var v=d.get(kind);return v==null?fallback:v.value;}
    private Type type(int id) {var t=types.get(id);if(t==null)throw invalid("Unknown type "+id);return t;}
    private int length(int id) {var n=constants.get(id);if(n==null || n<1 || n>65536)throw invalid("Unsupported array extent");return n;}
    private void reflect() {
        for(var v:variables) {
            Type pointer=type(v.pointer);if(pointer.op!=32)throw invalid("Variable lacks pointer type");int id=pointer.args[1];var d=dec(v.id,-1);
            if(v.storage==1 || v.storage==3) {
                if(d.containsKey(11))continue;
                boolean patch=d.containsKey(15),outer=!patch && (v.storage==1&&(stage==GraphicsStage.TESS_CONTROL||stage==GraphicsStage.TESS_EVALUATION||stage==GraphicsStage.GEOMETRY)||v.storage==3&&stage==GraphicsStage.TESS_CONTROL);
                if(outer) {var t=type(id);if(t.op!=28)throw invalid("Missing per-vertex stage array");id=t.args[0];}
                flatten(id,value(d,30,-1),value(d,31,0),d,v.storage==1?inputs:outputs,0);
            } else if(v.storage==0 || v.storage==2 || v.storage==12) {
                if(!d.containsKey(33) || !d.containsKey(34))throw invalid("Descriptor has no explicit set/binding");
                int kind;Type t=type(id);if(t.op==28 || t.op==29)throw invalid("Descriptor arrays are not supported");
                if(v.storage==12 || v.storage==2 && dec(id,-1).containsKey(3))kind=2;
                else if(v.storage==2 && dec(id,-1).containsKey(2))kind=1;
                else if(t.op==27)kind=7;
                else if(t.op==25 && t.args[5]==2)kind=6;
                else throw invalid("Unsupported descriptor type");
                String name=names.getOrDefault(kind==1||kind==2?id:v.id,"");if(name.isBlank())throw invalid("Descriptor has no name");
                descriptors.add(new Descriptor(name,id,kind,d.get(34).word,d.get(33).word));
            } else if(v.storage==9)pushConstantSize=Math.max(pushConstantSize,byteSize(id,Map.of(),0));
        }
    }
    private int flatten(int id,int location,int component,Map<Integer,Decoration> inherited,Map<Slot,Value> target,int depth) {
        if(depth>32)throw invalid("Interface nesting exceeds 32");Type t=type(id);
        if(t.op==30) {
            int next=location;
            for(int i=0;i<t.args.length;i++) {var member=new HashMap<>(inherited);member.putAll(dec(id,i));if(member.containsKey(11))continue;
                int at=value(member,30,next); // Explicit member location overrides the block's running location.
                if(!dec(id,i).containsKey(30))at=next;
                next=flatten(t.args[i],at,value(member,31,component),member,target,depth+1);
            }return next;
        }
        if(location<0 || location>65535 || component<0 || component>3)throw invalid("Missing or invalid interface location/component");
        if(t.op==28) {int next=location;for(int n=length(t.args[1]);n>0;n--)next=flatten(t.args[0],next,component,inherited,target,depth+1);return next;}
        if(t.op==24) {int next=location;for(int n=t.args[1];n>0;n--)next=flatten(t.args[0],next,component,inherited,target,depth+1);return next;}
        int vector=1;if(t.op==23) {vector=t.args[1];t=type(t.args[0]);}
        if((t.op!=21 && t.op!=22) || t.args[0]!=32 || vector<1 || vector>4 || component+vector>4)throw invalid("Unsupported interface scalar width/type");
        var v=new Value(t.op,t.args[0],t.op==21?t.args[1]:0,vector,inherited.containsKey(14),inherited.containsKey(13),inherited.containsKey(16),inherited.containsKey(17));
        // One entry per location retains the complete scalar/vector shape; components cannot overlap.
        var slot=new Slot(inherited.containsKey(15),location,component);
        for(var old:target.entrySet())if(old.getKey().patch==slot.patch&&old.getKey().location==location
            && component<old.getKey().component+old.getValue().vector&&old.getKey().component<component+vector)throw invalid("Overlapping interface location "+location);
        if(target.size()>=65536)throw invalid("Interface exceeds bounded location budget");target.put(slot,v);return location+1;
    }
    private int byteSize(int id,Map<Integer,Decoration> member,int depth) {
        if(depth>32)throw invalid("Push constant nesting exceeds 32");var t=type(id);
        long size=switch(t.op) {
            case 20 -> 4;
            case 21,22 -> t.args[0]/8;
            case 23 -> (long)byteSize(t.args[0],Map.of(),depth+1)*t.args[1];
            case 24 -> (long)value(member,7,byteSize(t.args[0],Map.of(),depth+1))*(member.containsKey(4)?type(t.args[0]).args[1]:t.args[1]);
            case 28 -> (long)value(dec(id,-1),6,byteSize(t.args[0],member,depth+1))*length(t.args[1]);
            case 30 -> {long end=0;for(int i=0;i<t.args.length;i++){var d=dec(id,i);int offset=value(d,35,-1);if(offset<0)throw invalid("Missing push constant member offset");end=Math.max(end,(long)offset+byteSize(t.args[i],d,depth+1));}yield end;}
            default -> throw invalid("Unsupported push constant type");
        };
        if(size<0 || size>65536)throw invalid("Push constant block exceeds bound");return (int)size;
    }
    public static void validateChain(List<GraphicsSpirv> modules,int patchVertices,int pushConstants) {
        if(modules.size()<2 || modules.getFirst().stage!=GraphicsStage.VERTEX || modules.getLast().stage!=GraphicsStage.FRAGMENT)throw invalid("Graphics chain requires vertex and fragment endpoints");
        boolean tc=modules.stream().anyMatch(m->m.stage==GraphicsStage.TESS_CONTROL),te=modules.stream().anyMatch(m->m.stage==GraphicsStage.TESS_EVALUATION);
        if(tc!=te || tc!=(patchVertices>0) || patchVertices<0 || patchVertices>32)throw invalid("Tessellation requires both stages and an explicit input patch size in 1..32");
        int previous=-1;
        for(int i=0;i<modules.size();i++) {
            var current=modules.get(i);if(current.stage.ordinal()<=previous)throw invalid("Duplicate or unordered graphics stage");previous=current.stage.ordinal();
            if(current.pushConstantSize>pushConstants)throw invalid("Intermediate push constants exceed the declared pipeline range");
            if(current.stage==GraphicsStage.GEOMETRY && i>0 && modules.get(i-1).stage==GraphicsStage.TESS_EVALUATION) {
                var tess=modules.get(i-1);
                boolean compatible=tess.mode(10)?current.mode(19):tess.mode(25)?current.mode(20):(tess.mode(22)||tess.mode(24))&&current.mode(22);
                if(!compatible)throw invalid("Geometry primitive input differs from tessellation output");
            }
            if(i==0)continue;var producer=modules.get(i-1);
            for(var input:current.inputs.entrySet()) {
                var output=producer.outputs.get(input.getKey());var expected=input.getValue();
                if(output==null || output.scalar!=expected.scalar || output.width!=expected.width || output.signed!=expected.signed || output.vector!=expected.vector
                    || current.stage==GraphicsStage.FRAGMENT&&(output.flat!=expected.flat || output.noPerspective!=expected.noPerspective))throw invalid("Interface mismatch "+producer.stage+" -> "+current.stage+" at "+input.getKey());
            }
        }
    }
    private final class Descriptor implements SpvModule.Reflection.Descriptor {
        private final String name;private final int type,kind,setWord,bindingWord;
        Descriptor(String name,int type,int kind,int setWord,int bindingWord) {this.name=name;this.type=type;this.kind=kind;this.setWord=setWord;this.bindingWord=bindingWord;}
        public String name(){return name;}
        public int resourceType(){return kind;}
        public int descriptorSetIndex(){return word(setWord);}
        public void descriptorSetIndex(int value){data.putInt(setWord*4,value);}
        public int binding(){return word(bindingWord);}
        public void binding(int value){data.putInt(bindingWord*4,value);}
        public SpvModule.Reflection.Type type(){
            int image=type;var t=GraphicsSpirv.this.type(image);if(t.op==27)t=GraphicsSpirv.this.type(t.args[0]);final Type descriptorType=t;
            return new SpvModule.Reflection.Type() {
                public int baseType(){return descriptorType.op==25?16:15;}
                public int dimensions(){return descriptorType.op==25?descriptorType.args[1]:Integer.MAX_VALUE;}
                public int vectorSize(){return 1;}
                public int arrayDimensions(){return 0;}
                public int arrayLength(int i){throw new IndexOutOfBoundsException(i);}
            };
        }
    }
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException("Graphics SPIR-V: "+message);}
}
