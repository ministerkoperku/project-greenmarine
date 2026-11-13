package com.koper.greenmarine.api.render;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.regex.Pattern;

/** Declaration-ordered std140 packing for 32-bit GLSL values, column-major matrices and fixed arrays.
 * Structs, doubles, runtime/multidimensional arrays and explicit member layouts are unsupported.
 * Writes use native byte order and absolute indices starting at zero, preserving buffer state.
 */
public final class UniformLayout {
    /** arrayLength is zero for a nonarray member, or a positive fixed array length. */
    public record Member(String name, String type, int arrayLength) {
        public Member {
            if(name==null||!name.matches("[A-Za-z_][A-Za-z0-9_]*")||name.startsWith("gl_"))throw invalid("invalid member name: "+name);
            if(type==null)throw invalid("missing member type: "+name);
            if(arrayLength<0)throw invalid("negative array length: "+name);
        }
    }
    /** Strides are zero when the corresponding array/matrix dimension is absent. */
    public record Slot(Member member,int offset,int byteSize,int alignment,int arrayStride,int matrixStride,int columns,int rows) {}
    private enum Kind { FLOAT, INT, UINT, BOOL }
    private record Type(Kind kind,int columns,int rows,boolean matrix) {
        int components(){return columns*rows;}
        int alignment(){return matrix||rows>=3?16:rows*4;}
        int size(){return matrix?columns*16:rows*4;}
    }
    private final List<Slot> slots;
    private final Map<String,Slot> named;
    private final Map<String,Type> types;
    private final int byteSize;
    private UniformLayout(List<Slot> slots,Map<String,Type> types,int byteSize) {
        this.slots=List.copyOf(slots);this.types=Map.copyOf(types);this.byteSize=byteSize;
        var named=new LinkedHashMap<String,Slot>();for(var slot:slots)named.put(slot.member().name(),slot);this.named=Collections.unmodifiableMap(named);
    }
    public static UniformLayout of(List<Member> members) {
        if(members==null||members.isEmpty())throw invalid("layout needs at least one member");
        var slots=new ArrayList<Slot>();var types=new LinkedHashMap<String,Type>();int offset=0;
        try {
            for(var member:members) {
                if(member==null)throw invalid("null member");var type=type(member.type());
                if(types.putIfAbsent(member.name(),type)!=null)throw invalid("duplicate member: "+member.name());
                int alignment=member.arrayLength()>0?16:type.alignment();
                int stride=member.arrayLength()>0?align(type.size(),16):0;
                int bytes=member.arrayLength()>0?Math.multiplyExact(stride,member.arrayLength()):type.size();
                // Component counts are also checked before a caller can allocate values for an overflowing array.
                Math.multiplyExact(type.components(),Math.max(1,member.arrayLength()));offset=align(offset,alignment);
                slots.add(new Slot(member,offset,bytes,alignment,stride,type.matrix()?16:0,type.columns(),type.rows()));
                offset=Math.addExact(offset,bytes);
            }
            return new UniformLayout(slots,types,align(offset,16));
        }catch(ArithmeticException overflow){throw invalid("layout exceeds the supported buffer size");}
    }
    /** Parses a block's member declarations, including comma-separated declarators and comments. */
    public static UniformLayout parse(String declarations) {
        if(declarations==null)throw invalid("missing declarations");
        String source=stripComments(declarations).strip();
        if(!source.endsWith(";"))throw invalid("member declaration needs a semicolon");
        var members=new ArrayList<Member>();
        for(String statement:source.split(";",-1)) {
            if(statement.isBlank())continue;
            var declaration=Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+(.+)",Pattern.DOTALL).matcher(statement);
            if(!declaration.matches())throw invalid("unsupported declaration: "+statement.strip());
            String type=declaration.group(1);type(type);
            for(String item:declaration.group(2).split(",",-1)) {
                var member=Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\[\\s*([1-9][0-9]*)[uU]?\\s*])?\\s*").matcher(item);
                if(!member.matches())throw invalid("unsupported member declaration: "+item.strip());
                int length=0;
                if(member.group(2)!=null)try{length=Integer.parseInt(member.group(2));}catch(NumberFormatException overflow){throw invalid("array extent overflows: "+item.strip());}
                members.add(new Member(member.group(1),type,length));
            }
        }
        return of(members);
    }
    public List<Slot> slots(){return slots;}
    public Slot slot(String name){var slot=named.get(name);if(slot==null)throw invalid("unknown member: "+name);return slot;}
    public int byteSize(){return byteSize;}
    /** Explicitly allocates an initialized block without requiring values for its members. */
    public ByteBuffer allocate(){var data=ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder());zero(data,0,byteSize);return data;}
    public ByteBuffer pack(Map<String,?> values){var data=allocate();write(data,values);return data;}
    /**
     * Requires exactly one complete value for every member; missing and unknown names fail.
     * Values are scalar Numbers/Booleans or flat primitive arrays/Lists. Matrix components are
     * column-major within each array element. All values are validated before changing any byte.
     */
    public void write(ByteBuffer target,Map<String,?> values) {
        checkBuffer(target);
        if(values==null||values.size()!=slots.size()||!values.keySet().equals(named.keySet()))throw invalid("values must contain exactly the declared member names");
        var prepared=new ArrayList<int[]>();for(var slot:slots)prepared.add(prepare(slot,values.get(slot.member().name())));
        var data=nativeView(target);zero(data,0,byteSize);
        for(int i=0;i<slots.size();i++)put(data,slots.get(i),prepared.get(i));
    }
    public void putFloats(ByteBuffer target,String name,float... values){putTyped(target,name,Kind.FLOAT,values);}
    public void putInts(ByteBuffer target,String name,int... values){putTyped(target,name,Kind.INT,values);}
    /** Unsigned values must be in [0, 4294967295]; their raw 32-bit representation is written. */
    public void putUnsigned(ByteBuffer target,String name,long... values){putTyped(target,name,Kind.UINT,values);}
    public void putBooleans(ByteBuffer target,String name,boolean... values){putTyped(target,name,Kind.BOOL,values);}
    private void putTyped(ByteBuffer target,String name,Kind expected,Object values) {
        var slot=slot(name);if(types.get(name).kind()!=expected)throw invalid("wrong value type for "+name);
        checkBuffer(target);var prepared=prepare(slot,values);var data=nativeView(target);
        zero(data,slot.offset(),slot.byteSize());put(data,slot,prepared);
    }
    private int[] prepare(Slot slot,Object value) {
        var type=types.get(slot.member().name());int count=type.components()*Math.max(1,slot.member().arrayLength());
        if(value==null)throw invalid("null value for "+slot.member().name());
        boolean array=value.getClass().isArray();boolean list=value instanceof List<?>;
        int length=array?Array.getLength(value):list?((List<?>)value).size():1;
        if(length!=count)throw invalid("member "+slot.member().name()+" needs "+count+" components, received "+length);
        var bits=new int[count];
        for(int i=0;i<count;i++) {
            Object component=array?Array.get(value,i):list?((List<?>)value).get(i):value;
            if(type.kind()==Kind.BOOL) {
                if(!(component instanceof Boolean flag))throw invalid("member "+slot.member().name()+" requires boolean values");
                bits[i]=flag?1:0;continue;
            }
            if(!(component instanceof Number number))throw invalid("member "+slot.member().name()+" requires numeric values");
            double numeric=number.doubleValue();
            if(!Double.isFinite(numeric))throw invalid("nonfinite value for "+slot.member().name());
            if(type.kind()==Kind.FLOAT) {
                float narrowed=(float)numeric;
                boolean nonzero=numeric!=0||number instanceof java.math.BigDecimal decimal&&decimal.signum()!=0||number instanceof java.math.BigInteger integer&&integer.signum()!=0;
                boolean aboveRange=Math.abs(numeric)>(double)Float.MAX_VALUE
                    ||number instanceof java.math.BigDecimal decimal&&decimal.abs().compareTo(new java.math.BigDecimal((double)Float.MAX_VALUE))>0
                    ||number instanceof java.math.BigInteger integer&&new java.math.BigDecimal(integer.abs()).compareTo(new java.math.BigDecimal((double)Float.MAX_VALUE))>0;
                if(aboveRange||!Float.isFinite(narrowed)||(narrowed==0&&nonzero))throw invalid("float value out of range for "+slot.member().name());
                bits[i]=Float.floatToRawIntBits(narrowed);
            }else{
                long integer;
                try {
                    if(number instanceof java.math.BigDecimal decimal)integer=decimal.longValueExact();
                    else if(number instanceof java.math.BigInteger precise)integer=precise.longValueExact();
                    else if(number instanceof Byte||number instanceof Short||number instanceof Integer||number instanceof Long)integer=number.longValue();
                    else if(number instanceof Float||number instanceof Double) {
                        if(numeric!=Math.rint(numeric)||numeric<Long.MIN_VALUE||numeric>=0x1p63)throw new ArithmeticException();
                        integer=(long)numeric;
                    }else integer=new java.math.BigDecimal(number.toString()).longValueExact();
                }catch(ArithmeticException|NumberFormatException invalid){throw invalid("integer value out of range for "+slot.member().name());}
                long min=type.kind()==Kind.INT?Integer.MIN_VALUE:0,max=type.kind()==Kind.INT?Integer.MAX_VALUE:4294967295L;
                if(integer<min||integer>max)throw invalid("integer value out of range for "+slot.member().name());
                bits[i]=(int)integer;
            }
        }
        return bits;
    }
    private static void put(ByteBuffer data,Slot slot,int[] bits) {
        int perElement=slot.columns()*slot.rows();
        for(int i=0;i<bits.length;i++) {
            int element=i/perElement,component=i%perElement;
            int at=slot.offset()+element*slot.arrayStride();
            if(slot.matrixStride()!=0)at+=(component/slot.rows())*slot.matrixStride()+(component%slot.rows())*4;
            else at+=component*4;
            data.putInt(at,bits[i]);
        }
    }
    private void checkBuffer(ByteBuffer target){if(target==null||target.isReadOnly()||target.limit()<byteSize)throw invalid("destination needs "+byteSize+" writable bytes");}
    private static ByteBuffer nativeView(ByteBuffer target){return target.duplicate().order(ByteOrder.nativeOrder());}
    private static void zero(ByteBuffer data,int start,int length){for(int i=start;i<start+length;i++)data.put(i,(byte)0);}
    private static int align(int value,int alignment){return Math.multiplyExact(Math.floorDiv(Math.addExact(value,alignment-1),alignment),alignment);}
    private static Type type(String type) {
        if(type==null)throw invalid("missing type");
        Kind scalar=switch(type){case "float"->Kind.FLOAT;case "int"->Kind.INT;case "uint"->Kind.UINT;case "bool"->Kind.BOOL;default->null;};
        if(scalar!=null)return new Type(scalar,1,1,false);
        var vector=Pattern.compile("([biu]?)vec([234])").matcher(type);
        if(vector.matches())return new Type(switch(vector.group(1)){case "b"->Kind.BOOL;case "i"->Kind.INT;case "u"->Kind.UINT;default->Kind.FLOAT;},1,Integer.parseInt(vector.group(2)),false);
        var matrix=Pattern.compile("mat([234])(?:x([234]))?").matcher(type);
        if(matrix.matches()){int columns=Integer.parseInt(matrix.group(1));return new Type(Kind.FLOAT,columns,matrix.group(2)==null?columns:Integer.parseInt(matrix.group(2)),true);}
        throw invalid("unsupported GLSL type: "+type);
    }
    private static String stripComments(String source) {
        var out=new StringBuilder();
        for(int i=0;i<source.length();) {
            if(source.startsWith("//",i)){int end=source.indexOf('\n',i+2);if(end<0)break;out.append('\n');i=end+1;}
            else if(source.startsWith("/*",i)){int end=source.indexOf("*/",i+2);if(end<0)throw invalid("unterminated block comment");out.append(' ');i=end+2;}
            else out.append(source.charAt(i++));
        }
        return out.toString();
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException("std140 layout: "+message);}
}
