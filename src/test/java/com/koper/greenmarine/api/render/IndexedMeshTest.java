package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexedMeshTest {
    @org.junit.jupiter.api.BeforeAll static void thread() {RenderTestHost.initialize();}
    private static ByteBuffer indices(short... values) {
        var out=ByteBuffer.allocate(values.length*2).order(ByteOrder.nativeOrder());
        for(short value:values)out.putShort(value);return out.flip();
    }
    @Test void indexedQuadUploadsFourVerticesAndIssuesOneInstancedCommand() {
        var gpu=new Host();var session=new RenderSession(gpu.device);
        var vertices=ByteBuffer.allocate(4*12);var indices=indices((short)0,(short)1,(short)2,(short)2,(short)3,(short)0);
        var mesh=session.indexedMesh("quad",DefaultVertexFormat.POSITION,4,vertices,IndexType.SHORT,indices);
        assertEquals(0,vertices.position());assertEquals(0,indices.position());
        assertEquals(2,gpu.allocations);assertEquals(60,gpu.uploadedBytes);
        var calls=new ArrayList<String>();var draws=new ArrayList<List<Integer>>();
        var pass=proxy(RenderPass.class,(name,args)->{
            calls.add(name);
            if(name.equals("drawIndexed"))draws.add(Arrays.stream(args).map(v->(Integer)v).toList());
            return null;
        });
        mesh.draw(pass,64,0);
        assertEquals(List.of("setVertexBuffer","setIndexBuffer","drawIndexed"),calls);
        assertEquals(List.of(6,64,0,0,0),draws.getFirst());
        mesh.close();session.close();assertEquals(2,gpu.closes);
        assertThrows(IllegalStateException.class,()->mesh.draw(pass,1,0));
    }
    @Test void indexValidationUsesUnsignedNativeBytesAndPreservesSlices() {
        var shortData=indices((short)32768,(short)2,(short)1).asReadOnlyBuffer();
        assertEquals(3,Mesh.validateIndices(40000,IndexType.SHORT,shortData));
        assertEquals(0,shortData.position());
        var ints=ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
        ints.position(4);ints.putInt(65536).putInt(2).putInt(1).flip().position(4);
        assertEquals(3,Mesh.validateIndices(70000,IndexType.INT,ints));
        assertEquals(4,ints.position());assertEquals(16,ints.limit());
    }
    @Test void invalidIndicesFailBeforeAnyGpuAllocation() {
        var gpu=new Host();var session=new RenderSession(gpu.device);
        for(var index:List.of(indices((short)0,(short)1,(short)4),indices((short)0,(short)1,(short)-1),ByteBuffer.allocate(5)))
            assertThrows(IllegalArgumentException.class,()->session.indexedMesh("bad",DefaultVertexFormat.POSITION,4,ByteBuffer.allocate(48),IndexType.SHORT,index));
        var bad=ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(-1).flip();
        assertThrows(IllegalArgumentException.class,()->session.indexedMesh("bad32",DefaultVertexFormat.POSITION,4,ByteBuffer.allocate(48),IndexType.INT,bad));
        assertEquals(0,gpu.allocations);session.close();
    }
    @Test void indexUploadFailureReleasesTheVertexBuffer() {
        var gpu=new Host();gpu.failSecond=true;var session=new RenderSession(gpu.device);
        assertThrows(IllegalStateException.class,()->session.indexedMesh("failed",DefaultVertexFormat.POSITION,4,ByteBuffer.allocate(48),IndexType.SHORT,indices((short)0,(short)1,(short)2)));
        assertEquals(1,gpu.closes);session.close();assertEquals(1,gpu.closes);
    }
    @Test void validatesInstanceCountsBeforeBindingAnything() {
        var gpu=new Host();var session=new RenderSession(gpu.device);
        var mesh=session.mesh("triangle",DefaultVertexFormat.POSITION,3,ByteBuffer.allocate(36));
        var pass=proxy(RenderPass.class,(name,args)->{throw new AssertionError("Unexpected GPU call: "+name);});
        assertThrows(IllegalArgumentException.class,()->mesh.draw(pass,0,0));
        assertThrows(IllegalArgumentException.class,()->mesh.draw(pass,1,-1));
        assertThrows(IllegalArgumentException.class,()->mesh.draw(pass,Integer.MAX_VALUE,1));
        session.close();
    }
    interface Call {Object invoke(String name,Object[] args);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->call.invoke(m.getName(),a));
    }
    private static class Host {
        int allocations,closes,uploadedBytes;boolean failSecond;
        final GpuDevice device=proxy(GpuDevice.class,(name,args)->{
            if(!name.equals("createBuffer"))throw new UnsupportedOperationException(name);
            if(++allocations==2 && failSecond)throw new IllegalStateException("Index allocation refused");
            var data=(ByteBuffer)args[2];int size=data.remaining(),usage=(int)args[1];uploadedBytes+=size;boolean[] closed={false};
            GpuBuffer[] self={null};
            self[0]=proxy(GpuBuffer.class,(method,values)->switch(method){
                case "size"->(long)size;case "usage"->usage;case "isClosed"->closed[0];
                case "slice"->new com.mojang.renderpearl.api.buffers.GpuBufferSlice(self[0],0,size);
                case "close"->{if(!closed[0])closes++;closed[0]=true;yield null;}
                default->throw new UnsupportedOperationException(method);
            });
            return self[0];
        });
    }
}
