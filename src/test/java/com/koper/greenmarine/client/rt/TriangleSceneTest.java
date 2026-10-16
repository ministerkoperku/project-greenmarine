package com.koper.greenmarine.client.rt;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;
class TriangleSceneTest {
    @org.junit.jupiter.api.Test void exactAssemblyTransfersAnImmutableBufferAndRejectsFurtherBuilderWrites() {
        var source=new TriangleScene.Builder(0,0,0,1);
        source.triangle(new float[]{0,0,0,1,0,0,0,1,0},new float[]{0,0,1,0,0,1},0xabcdef,7,true);
        var mesh=source.finish();
        var exact=new TriangleScene.Builder(16,32,48,2,1);exact.append(mesh,4,8,12);
        var result=exact.finishOwned();
        assertTrue(result.data().isReadOnly());assertEquals(96,result.data().capacity());
        assertEquals(4,result.data().getFloat(0));assertEquals(8,result.data().getFloat(4));
        var legacy=new TriangleScene.Builder(16,32,48,2);legacy.append(mesh,4,8,12);
        assertTrue(result.sameContent(legacy.finish()));
        assertThrows(IllegalStateException.class,()->exact.append(mesh,0,0,0));
        assertThrows(IllegalStateException.class,exact::finishOwned);
    }
    @Test void sceneHashIdentifiesGeometryWithoutRetainingThePackedBuffer() {
        var builder=new TriangleScene.Builder(16,32,48,1);
        builder.triangle(new float[]{0,0,0,1,0,0,0,1,0},new float[6],0xFFFFFF,0,false);
        var scene=builder.finish();
        var copy=new TriangleScene.Builder(16,32,48,1);copy.append(scene,0,0,0);
        assertEquals(scene.hash(),copy.finish().hash());
        var changed=new TriangleScene.Builder(16,32,48,1);changed.append(scene,1,0,0);
        assertNotEquals(scene.hash(),changed.finish().hash());
    }
    @Test void bulkAppendPreservesMaterialBytesAndRejectsOverflow() {
        var source=new TriangleScene.Builder(0,0,0,1);
        source.triangle(new float[]{.25f,0,0,1,0,0,0,1,0},new float[]{.1f,.2f,.3f,.4f,.5f,.6f},0x80FF40,15,true);
        var mesh=source.finish();var target=new TriangleScene.Builder(32,48,64,1);target.append(mesh,16,-16,32);
        var data=target.finish().data();assertEquals(16.25f,data.getFloat(0));assertEquals(-16,data.getFloat(4));assertEquals(32,data.getFloat(8));
        for(int offset=48;offset<TriangleScene.TRIANGLE_BYTES;offset+=4)assertEquals(mesh.data().getFloat(offset),data.getFloat(offset));
        assertThrows(IllegalArgumentException.class,()->target.append(mesh,0,0,0));
    }
    @Test void packsActualTrianglePositionsAndUvsIntoNativeStd430Abi() {
        var builder = new TriangleScene.Builder(30_000_000, 64, -30_000_000, 2);
        builder.triangle(new float[]{.25f,0,0,1,0,0,0,1,0}, new float[]{.1f,.2f,.3f,.4f,.5f,.6f}, 0x80FF40, 15, true);
        var scene = builder.finish(); var data = scene.data().order(ByteOrder.nativeOrder());
        assertEquals(1, scene.triangles()); assertEquals(96, data.remaining());
        assertEquals(.25f, data.getFloat(0)); assertEquals(1, data.getFloat(16));
        assertEquals(.1f, data.getFloat(48)); assertEquals(.6f, data.getFloat(68));
        assertEquals(1, data.getFloat(72)); assertEquals(15, data.getFloat(76));
        assertEquals(128/255f, data.getFloat(80));
        assertThrows(ReadOnlyBufferException.class, () -> scene.data().putFloat(0, 5));
        assertEquals(1.25f, scene.cameraX(30_000_001.25));
    }
    @Test void enforcesGeometryBudgetAndSkipsDegenerateTriangles() {
        var builder = new TriangleScene.Builder(0,0,0,1);
        builder.triangle(new float[9], new float[6], 0xFFFFFF, 0, false);
        assertEquals(0, builder.finish().triangles());
        builder.triangle(new float[]{0,0,0,1,0,0,0,1,0}, new float[6], 0xFFFFFF, 0, false);
        assertThrows(IllegalArgumentException.class, () -> builder.triangle(new float[]{0,0,0,1,0,0,0,1,0}, new float[6], 0xFFFFFF, 0, false));
    }
}
