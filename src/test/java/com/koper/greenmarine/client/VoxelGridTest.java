package com.koper.greenmarine.client;

import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelGridTest {
    @Test void voxelWallsOccludeSegmentsWhileOpenPathsAndOutsideSegmentsRemainVisible() {
        var grid=new VoxelGrid(-16,48,-16);
        for(int y=0;y<32;y++) for(int z=0;z<32;z++) grid.set(16,y,z,true);
        assertTrue(grid.occluded(-2,64,0,2,64,0));
        assertFalse(grid.occluded(-5,64,0,-2,64,0));
        assertTrue(grid.occluded(-30,64,0,5,64,0));
        assertFalse(grid.occluded(-30,90,0,30,90,0));
        grid.set(16,16,16,false);
        assertFalse(grid.occluded(-2,64.5,0.5,2,64.5,0.5));
    }
    @Test void std140VoxelBitsPreserveAxisOrderAndSnapshotIsolation() {
        var grid=new VoxelGrid(30_000_000,64,-30_000_000);
        grid.set(1,2,3,true); var snapshot=grid.copy(); grid.set(1,2,3,false);
        assertTrue(snapshot.occupied(1,2,3)); assertFalse(grid.occupied(1,2,3));
        var bytes=ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder()); snapshot.writeCells(bytes);
        int index=(3*32+2)*32+1;
        assertEquals(1<<(index%32),bytes.getInt((index/32)*4));
        assertThrows(IndexOutOfBoundsException.class,()->grid.set(32,0,0,true));
    }
    @Test void scanningHasAnExplicitBlockBudgetAndPublishesOnlyCompleteSnapshots() {
        var scan=new VoxelScanner(); scan.begin(0,0,0);
        assertFalse(scan.step((x,y,z)->x==16,100,Long.MAX_VALUE));
        assertEquals(100,scan.progress()); assertNull(scan.completed());
        while(!scan.step((x,y,z)->x==16,100,Long.MAX_VALUE)) {}
        assertEquals(32768,scan.progress()); assertNotNull(scan.completed());
        assertTrue(scan.completed().occluded(14,16,16,18,16,16));
    }
    @Test void materialsPreserveColorEmissionAndSnapshotIsolation() {
        var grid=new VoxelGrid(0,0,0);
        grid.setSample(1,2,3,true,0x123456,7);
        var snapshot=grid.copy(); grid.setSample(1,2,3,false,0,0);
        assertTrue(snapshot.occupied(1,2,3)); assertEquals(7,snapshot.emission(1,2,3));
        assertEquals(0x123456,snapshot.albedo(1,2,3)); assertFalse(snapshot.sameContent(grid));
        var bytes=ByteBuffer.allocate(VoxelGrid.CELLS*4);snapshot.writeMaterials(bytes);
        int offset=((3*32+2)*32+1)*4;
        assertEquals(0x12,bytes.get(offset)&255);assertEquals(0x34,bytes.get(offset+1)&255);
        assertEquals(0x56,bytes.get(offset+2)&255);assertEquals(119,bytes.get(offset+3)&255);
        assertThrows(IllegalArgumentException.class,()->grid.setSample(0,0,0,true,0,16));
    }
    @Test void materialScansPublishColorAndGeometryTogetherAfterTheLastCell() {
        var scan=new VoxelScanner();scan.begin(0,0,0);
        assertFalse(scan.stepMaterials((x,y,z)->VoxelGrid.sample(x==16,0x40AA20,x==16?15:0),100,Long.MAX_VALUE));
        assertNull(scan.completed());
        while(!scan.stepMaterials((x,y,z)->VoxelGrid.sample(x==16,0x40AA20,x==16?15:0),100,Long.MAX_VALUE)) {}
        var grid=scan.completed();assertTrue(grid.occupied(16,20,10));assertEquals(15,grid.emission(16,20,10));
        assertEquals(0x40AA20,grid.albedo(16,20,10));assertFalse(grid.occupied(15,20,10));
    }
}
