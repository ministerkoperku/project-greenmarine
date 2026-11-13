package com.koper.greenmarine.client.rt;

/** Separate VM: reproduce direct-memory pressure without a heap collection rescuing Cleaner buffers. */
public final class ActorMemorySoak {
    public static void main(String[] args) {
        long checksum=0;
        if(args.length>0 && args[0].equals("scene")) {
            for(int frame=0;frame<50_000;frame++) {
                var builder=new TriangleScene.Builder(0,0,0,8);
                builder.triangle(new float[]{0,0,0,1,0,0,0,1,0},new float[]{0,0,1,0,0,1},0xffffff,0,false);
                checksum+=builder.finish().data().remaining();
            }
            System.out.println("completed 50000 scene frames: "+checksum);return;
        }
        for(int frame=0;frame<50_000;frame++) {
            var geometry=new PlayerReflectionGeometry.Builder(PlayerReflectionGeometry.EXTENDED_MAX_BOXES);
            checksum+=geometry.data().remaining();
            checksum+=PlayerReflection.sized(new PlayerReflectionGeometry.Builder().data(),true).remaining();
            checksum+=PlayerReflectionKAvatar.captureModel(null,null,new org.joml.Matrix4f()).geometry().data().remaining();
            var snapshot=PlayerReflection.snapshot(null,0,0,0,true);checksum+=snapshot.data().remaining();
        }
        System.out.println("completed 50000 actor frames: "+checksum);
    }
}
