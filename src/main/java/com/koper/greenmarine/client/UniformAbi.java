package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackUniformBlock;
import com.koper.greenmarine.api.render.UniformLayout;
import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** Validates pack std140 declarations against the actual compiled shader module. */
public final class UniformAbi {
    private UniformAbi() {}
    public record Member(String name,String type,int arrayLength,int offset,int byteSize,
                         int arrayStride,int matrixStride,boolean rowMajor,int arrayDimensions,boolean literalArray) {}
    public record Block(String name,int byteSize,List<Member> members,boolean arrayed) {
        public Block { members=List.copyOf(members); }
    }
    /** Reads remaining bytes without changing the caller's position, limit or byte order.
     * Names follow host UBO semantics: block type name first, resource name as a fallback.
     * Unsupported member shapes remain in metadata so unmapped engine blocks can be ignored.
     */
    public static Map<String,Block> reflect(ByteBuffer spirv) {
        if(spirv==null || spirv.remaining()<20 || spirv.remaining()%4!=0)throw invalid("Invalid SPIR-V size");
        var header=spirv.slice().order(ByteOrder.LITTLE_ENDIAN);
        if(header.getInt(0)!=0x07230203 || header.getInt(12)<=0 || header.getInt(16)!=0)throw invalid("Invalid SPIR-V header");
        var bytes=memAlloc(spirv.remaining());
        try(var stack=MemoryStack.stackPush()) {
            bytes.order(ByteOrder.LITTLE_ENDIAN).put(spirv.duplicate()).flip();
            var pointer=stack.mallocPointer(1);
            check(spvc_context_create(pointer),"creating reflection context");long context=pointer.get(0);
            try {
                check(spvc_context_parse_spirv(context,bytes.asIntBuffer(),bytes.remaining()/4,pointer),"parsing SPIR-V");long ir=pointer.get(0);
                check(spvc_context_create_compiler(context,SPVC_BACKEND_NONE,ir,SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,pointer),"creating reflection compiler");long compiler=pointer.get(0);
                check(spvc_compiler_create_shader_resources(compiler,pointer),"reflecting shader resources");long resources=pointer.get(0);
                var list=stack.mallocPointer(1);var count=stack.mallocPointer(1);
                check(spvc_resources_get_resource_list_for_type(resources,SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,list,count),"reflecting uniform blocks");
                var blocks=new LinkedHashMap<String,Block>();
                for(var resource:SpvcReflectedResource.create(list.get(0),Math.toIntExact(count.get(0)))) {
                    String name=spvc_compiler_get_name(compiler,resource.base_type_id());
                    if(name==null || name.isBlank())name=resource.nameString();
                    if(name==null || name.isBlank())throw invalid("Uniform block has no reflected name");
                    long structure=spvc_compiler_get_type_handle(compiler,resource.base_type_id());
                    var size=stack.mallocPointer(1);
                    check(spvc_compiler_get_declared_struct_size(compiler,structure,size),"reading block size for "+name);
                    int byteSize=alignedBlockSize(size.get(0));
                    var members=new ArrayList<Member>();var out=stack.mallocInt(1);
                    for(int i=0;i<spvc_type_get_num_member_types(structure);i++) {
                        long type=spvc_compiler_get_type_handle(compiler,spvc_type_get_member_type(structure,i));
                        String memberName=spvc_compiler_get_member_name(compiler,resource.base_type_id(),i);
                        int dimensions=spvc_type_get_num_array_dimensions(type),length=0;
                        boolean literal=dimensions==0 || dimensions==1&&spvc_type_array_dimension_is_literal(type,0);
                        if(dimensions==1)length=spvc_type_get_array_dimension(type,0);
                        check(spvc_compiler_type_struct_member_offset(compiler,structure,i,out),"reading member offset");int offset=out.get(0);
                        check(spvc_compiler_get_declared_struct_member_size(compiler,structure,i,size),"reading member size");int memberSize=Math.toIntExact(size.get(0));
                        int arrayStride=0,matrixStride=0;
                        if(dimensions>0) {check(spvc_compiler_type_struct_member_array_stride(compiler,structure,i,out),"reading array stride");arrayStride=out.get(0);}
                        if(spvc_type_get_columns(type)>1) {check(spvc_compiler_type_struct_member_matrix_stride(compiler,structure,i,out),"reading matrix stride");matrixStride=out.get(0);}
                        boolean rowMajor=spvc_compiler_has_member_decoration(compiler,resource.base_type_id(),i,4); // DecorationRowMajor
                        members.add(new Member(memberName,type(type),length,offset,memberSize,arrayStride,matrixStride,rowMajor,dimensions,literal));
                    }
                    long variableType=spvc_compiler_get_type_handle(compiler,resource.type_id());
                    var block=new Block(name,byteSize,members,spvc_type_get_num_array_dimensions(variableType)>0);
                    if(blocks.putIfAbsent(name,block)!=null)throw invalid("Duplicate reflected uniform block: "+name);
                }
                return Collections.unmodifiableMap(blocks);
            } finally {spvc_context_destroy(context);}
        } catch(ArithmeticException overflow) {throw invalid("Reflected uniform layout exceeds supported size");}
        finally {memFree(bytes);}
    }
    public static void validate(ByteBuffer spirv,Map<String,PackUniformBlock> mappedBlocks) { validate(reflect(spirv),mappedBlocks); }
    /** Unmatched mappings may have been optimized out; every matched block must match in full. */
    public static void validate(Map<String,Block> reflected,Map<String,PackUniformBlock> mappedBlocks) {
        Objects.requireNonNull(reflected);Objects.requireNonNull(mappedBlocks);
        for(var mapping:mappedBlocks.entrySet()) {
            var actual=reflected.get(mapping.getKey());if(actual==null)continue;
            var expected=Objects.requireNonNull(mapping.getValue()).layout();
            String prefix="Block "+mapping.getKey()+": ";
            if(actual.arrayed())throw invalid(prefix+"unsupported array of uniform blocks");
            if(actual.members().size()!=expected.slots().size())throw invalid(prefix+"member count does not match the declaration");
            for(int i=0;i<actual.members().size();i++) {
                var member=actual.members().get(i);var slot=expected.slots().get(i);var declaration=slot.member();
                String at=prefix+"member "+declaration.name()+": ";
                if(member.type().startsWith("unsupported") || member.arrayDimensions()>1 || !member.literalArray())throw invalid(at+"unsupported reflected type or array shape: "+member.type());
                if(member.rowMajor())throw invalid(at+"row-major matrices are unsupported");
                if(!declaration.name().equals(member.name()))throw invalid(at+"member name/order differs from shader member "+member.name());
                if(!compatibleType(declaration.type(),member.type()))throw invalid(at+"type "+declaration.type()+" differs from shader type "+member.type());
                if(declaration.arrayLength()!=member.arrayLength())throw invalid(at+"array extent differs from shader");
                if(slot.offset()!=member.offset())throw invalid(at+"offset differs from shader");
                if(slot.arrayStride()!=member.arrayStride())throw invalid(at+"array stride differs from shader");
                if(slot.matrixStride()!=member.matrixStride())throw invalid(at+"matrix stride differs from shader");
                if(slot.byteSize()!=member.byteSize())throw invalid(at+"member byte size differs from shader");
            }
            if(actual.byteSize()!=expected.byteSize())throw invalid(prefix+"block byte size differs from shader");
        }
    }
    private static boolean compatibleType(String expected,String actual) {
        expected=expected.replace("mat2x2","mat2").replace("mat3x3","mat3").replace("mat4x4","mat4");
        // Vulkan represents GLSL bool/bvec uniform storage as uint/uvec with identical 32-bit ABI.
        return expected.equals(actual) || expected.equals("bool")&&actual.equals("uint")
            || expected.startsWith("bvec")&&actual.equals("uvec"+expected.substring(4));
    }
    private static String type(long type) {
        int base=spvc_type_get_basetype(type),bits=spvc_type_get_bit_width(type),rows=spvc_type_get_vector_size(type),columns=spvc_type_get_columns(type);
        String scalar=switch(base) {case SPVC_BASETYPE_FP32 -> "float";case SPVC_BASETYPE_INT32 -> "int";case SPVC_BASETYPE_UINT32 -> "uint";case SPVC_BASETYPE_BOOLEAN -> "bool";default -> null;};
        if(scalar==null || bits!=32&&base!=SPVC_BASETYPE_BOOLEAN || rows<1 || rows>4 || columns<1 || columns>4)
            return "unsupported(base="+base+", bits="+bits+", rows="+rows+", columns="+columns+")";
        if(columns>1)return base!=SPVC_BASETYPE_FP32 || rows<2?"unsupported matrix":"mat"+columns+(columns==rows?"":"x"+rows);
        if(rows==1)return scalar;
        return switch(scalar) {case "float" -> "vec";case "int" -> "ivec";case "uint" -> "uvec";default -> "bvec";}+rows;
    }
    private static int alignedBlockSize(long size) {return Math.toIntExact(Math.multiplyExact(Math.floorDiv(Math.addExact(size,15),16),16));}
    private static void check(int result,String action) {if(result!=SPVC_SUCCESS)throw invalid("SPIR-V reflection failed while "+action+" ("+result+")");}
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException("Uniform ABI: "+message);}
}
