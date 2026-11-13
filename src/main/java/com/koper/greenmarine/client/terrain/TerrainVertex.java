package com.koper.greenmarine.client.terrain;
import java.nio.*;
/** The 32-byte std430 terrain record. Signed midpoint bytes retain their magnitude. */
public final class TerrainVertex {
    public static final int BYTES=32;
    private TerrainVertex() {}
    /** Vertices contain emitted section-local x/y/z/u/v in unchanged host order. */
    public static ByteBuffer encodeQuad(float[][] v,int blockX,int blockY,int blockZ,int blockId,int renderType,int[] tintAo) {
        return encodeQuad(v,blockX,blockY,blockZ,blockId,renderType,tintAo,0);
    }
    /** Intrinsic block emission is independent of the material/render-type identity. */
    public static ByteBuffer encodeQuad(float[][] v,int blockX,int blockY,int blockZ,int blockId,int renderType,int[] tintAo,int emission) {
        emission=Math.clamp(emission,0,15);
        if(v.length!=4||tintAo.length!=4)throw new IllegalArgumentException("Four vertices required");
        float ax=v[2][0]-v[0][0],ay=v[2][1]-v[0][1],az=v[2][2]-v[0][2];
        float bx=v[3][0]-v[1][0],by=v[3][1]-v[1][1],bz=v[3][2]-v[1][2];
        float[] n=unit(ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx);
        float ex=v[1][0]-v[0][0],ey=v[1][1]-v[0][1],ez=v[1][2]-v[0][2];
        float fx=v[2][0]-v[0][0],fy=v[2][1]-v[0][1],fz=v[2][2]-v[0][2];
        float du=v[1][3]-v[0][3],dv=v[1][4]-v[0][4],eu=v[2][3]-v[0][3],ev=v[2][4]-v[0][4];
        float det=du*ev-eu*dv,inv=det==0?1:1/det;
        float[] t=unit(inv*(ev*ex-dv*fx),inv*(ev*ey-dv*fy),inv*(ev*ez-dv*fz));
        float[] b=unit(inv*(du*fx-eu*ex),inv*(du*fy-eu*ey),inv*(du*fz-eu*ez));
        float handed=b[0]*(t[1]*n[2]-t[2]*n[1])+b[1]*(t[2]*n[0]-t[0]*n[2])+b[2]*(t[0]*n[1]-t[1]*n[0])<0?-1:1;
        int normal=pack(n[0],n[1],n[2],0),tangent=pack(t[0],t[1],t[2],handed);
        if(normal==0&&t[0]==0&&t[1]==0&&t[2]==0)tangent=0;
        float u=0,w=0;for(var p:v){u+=p[3]*.25f;w+=p[4]*.25f;}
        var data=ByteBuffer.allocate(4*BYTES).order(ByteOrder.nativeOrder());
        for(int i=0;i<4;i++) {
            int mid=((byte)(64*(blockX+.5f-v[i][0]))&255)|((byte)(64*(blockY+.5f-v[i][1]))&255)<<8
                |((byte)(64*(blockZ+.5f-v[i][2]))&255)<<16|(renderType&255)<<24;
            data.putInt(normal).putInt(tangent).putInt(blockId).putInt(mid).putFloat(u).putFloat(w).putInt(tintAo[i]).putInt(emission);
        }
        return data.flip();
    }
    private static float[] unit(float x,float y,float z) {
        double len=Math.sqrt(x*x+y*y+z*z);if(!(len>1e-20)||!Double.isFinite(len))return new float[3];
        return new float[]{(float)(x/len),(float)(y/len),(float)(z/len)};
    }
    private static int pack(float x,float y,float z,float w) {return snorm(x)|snorm(y)<<8|snorm(z)<<16|snorm(w)<<24;}
    private static int snorm(float value){return ((byte)(Math.clamp(value,-1f,1f)*127f))&255;}
}
