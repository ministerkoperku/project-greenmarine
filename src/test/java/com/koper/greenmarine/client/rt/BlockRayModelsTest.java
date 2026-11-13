package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.api.render.BlockRayModels;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlockRayModelsTest {
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void customGeometryKeepsPieceScaleAndMaterialInsteadOfTheContainerModel() {
        var container=Blocks.STONE.defaultBlockState();var material=Blocks.GLOWSTONE.defaultBlockState();
        var part=new BlockRayModels.Part(material,.25f,.5f,.75f,.25f,63);
        try(var registration=BlockRayModels.register("test:micro",s->s==container,context->List.of(part))) {
            var parts=BlockRayModels.parts(null,BlockPos.ZERO,container,null);
            assertEquals(1,parts.size());assertSame(material,parts.getFirst().material());
            assertEquals(15,parts.getFirst().material().getLightEmission());
            assertEquals(.25f,parts.getFirst().scale());assertEquals(.75f,parts.getFirst().z());
            assertNull(BlockRayModels.parts(null,BlockPos.ZERO,Blocks.DIRT.defaultBlockState(),null));
        }
        assertNull(BlockRayModels.parts(null,BlockPos.ZERO,container,null));
    }
    @Test void emptyDynamicGeometryIsHandledWithoutPublishingAFullCube() {
        var container=Blocks.STONE.defaultBlockState();
        try(var registration=BlockRayModels.register("test:empty_micro",s->s==container,context->List.of())) {
            assertTrue(BlockRayModels.parts(null,BlockPos.ZERO,container,null).isEmpty());
        }
    }
    @Test void matchingProviderCanRequestOrdinaryBakedGeometry() {
        var container=Blocks.STONE.defaultBlockState();
        try(var registration=BlockRayModels.register("test:fallback",s->s==container,context->null)) {
            assertNull(BlockRayModels.parts(null,BlockPos.ZERO,container,null));
        }
    }
}
