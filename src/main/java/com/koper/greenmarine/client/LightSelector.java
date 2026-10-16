package com.koper.greenmarine.client;

import com.koper.greenmarine.api.Light;
import java.util.Arrays;
import java.util.List;

/** Bounded insertion selection avoids sorting or allocating the full source list. */
public final class LightSelector {
    private final Light[] selected = new Light[WorldLightFrame.MAX_LIGHTS];
    private final double[] scores = new double[WorldLightFrame.MAX_LIGHTS];
    private int size;
    private final double[] planes=new double[16];
    public void select(List<Light> sources,double cameraX,double cameraY,double cameraZ,int limit) {
        select(sources,cameraX,cameraY,cameraZ,limit,null);
    }
    public void select(List<Light> sources,double cameraX,double cameraY,double cameraZ,int limit,org.joml.Matrix4fc clip) {
        if (limit < 0 || limit > selected.length) throw new IllegalArgumentException("Invalid GPU light limit");
        Arrays.fill(selected,null); size=0;
        if (limit == 0) return;
        if(clip!=null) {
            plane(0,clip.m03()+clip.m00(),clip.m13()+clip.m10(),clip.m23()+clip.m20(),clip.m33()+clip.m30());
            plane(4,clip.m03()-clip.m00(),clip.m13()-clip.m10(),clip.m23()-clip.m20(),clip.m33()-clip.m30());
            plane(8,clip.m03()+clip.m01(),clip.m13()+clip.m11(),clip.m23()+clip.m21(),clip.m33()+clip.m31());
            plane(12,clip.m03()-clip.m01(),clip.m13()-clip.m11(),clip.m23()-clip.m21(),clip.m33()-clip.m31());
        }
        for (var light : sources) {
            double x=light.x()-cameraX,y=light.y()-cameraY,z=light.z()-cameraZ;
            if (!Float.isFinite((float)x) || !Float.isFinite((float)y) || !Float.isFinite((float)z)
                || light.intensity()==0 || Math.max(light.red(),Math.max(light.green(),light.blue()))==0) continue;
            if(clip!=null && !intersects(x,y,z,light.radius())) continue;
            double radius=light.radius();
            double score=(x*x+y*y+z*z)/(radius*radius*Math.max(0.001,Math.min(16,light.intensity())));
            int at=size;
            while (at>0 && scores[at-1]>score) at--;
            if (at>=limit) continue;
            int last=Math.min(size,limit-1);
            for (int i=last;i>at;i--) { selected[i]=selected[i-1]; scores[i]=scores[i-1]; }
            selected[at]=light; scores[at]=score; size=Math.min(limit,size+1);
        }
    }
    private void plane(int at,double x,double y,double z,double w) {
        double length=Math.sqrt(x*x+y*y+z*z);
        if(length==0) { for(int i=0;i<4;i++) planes[at+i]=0; return; }
        planes[at]=x/length; planes[at+1]=y/length; planes[at+2]=z/length; planes[at+3]=w/length;
    }
    private boolean intersects(double x,double y,double z,double radius) {
        for(int at=0;at<planes.length;at+=4) if(planes[at]*x+planes[at+1]*y+planes[at+2]*z+planes[at+3]<-radius) return false;
        return true;
    }
    public int size() { return size; }
    public Light get(int index) { if (index<0 || index>=size) throw new IndexOutOfBoundsException(index); return selected[index]; }
}
