package com.koper.greenmarine.api.render;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

class UniformLayoutTest {
    @Test void std140MembersFollowDeclarationOrderAndReserveArrayAndMatrixStrides() {
        var layout=UniformLayout.parse("float gain; vec3 position; float enabled; mat2x3 basis; float weights[2];");
        assertEquals(96,layout.byteSize());
        assertEquals(List.of(0,16,28,32,64),layout.slots().stream().map(UniformLayout.Slot::offset).toList());
        assertEquals(12,layout.slot("position").byteSize());
        assertEquals(16,layout.slot("weights").arrayStride());
        assertEquals(16,layout.slot("basis").matrixStride());
        assertEquals(2,layout.slot("basis").columns()); assertEquals(3,layout.slot("basis").rows());
        assertEquals(layout.slots(),UniformLayout.of(List.of(new UniformLayout.Member("gain","float",0),
            new UniformLayout.Member("position","vec3",0),new UniformLayout.Member("enabled","float",0),
            new UniformLayout.Member("basis","mat2x3",0),new UniformLayout.Member("weights","float",2))).slots());
    }
    @Test void packingUsesColumnMajorMatricesAndClearsEveryPaddingByte() {
        var layout=UniformLayout.parse("float gain;vec3 position;float enabled;mat2x3 basis;float weights[2];");
        var data=ByteBuffer.allocate(104).order(ByteOrder.BIG_ENDIAN);for(int i=0;i<data.capacity();i++)data.put(i,(byte)0x7f);
        data.position(7).limit(100);
        layout.write(data,Map.of("gain",2f,"position",new float[]{3,4,5},"enabled",6,
            "basis",new float[]{7,8,9,10,11,12},"weights",List.of(13,14)));
        assertEquals(7,data.position());assertEquals(100,data.limit());assertEquals(ByteOrder.BIG_ENDIAN,data.order());
        var nativeData=data.duplicate().order(ByteOrder.nativeOrder());
        int[] offsets={0,16,20,24,28,32,36,40,48,52,56,64,80};
        float[] values={2,3,4,5,6,7,8,9,10,11,12,13,14};var used=new HashSet<Integer>();
        for(int i=0;i<offsets.length;i++){assertEquals(values[i],nativeData.getFloat(offsets[i]));for(int b=0;b<4;b++)used.add(offsets[i]+b);}
        for(int i=0;i<96;i++)if(!used.contains(i))assertEquals(0,data.get(i),"padding byte "+i);
        for(int i=96;i<100;i++)assertEquals((byte)0x7f,data.get(i));
    }
    @Test void matrixArraysAndBooleanArraysHaveIndependentStd140Strides() {
        var layout=UniformLayout.parse("mat3x2 transforms[2];bool flags[2];");
        assertEquals(128,layout.byteSize());assertEquals(48,layout.slot("transforms").arrayStride());
        var data=layout.pack(Map.of("transforms",new float[]{1,2,3,4,5,6,7,8,9,10,11,12},"flags",new boolean[]{true,false}));
        for(int i=0;i<12;i++)assertEquals(i+1,data.getFloat((i/6)*48+(i%6/2)*16+(i%2)*4));
        assertEquals(1,data.getInt(96));assertEquals(0,data.getInt(112));
    }
    @Test void integerAndBooleanValuesKeepTheirBitsAndRejectNarrowing() {
        var layout=UniformLayout.parse("ivec2 signedValue;uvec2 unsignedValue;bvec3 flags;bool active;");
        var data=layout.pack(Map.of("signedValue",new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE},
            "unsignedValue",new long[]{0,0xffff_ffffL},"flags",List.of(true,false,true),"active",false));
        assertEquals(Integer.MIN_VALUE,data.getInt(0));assertEquals(Integer.MAX_VALUE,data.getInt(4));
        assertEquals(0,data.getInt(8));assertEquals(-1,data.getInt(12));
        assertEquals(1,data.getInt(16));assertEquals(0,data.getInt(20));assertEquals(1,data.getInt(24));assertEquals(0,data.getInt(28));
        for(Object invalid:List.of(1.25,2147483648L,-2147483649L,true))
            assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("int v;").pack(Map.of("v",invalid)));
        for(Object invalid:List.of(-1,4294967296L,1.5,false))
            assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("uint v;").pack(Map.of("v",invalid)));
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("bool v;").pack(Map.of("v",1)));
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("float v;").pack(Map.of("v",Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("float v;").pack(Map.of("v",Double.MAX_VALUE)));
    }
    @Test void arbitraryPrecisionNumbersCannotHideFractionalOrOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("int v;").pack(Map.of("v",new java.math.BigDecimal("1.00000000000000000001"))));
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("uint v;").pack(Map.of("v",new java.math.BigDecimal("4294967295.00000000001"))));
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("float v;").pack(Map.of("v",new java.math.BigDecimal("1e-1000"))));
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("float v;").pack(Map.of("v",Math.nextUp((double)Float.MAX_VALUE))));
        var aboveFloatMaximum=new java.math.BigDecimal((double)Float.MAX_VALUE).add(java.math.BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class,()->UniformLayout.parse("float v;").pack(Map.of("v",aboveFloatMaximum)));
        assertEquals(-1,UniformLayout.parse("uint v;").pack(Map.of("v",new java.math.BigInteger("4294967295"))).getInt(0));
    }
    @Test void writesRequireEveryMemberAndValidateBeforeMutatingTheDestination() {
        var layout=UniformLayout.parse("float a;vec2 b;");var target=ByteBuffer.allocate(16);
        for(int i=0;i<16;i++)target.put(i,(byte)7);
        for(var values:List.<Map<String,?>>of(Map.of("a",1),Map.of("a",1,"b",new float[]{2,3},"extra",4),
            Map.of("a",1,"b",new float[]{2}),Map.of("a",1,"b",new String[]{"x","y"}))) {
            assertThrows(IllegalArgumentException.class,()->layout.write(target,values));
            for(int i=0;i<16;i++)assertEquals(7,target.get(i));
        }
        var zero=layout.allocate();for(int i=0;i<zero.limit();i++)assertEquals(0,zero.get(i));
        assertThrows(IllegalArgumentException.class,()->layout.write(ByteBuffer.allocate(15),Map.of("a",1,"b",List.of(2,3))));
        assertThrows(IllegalArgumentException.class,()->layout.write(target.asReadOnlyBuffer(),Map.of("a",1,"b",List.of(2,3))));
    }
    @Test void typedSettersWriteWholeMembersAndKeepOtherMembersUntouched() {
        var layout=UniformLayout.parse("float values[2];int code;uint mask;bool active;");var data=layout.allocate();
        layout.putInts(data,"code",17);layout.putUnsigned(data,"mask",0xffff_ffffL);layout.putBooleans(data,"active",true);
        layout.putFloats(data,"values",2,3);
        assertEquals(2,data.getFloat(0));assertEquals(3,data.getFloat(16));assertEquals(17,data.getInt(32));
        assertEquals(-1,data.getInt(36));assertEquals(1,data.getInt(40));
        assertThrows(IllegalArgumentException.class,()->layout.putFloats(data,"values",1));
        assertThrows(IllegalArgumentException.class,()->layout.putInts(data,"values",1,2));
        assertThrows(IllegalArgumentException.class,()->layout.putUnsigned(data,"mask",-1));
        assertThrows(IllegalArgumentException.class,()->layout.putFloats(data,"missing",1));
    }
    @Test void parserRejectsUnsupportedDeclarationsAndPreservesMultipleDeclaratorOrder() {
        assertEquals(List.of("a","b","matrix"),UniformLayout.parse("/* header */float a,b[2]; // arrays\nmat4 matrix;").slots().stream().map(s->s.member().name()).toList());
        for(String declaration:List.of("","double x;","dvec2 x;","sampler2D tex;","Thing x;","float x[];","float x[2][2];",
            "float x[COUNT];","float x=1;","layout(row_major) mat4 x;","float a;int a;","float x[0];","float x[-1];",
            "vec3 x","float x[2147483647];","struct Thing {float a;};Thing x;","/* unfinished"))
            assertThrows(IllegalArgumentException.class,()->UniformLayout.parse(declaration),declaration);
    }
    @Test void offsetsAndStridesMatchStrictShadercSpirvReflection() {
        String members="float a;vec2 b;vec3 c;float d;ivec4 e;uvec3 f;bool g;bvec2 h;mat2x3 m;mat4x2 n[2];float values[3];mat3 p;";
        var layout=UniformLayout.parse(members);var reflected=reflect(members);
        for(var slot:layout.slots()) {
            assertEquals(slot.offset(),reflected.get(slot.member().name()+".offset"),slot.member().name());
            if(slot.arrayStride()!=0)assertEquals(slot.arrayStride(),reflected.get(slot.member().name()+".arrayStride"),slot.member().name());
            if(slot.matrixStride()!=0)assertEquals(slot.matrixStride(),reflected.get(slot.member().name()+".matrixStride"),slot.member().name());
        }
    }
    private static Map<String,Integer> reflect(String members) {
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize(),result=0;
        try {
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            String source="#version 460\nlayout(std140,set=0,binding=0) uniform Packed {"+members+"};\nlayout(std430,set=0,binding=1) buffer Output {vec4 outputValue;};layout(local_size_x=1) in;void main(){outputValue=vec4(a);}";
            result=shaderc_compile_into_spv(compiler,source,shaderc_compute_shader,"layout.csh","main",options);
            assertEquals(shaderc_compilation_status_success,shaderc_result_get_compilation_status(result),shaderc_result_get_error_message(result));
            var words=shaderc_result_get_bytes(result).duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            var names=new HashMap<Integer,String>();var memberNames=new HashMap<String,String>();var structures=new HashMap<Integer,int[]>();
            var arrays=new HashMap<Integer,Integer>();var decorations=new ArrayList<int[]>();
            for(int at=5;at<words.limit();) {
                int header=words.get(at),size=header>>>16,op=header&0xffff;
                if(op==5)names.put(words.get(at+1),spirvString(words,at+2,at+size));
                if(op==6)memberNames.put(words.get(at+1)+":"+words.get(at+2),spirvString(words,at+3,at+size));
                if(op==30){int[] types=new int[size-2];for(int i=0;i<types.length;i++)types[i]=words.get(at+2+i);structures.put(words.get(at+1),types);}
                if(op==71&&size>=4&&words.get(at+2)==6)arrays.put(words.get(at+1),words.get(at+3));
                if(op==72&&size>=5)decorations.add(new int[]{words.get(at+1),words.get(at+2),words.get(at+3),words.get(at+4)});
                at+=size;
            }
            var values=new HashMap<String,Integer>();
            for(var decoration:decorations)if("Packed".equals(names.get(decoration[0]))) {
                String name=memberNames.get(decoration[0]+":"+decoration[1]);
                if(decoration[2]==35)values.put(name+".offset",decoration[3]);
                if(decoration[2]==7)values.put(name+".matrixStride",decoration[3]);
                Integer stride=arrays.get(structures.get(decoration[0])[decoration[1]]);if(stride!=null)values.put(name+".arrayStride",stride);
            }
            return values;
        } finally {if(result!=0)shaderc_result_release(result);shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }
    private static String spirvString(IntBuffer words,int start,int end) {
        var bytes=ByteBuffer.allocate((end-start)*4).order(ByteOrder.LITTLE_ENDIAN);for(int i=start;i<end;i++)bytes.putInt(words.get(i));
        int length=0;while(length<bytes.capacity()&&bytes.get(length)!=0)length++;return new String(bytes.array(),0,length,StandardCharsets.UTF_8);
    }
}
