package com.koper.greenmarine.client;

import java.nio.ByteBuffer;

public final class VoxelGrid {
    public static final int SIZE=32, CELLS=SIZE*SIZE*SIZE, WORDS=CELLS/32, MAX_STEPS=96;
    private final int x,y,z;
    private final int[] words=new int[WORDS];
    private final int[] materials=new int[CELLS];
    public VoxelGrid(int x,int y,int z) { this.x=x; this.y=y; this.z=z; }
    public int x() { return x; } public int y() { return y; } public int z() { return z; }
    public void set(int x,int y,int z,boolean solid) {
        if(x<0 || y<0 || z<0 || x>=SIZE || y>=SIZE || z>=SIZE) throw new IndexOutOfBoundsException();
        int index=(z*SIZE+y)*SIZE+x,mask=1<<(index%32);
        if(solid) words[index/32]|=mask; else words[index/32]&=~mask;
    }
    public boolean occupied(int x,int y,int z) {
        if(x<0 || y<0 || z<0 || x>=SIZE || y>=SIZE || z>=SIZE) return false;
        int index=(z*SIZE+y)*SIZE+x;
        return (words[index/32]&(1<<(index%32)))!=0;
    }
    public static int sample(boolean solid,int albedo,int emission) {
        if(albedo<0 || albedo>0xFFFFFF || emission<0 || emission>15) throw new IllegalArgumentException("Invalid voxel material");
        return (solid?0x80000000:0)|(emission<<24)|albedo;
    }
    public void setSample(int x,int y,int z,boolean solid,int albedo,int emission) {
        sample(solid,albedo,emission); set(x,y,z,solid);
        materials[(z*SIZE+y)*SIZE+x]=(albedo<<8)|(emission*17);
    }
    public int albedo(int x,int y,int z) { return material(x,y,z)>>>8; }
    public int emission(int x,int y,int z) { return (material(x,y,z)&255)/17; }
    private int material(int x,int y,int z) {
        return x<0 || y<0 || z<0 || x>=SIZE || y>=SIZE || z>=SIZE?0:materials[(z*SIZE+y)*SIZE+x];
    }
    public void writeMaterials(ByteBuffer data) {
        for(int i=0;i<materials.length;i++) {
            int value=materials[i],offset=i*4;
            data.put(offset,(byte)(value>>>24)).put(offset+1,(byte)(value>>>16)).put(offset+2,(byte)(value>>>8)).put(offset+3,(byte)value);
        }
    }
    public VoxelGrid copy() {
        var grid=new VoxelGrid(x,y,z); System.arraycopy(words,0,grid.words,0,words.length);
        System.arraycopy(materials,0,grid.materials,0,materials.length); return grid;
    }
    public boolean sameContent(VoxelGrid other) {
        return other!=null && x==other.x && y==other.y && z==other.z
            && java.util.Arrays.equals(words,other.words) && java.util.Arrays.equals(materials,other.materials);
    }
    public void writeCells(ByteBuffer data) { for(int i=0;i<words.length;i++) data.putInt(i*4,words[i]); }

    /** Segment DDA through full occupied voxels; no triangle intersection is involved. */
    public boolean occluded(double sx,double sy,double sz,double ex,double ey,double ez) {
        double[] origin={sx-x,sy-y,sz-z},delta={ex-sx,ey-sy,ez-sz};
        double enter=0,exit=1;
        for(int axis=0;axis<3;axis++) {
            if(Math.abs(delta[axis])<1e-12) {
                if(origin[axis]<0 || origin[axis]>=SIZE) return false;
            } else {
                double a=-origin[axis]/delta[axis],b=(SIZE-origin[axis])/delta[axis];
                enter=Math.max(enter,Math.min(a,b)); exit=Math.min(exit,Math.max(a,b));
            }
        }
        if(enter>=exit) return false;
        int[] cell=new int[3],step=new int[3]; double[] next=new double[3],advance=new double[3];
        for(int axis=0;axis<3;axis++) {
            step[axis]=Double.compare(delta[axis],0);
            cell[axis]=(int)Math.floor(origin[axis]+delta[axis]*enter+step[axis]*1e-7);
            if(Math.abs(delta[axis])<1e-12) { next[axis]=advance[axis]=Double.POSITIVE_INFINITY; }
            else {
                next[axis]=(cell[axis]+(step[axis]>0?1:0)-origin[axis])/delta[axis];
                advance[axis]=Math.abs(1/delta[axis]);
            }
        }
        for(int i=0;i<MAX_STEPS;i++) {
            if(occupied(cell[0],cell[1],cell[2])) return true;
            double crossing=Math.min(next[0],Math.min(next[1],next[2]));
            if(crossing>=exit) break;
            for(int axis=0;axis<3;axis++) if(next[axis]<=crossing+1e-10) { cell[axis]+=step[axis]; next[axis]+=advance[axis]; }
        }
        return false;
    }
}
