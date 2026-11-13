package com.koper.greenmarine.compat.integration;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MechanicsMicroGeometryTest {
    public record Piece(int x,int y,int z,int size,BlockState material) {}
    public record Grid(List<Piece> pieces) {}
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void quarterPartsRetainRealGlassAndEmitterMaterials() throws Exception {
        var grid=new Grid(List.of(new Piece(0,0,0,1,Blocks.GLASS.defaultBlockState()),new Piece(1,2,3,1,Blocks.GLOWSTONE.defaultBlockState())));
        var parts=MechanicsMicroGeometry.decode(grid);
        assertEquals(2,parts.size());assertSame(Blocks.GLASS,parts.getFirst().material().getBlock());
        var glow=parts.get(1);assertEquals(.25f,glow.x());assertEquals(.5f,glow.y());assertEquals(.75f,glow.z());
        assertEquals(.25f,glow.scale());assertEquals(15,glow.material().getLightEmission());
        assertEquals(63,glow.faceMask());
    }
    @Test void sharedMicroFacesAreOmittedWithoutDiscardingExposedFaces() throws Exception {
        var stone=Blocks.STONE.defaultBlockState();
        var parts=MechanicsMicroGeometry.decode(new Grid(List.of(new Piece(0,0,0,1,stone),new Piece(1,0,0,1,stone))));
        assertEquals(0,parts.getFirst().faceMask()&1<<Direction.EAST.ordinal());
        assertEquals(0,parts.get(1).faceMask()&1<<Direction.WEST.ordinal());
        assertNotEquals(0,parts.getFirst().faceMask()&1<<Direction.WEST.ordinal());
        assertNotEquals(0,parts.get(1).faceMask()&1<<Direction.EAST.ordinal());
    }
    @Test void missingAttachmentProducesNoPhantomFullBlock() throws Exception {
        assertTrue(MechanicsMicroGeometry.decode(null).isEmpty());
        assertTrue(MechanicsMicroGeometry.decode(new Grid(List.of())).isEmpty());
    }
}
