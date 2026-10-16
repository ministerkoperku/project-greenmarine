package com.koper.greenmarine.client;

public final class VoxelScanner {
    @FunctionalInterface public interface SolidQuery { boolean occupied(int x,int y,int z); }
    @FunctionalInterface public interface MaterialQuery { int sample(int x,int y,int z); }
    private VoxelGrid staging,completed;
    private int cursor;
    public void begin(int x,int y,int z) { staging=new VoxelGrid(x,y,z); completed=null; cursor=0; }
    public boolean step(SolidQuery query,int blockBudget,long nanoBudget) {
        return stepMaterials((x,y,z)->query.occupied(x,y,z)?0x80000000:0,blockBudget,nanoBudget);
    }
    public boolean stepMaterials(MaterialQuery query,int blockBudget,long nanoBudget) {
        if(staging==null || blockBudget<1 || nanoBudget<1) throw new IllegalArgumentException("Invalid voxel scan/budget");
        long start=System.nanoTime(); int stop=Math.min(VoxelGrid.CELLS,cursor+blockBudget);
        while(cursor<stop) {
            int x=cursor%32,y=(cursor/32)%32,z=cursor/1024;
            int sample=query.sample(staging.x()+x,staging.y()+y,staging.z()+z);
            staging.setSample(x,y,z,sample<0,sample&0xFFFFFF,(sample>>>24)&15); cursor++;
            if((cursor&63)==0 && System.nanoTime()-start>=nanoBudget) break;
        }
        if(cursor==VoxelGrid.CELLS && completed==null) completed=staging.copy();
        return completed!=null;
    }
    public int progress() { return cursor; }
    public VoxelGrid completed() { return completed; }
}
