package com.koper.greenmarine.client.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

/** Nearby emitting blocks, refreshed on availability/edits with bounded section extraction,
 * plus dynamic lights from held/dropped items and burning entities. */
final class SurfaceLights {
    record Source(double x,double y,double z,int level,float red,float green,float blue) {}
    // Pixels within 24 blocks get traced light; a level-15 source reaches 15 further.
    private static final int RADIUS=40,SECTIONS_PER_REFRESH=4;
    private static final SectionLightCache<Source> STATIC=new SectionLightCache<>();
    private static Object world;
    private static final float[] DATA=new float[com.koper.greenmarine.nativebridge.NativeRayTracer.MAX_SURFACE_LIGHTS*8];
    private SurfaceLights() {}

    static void reset() {STATIC.reset();world=null;}
    static void invalidateBlock(BlockPos pos) {
        invalidateSection(SectionPos.blockToSectionCoord(pos.getX()),SectionPos.blockToSectionCoord(pos.getY()),SectionPos.blockToSectionCoord(pos.getZ()));
    }
    static void invalidateSection(int x,int y,int z) {STATIC.invalidateSection(x,y,z);}
    static void invalidateColumn(int x,int z) {STATIC.invalidateColumn(x,z);}

    /** Packs the nearest sources relative to the scene origin; returns the count written to data(). */
    static int collect(Minecraft client,int originX,int originY,int originZ) {
        var level=client.level;if(level==null || client.player==null)return 0;
        var camera=client.gameRenderer.mainCamera().position();
        var centre=BlockPos.containing(camera);long tick=level.getGameTime();
        if(world!=level) {reset();world=level;}
        // Availability palettes are polled once per tick while the camera block and edits stay stable.
        // Movement/notifications refresh immediately; dynamic entities remain frame-interpolated.
        var nearby=STATIC.collect(tick,centre.getX(),centre.getY(),centre.getZ(),
            ()->observeSections(client,centre),source->{
                double dx=source.x()-camera.x,dy=source.y()-camera.y,dz=source.z()-camera.z;
                return dx*dx+dy*dy+dz*dz<=RADIUS*RADIUS;
            },SECTIONS_PER_REFRESH);
        var sources=new ArrayList<>(nearby);
        sources.addAll(DynamicLights.collect(client));
        sources.sort(java.util.Comparator.comparingDouble(s->(s.x()-camera.x)*(s.x()-camera.x)+(s.y()-camera.y)*(s.y()-camera.y)+(s.z()-camera.z)*(s.z()-camera.z)));
        int count=Math.min(sources.size(),com.koper.greenmarine.nativebridge.NativeRayTracer.MAX_SURFACE_LIGHTS);
        for(int i=0;i<count;i++) {
            var s=sources.get(i);int o=i*8;
            DATA[o]=(float)(s.x()-originX);DATA[o+1]=(float)(s.y()-originY);DATA[o+2]=(float)(s.z()-originZ);DATA[o+3]=s.level();
            DATA[o+4]=s.red();DATA[o+5]=s.green();DATA[o+6]=s.blue();DATA[o+7]=0;
        }
        return count;
    }
    static float[] data() {return DATA;}

    /** Shared interpolated source positions for terrain tracing and entity receiver light. */
    static List<Source> dynamicSources(Minecraft client) {
        var level=client.level;
        if(level==null)return List.of();
        var camera=client.gameRenderer.mainCamera().position();
        var sources=new ArrayList<Source>();
        for(var entity:level.entitiesForRendering()) {
            if(entity.isRemoved() || entity.position().distanceToSqr(camera)>RADIUS*RADIUS)continue;
            float partial=client.getDeltaTracker().getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            var at=entity.getPosition(partial);
            if(entity.isOnFire()) sources.add(warm(at.x,at.y+entity.getBbHeight()*.5,at.z,15));
            if(entity instanceof ItemEntity dropped) {
                var light=itemSource(dropped.getItem(),at.x,at.y+entity.getBbHeight()*.5,at.z);
                if(light!=null)sources.add(light);
            }
            if(!(entity instanceof LivingEntity living))continue;
            int main=itemEmission(living.getMainHandItem()),off=itemEmission(living.getOffhandItem());
            if(main>0 || off>0) {
                // In the hand, not the eye: a light at the camera hides every shadow behind its caster.
                boolean right=(living.getMainArm()==net.minecraft.world.entity.HumanoidArm.RIGHT)==(main>=off);
                var eye=living.getEyePosition(partial);var look=living.getViewVector(partial);
                var offset=HeldLightPosition.offset(look.x,look.y,look.z,living.getViewYRot(partial),right,living.getBbWidth());
                var hand=eye.add(offset.x(),offset.y(),offset.z());
                var obstruction=level.clip(new net.minecraft.world.level.ClipContext(eye,hand,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,living));
                if(obstruction.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    var segment=obstruction.getLocation().subtract(eye);double distance=segment.length();
                    hand=distance<=.03?eye:eye.add(segment.scale((distance-.03)/distance));
                }
                sources.add(itemSource(main>=off?living.getMainHandItem():living.getOffhandItem(),
                    hand.x,hand.y,hand.z));
            }
        }
        return sources;
    }

