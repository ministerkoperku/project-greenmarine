package com.koper.greenmarine.api.external;

import java.nio.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalRenderersTest {
    private static ByteBuffer triangles(int count,ByteOrder order) {
        var data=ByteBuffer.allocate(count*ExternalRenderers.TRIANGLE_BYTES).order(order);
        for(int i=0;i<count*ExternalRenderers.TRIANGLE_FLOATS;i++)data.putFloat(i*4,i*.5f);
        return data;
    }
    @Test void meshesCopyAnyByteOrderAndRejectMalformedInput() {
        var big=triangles(2,ByteOrder.BIG_ENDIAN);
        var mesh=ExternalRenderers.createMesh(big);
        assertEquals(2,mesh.triangles());
        big.putFloat(0,99);
        assertFalse(mesh.closed());
        assertThrows(IllegalArgumentException.class,()->ExternalRenderers.createMesh(ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class,()->ExternalRenderers.createMesh(ByteBuffer.allocate(95)));
        var nan=triangles(1,ByteOrder.nativeOrder());nan.putFloat(40,Float.NaN);
        assertThrows(IllegalArgumentException.class,()->ExternalRenderers.createMesh(nan));
        assertThrows(IllegalArgumentException.class,()->ExternalRenderers.createMesh(
            ByteBuffer.allocate((ExternalRenderers.MAX_MESH_TRIANGLES+1)*ExternalRenderers.TRIANGLE_BYTES)));
        mesh.close();mesh.close();
        assertTrue(mesh.closed());
        assertFalse(mesh.prepare(),"closed meshes never upload");
    }
    @Test void transformsAreRowMajorAffineRelativeToTheIntegerSceneOrigin() {
        var basis=new Matrix3f().rotateY((float)Math.PI/2).scale(2);
        float[] out=new float[24];
        ExternalRenderers.writeTransform(out,12,1_000_000.25,64.5,-3.75,basis,999_936,0,-64);
        for(int row=0;row<3;row++)for(int column=0;column<3;column++)
            assertEquals(basis.get(column,row),out[12+row*4+column],1e-6f);
        assertEquals(64.25f,out[15]);assertEquals(64.5f,out[19]);assertEquals(60.25f,out[23]);
        for(int i=0;i<12;i++)assertEquals(0,out[i]);
    }
    @Test void contributorsRunInOrderAndFailingOnesAreIsolatedUntilReRegistered() {
        var calls=new AtomicInteger();
        var mesh=ExternalRenderers.createMesh(triangles(1,ByteOrder.nativeOrder()));
        try(var good=ExternalRenderers.registerRayGeometry("test:good",(frame,out)->{
                calls.incrementAndGet();
                assertEquals(ExternalRenderers.MAX_INSTANCES,out.remaining());
                // No native module in unit tests: unprepared meshes are skipped, never queued.
                assertTrue(out.add(mesh,1,2,3));
                assertTrue(out.add(mesh,Double.NaN,0,0));
                assertTrue(out.add(mesh,0,0,0,new Matrix3f().zero()));
            });
            var bad=ExternalRenderers.registerRayGeometry("test:bad",(frame,out)->{throw new IllegalStateException("boom");})) {
            var frame=new ExternalRenderers.RayFrame(0,0,0,0,1);
            var first=ExternalRenderers.collectRayGeometry(frame,0,0,0);
            var second=ExternalRenderers.collectRayGeometry(frame,0,0,0);
            assertEquals(0,first.count());assertEquals(0,second.count());
            assertEquals(2,calls.get(),"a failing contributor never blocks the others");
            assertThrows(IllegalArgumentException.class,()->ExternalRenderers.registerRayGeometry("test:good",(f,o)->{}));
        }
        assertThrows(IllegalArgumentException.class,()->ExternalRenderers.registerRayGeometry("unnamespaced",(f,o)->{}));
        assertThrows(IllegalArgumentException.class,()->ExternalRenderers.registerMaterials("Bad:Id",t->{}));
        try(var again=ExternalRenderers.registerRayGeometry("test:good",(f,o)->{})) {
            assertNotNull(again,"closing a registration frees its id");
        }
    }
    @Test void materialPreparationRunsBeforeDrawingAndDisablesOnlyFailedContributor() {
        var calls=new java.util.ArrayList<String>();
        try(var bad=ExternalRenderers.registerMaterials("test:prepare_bad",()->{calls.add("bad");throw new IllegalStateException("prepare failed");},t->calls.add("bad_draw"));
            var good=ExternalRenderers.registerMaterials("test:prepare_good",()->calls.add("prepare"),t->calls.add("draw"))) {
            ExternalRenderers.prepareMaterials();ExternalRenderers.drawMaterials(123,4,4,126);
            ExternalRenderers.prepareMaterials();ExternalRenderers.drawMaterials(123,4,4,126);
            assertEquals(java.util.List.of("bad","prepare","draw","prepare","draw"),calls);
        }
        calls.clear();ExternalRenderers.prepareMaterials();assertTrue(calls.isEmpty());
    }
    @Test void materialContributorsReceiveTheCapturedCameraAndFailuresAreIsolated() {
        var seen=new ExternalRenderers.MaterialTarget[1];
        ExternalRenderers.captureCamera(new org.joml.Matrix4f().perspective(1,1,.05f,100),new org.joml.Matrix4f().rotateY(1),10.5,64,-3,true);
        try(var bad=ExternalRenderers.registerMaterials("test:bad_materials",t->{throw new IllegalStateException("boom");});
            var good=ExternalRenderers.registerMaterials("test:materials",t->seen[0]=t)) {
            assertTrue(ExternalRenderers.hasMaterialContributors());
            ExternalRenderers.drawMaterials(0,64,32,126);
            assertNull(seen[0],"no command buffer means no external draws");
            ExternalRenderers.drawMaterials(0x1234,64,32,126);
            assertEquals(0x1234,seen[0].vkCommandBuffer());assertEquals(64,seen[0].width());assertEquals(126,seen[0].depthFormat());
            assertEquals(10.5,seen[0].cameraX());assertTrue(seen[0].zZeroToOne());
            assertEquals(new org.joml.Matrix4f().rotateY(1),seen[0].viewRotation());
        }
        assertFalse(ExternalRenderers.hasMaterialContributors());
    }
}
