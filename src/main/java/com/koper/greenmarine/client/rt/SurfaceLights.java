package com.koper.greenmarine.client.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

/** Light sources near the camera for traced block-light shadows: emitting blocks (rescanned every
 * second, skipping chunk sections without emitters) and dynamic lights from held items and burning entities. */
final class SurfaceLights {
    record Source(double x,double y,double z,int level,float red,float green,float blue) {}
    // Pixels within 24 blocks get traced light; a level-15 source reaches 15 further.
    private static final int RADIUS=40,RESCAN_TICKS=20;
    private static final List<Source> STATIC=new ArrayList<>();
    private static long scannedTick=Long.MIN_VALUE;
    private static BlockPos scannedAt;
    private static final float[] DATA=new float[com.koper.greenmarine.nativebridge.NativeRayTracer.MAX_SURFACE_LIGHTS*8];
    private SurfaceLights() {}

    static void reset() {STATIC.clear();scannedTick=Long.MIN_VALUE;scannedAt=null;}

    /** Packs the nearest sources relative to the scene origin; returns the count written to data(). */
    static int collect(Minecraft client,int originX,int originY,int originZ) {
        var level=client.level;if(level==null || client.player==null)return 0;
        var camera=client.gameRenderer.mainCamera().position();
        var centre=BlockPos.containing(camera);long tick=level.getGameTime();
        if(scannedAt==null || tick-scannedTick>=RESCAN_TICKS || tick<scannedTick || scannedAt.distManhattan(centre)>8) {
            scanBlocks(client,centre);scannedTick=tick;scannedAt=centre;
        }
        var sources=new ArrayList<>(STATIC);
        for(var entity:level.entitiesForRendering()) {
            if(!(entity instanceof LivingEntity living) || entity.isRemoved() || entity.position().distanceToSqr(camera)>RADIUS*RADIUS)continue;
            float partial=client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            int main=held(living.getMainHandItem()),off=held(living.getOffhandItem());
            if(main>0 || off>0) {
                // In the hand, not the eye: a light at the camera hides every shadow behind its caster.
                boolean right=(living.getMainArm()==net.minecraft.world.entity.HumanoidArm.RIGHT)==(main>=off);
                var eye=living.getEyePosition(partial);var look=living.getViewVector(partial);
                double side=Math.hypot(look.x,look.z)<1e-4?0:1/Math.hypot(look.x,look.z);
                double rx=-look.z*side*(right?1:-1),rz=look.x*side*(right?1:-1);
                sources.add(warm(eye.x+look.x*.35+rx*.35,eye.y+look.y*.35-.45,eye.z+look.z*.35+rz*.35,Math.max(main,off)));
            }
            if(entity.isOnFire()) {var at=living.position();sources.add(warm(at.x,at.y+living.getBbHeight()*.5,at.z,15));}
        }
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

    private static void scanBlocks(Minecraft client,BlockPos centre) {
        STATIC.clear();var level=client.level;var cursor=new BlockPos.MutableBlockPos();
        int minSection=SectionPos.blockToSectionCoord(centre.getX()-RADIUS),maxSection=SectionPos.blockToSectionCoord(centre.getX()+RADIUS);
        int minZSection=SectionPos.blockToSectionCoord(centre.getZ()-RADIUS),maxZSection=SectionPos.blockToSectionCoord(centre.getZ()+RADIUS);
        for(int cx=minSection;cx<=maxSection;cx++)for(int cz=minZSection;cz<=maxZSection;cz++) {
            var chunk=level.getChunkSource().getChunk(cx,cz,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);if(chunk==null)continue;
            var sections=chunk.getSections();
            for(int index=0;index<sections.length;index++) {
                var section=sections[index];int baseY=SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(index));
                if(section==null || section.hasOnlyAir() || Math.abs(baseY+8-centre.getY())>RADIUS+8
                    || !section.maybeHas(state->state.getLightEmission()>0))continue;
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                    BlockState state=section.getBlockState(x,y,z);int light=state.getLightEmission();
                    if(light<=0)continue;
                    int bx=(cx<<4)+x,by=baseY+y,bz=(cz<<4)+z;
                    if(cursor.set(bx,by,bz).distSqr(centre)>RADIUS*RADIUS)continue;
                    STATIC.add(colour(state,bx+.5,by+.5,bz+.5,light));
                }
            }
        }
    }
    private static int held(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem block?block.getBlock().defaultBlockState().getLightEmission():0;
    }
    private static Source colour(BlockState state,double x,double y,double z,int light) {
        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(id.contains("soul"))return new Source(x,y,z,light,.35f,.75f,1.25f);
        if(id.contains("redstone"))return new Source(x,y,z,light,1.2f,.25f,.2f);
        return warm(x,y,z,light);
    }
    private static Source warm(double x,double y,double z,int light) {return new Source(x,y,z,light,1.25f,.74f,.32f);}
}