    private static List<SectionLightCache.Section<Source>> observeSections(Minecraft client,BlockPos centre) {
        var level=client.level;
        var nearby=new ArrayList<SectionLightCache.Section<Source>>();
        int minSection=SectionPos.blockToSectionCoord(centre.getX()-RADIUS),maxSection=SectionPos.blockToSectionCoord(centre.getX()+RADIUS);
        int minZSection=SectionPos.blockToSectionCoord(centre.getZ()-RADIUS),maxZSection=SectionPos.blockToSectionCoord(centre.getZ()+RADIUS);
        for(int cx=minSection;cx<=maxSection;cx++)for(int cz=minZSection;cz<=maxZSection;cz++) {
            var chunk=level.getChunkSource().getChunk(cx,cz,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
            if(chunk==null)continue;
            var sections=chunk.getSections();
            for(int index=0;index<sections.length;index++) {
                var section=sections[index];int sy=level.getSectionYFromSectionIndex(index);
                int baseY=SectionPos.sectionToBlockCoord(sy);
                if(section==null || Math.abs(baseY+8-centre.getY())>RADIUS+8)continue;
                var key=new SectionLightCache.Key(cx,sy,cz);
                boolean emissive=!section.hasOnlyAir() && section.maybeHas(state->state.getLightEmission()>0);
                nearby.add(new SectionLightCache.Section<>(key,section,emissive,()->scanSection(section,key)));
            }
        }
        nearby.sort(java.util.Comparator.comparingDouble(section->{
            var key=section.key();
            double dx=key.x()*16.0+8-centre.getX(),dy=key.y()*16.0+8-centre.getY(),dz=key.z()*16.0+8-centre.getZ();
            return dx*dx+dy*dy+dz*dz;
        }));
        return nearby;
    }
    private static List<Source> scanSection(net.minecraft.world.level.chunk.LevelChunkSection section,SectionLightCache.Key key) {
        var sources=new ArrayList<Source>();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            BlockState state=section.getBlockState(x,y,z);int light=state.getLightEmission();
            if(light>0)sources.add(colour(state,key.x()*16+x+.5,key.y()*16+y+.5,key.z()*16+z+.5,light));
        }
        return sources;
    }
    private static int itemEmission(ItemStack stack) {
        if(stack.isEmpty())return 0;
        var item=stack.getItem();
        if(item==Items.LAVA_BUCKET)return 15;
        if(item==Items.BLAZE_ROD || item==Items.BLAZE_POWDER)return 10;
        if(item==Items.GLOWSTONE_DUST || item==Items.GLOW_BERRIES || item==Items.GLOW_INK_SAC || item==Items.MAGMA_CREAM)return 8;
        if(item instanceof BlockItem block)return block.getBlock().defaultBlockState().getLightEmission();
        return 0;
    }
    static Source itemSource(ItemStack stack,double x,double y,double z) {
        int light=itemEmission(stack);if(light<=0)return null;
        return stack.getItem() instanceof BlockItem block?colour(block.getBlock().defaultBlockState(),x,y,z,light):warm(x,y,z,light);
    }
    private static Source colour(BlockState state,double x,double y,double z,int light) {
        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(id.contains("soul"))return new Source(x,y,z,light,.35f,.75f,1.25f);
        if(id.contains("redstone"))return new Source(x,y,z,light,1.2f,.25f,.2f);
        return warm(x,y,z,light);
    }
    private static Source warm(double x,double y,double z,int light) {return new Source(x,y,z,light,1.25f,.74f,.32f);}
}
