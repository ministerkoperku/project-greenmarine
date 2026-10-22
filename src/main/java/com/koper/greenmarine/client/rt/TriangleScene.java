package com.koper.greenmarine.client.rt;

import java.nio.*;
import java.util.Objects;

/** Six vec4 fields per textured triangle, shared with native std430 metadata. */
public final class TriangleScene {
    public static final int TRIANGLE_BYTES = 96, MAX_TRIANGLES = 1_000_000;
    private final int x, y, z, triangles;
    private final ByteBuffer data;
    private final long hash;
    private TriangleScene(int x, int y, int z, int triangles, ByteBuffer data, long hash) {
        this.x=x; this.y=y; this.z=z; this.triangles=triangles; this.data=data; this.hash=hash;
    }
    public int x() { return x; } public int y() { return y; } public int z() { return z; }
    public int triangles() { return triangles; }
    public long hash() { return hash; }
    public float cameraX(double camera) { return (float)(camera-x); }
    public float cameraY(double camera) { return (float)(camera-y); }
    public float cameraZ(double camera) { return (float)(camera-z); }
    public ByteBuffer data() { return data.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    public boolean sameContent(TriangleScene other) { return other != null && x==other.x && y==other.y && z==other.z && triangles==other.triangles && hash==other.hash; }
    public static final class Builder {
        private final int x,y,z,max;
        private int count;
        private ByteBuffer data;
        private boolean transferred;
        private long hash=0xcbf29ce484222325L;
        public Builder(int x,int y,int z,int max) {
            this(x,y,z,max,Math.min(max,4096));
        }
        /** Known-size worker assembly avoids geometric growth and an extra full-size copy. */
        public Builder(int x,int y,int z,int max,int initialTriangles) {
            if(max<=0 || max>MAX_TRIANGLES) throw new IllegalArgumentException("Invalid triangle budget");
            if(initialTriangles<0 || initialTriangles>max)throw new IllegalArgumentException("Invalid initial triangle capacity");
            this.x=x;this.y=y;this.z=z;this.max=max;
            data=ByteBuffer.allocateDirect(initialTriangles*TRIANGLE_BYTES).order(ByteOrder.nativeOrder());
        }
        public void triangle(float[] positions,float[] uv,int tint,int emission,boolean cutout) {
            triangle(positions,uv,tint,emission,cutout,0);
        }
        public void triangle(float[] positions,float[] uv,int tint,int emission,boolean cutout,float sunlightTransmission) {
            requireOpen();
            if(!Float.isFinite(sunlightTransmission)||sunlightTransmission<0||sunlightTransmission>1)throw new IllegalArgumentException("Invalid sunlight transmission");
            if(positions.length!=9 || uv.length!=6 || emission<0 || emission>15) throw new IllegalArgumentException("Invalid triangle data");
            for(float v:positions) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite vertex");
            for(float v:uv) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite UV");
            float ax=positions[3]-positions[0], ay=positions[4]-positions[1], az=positions[5]-positions[2];
            float bx=positions[6]-positions[0], by=positions[7]-positions[1], bz=positions[8]-positions[2];
            float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
            if(nx*nx+ny*ny+nz*nz<1e-12f) return;
            if(count==max) throw new IllegalArgumentException("World triangle budget exceeded");
            if(data.remaining()<TRIANGLE_BYTES) {
                var next=ByteBuffer.allocateDirect(Math.min(max*TRIANGLE_BYTES,Math.max(TRIANGLE_BYTES,data.capacity()*2))).order(ByteOrder.nativeOrder());
                data.flip();next.put(data);data=next;
            }
            for(int i=0;i<3;i++) { for(int j=0;j<3;j++) put(positions[i*3+j]);put(0); }
            for(int i=0;i<4;i++) put(uv[i]);put(uv[4]);put(uv[5]);put(cutout?1:0);put(emission);
            put((tint>>16&255)/255f);put((tint>>8&255)/255f);put((tint&255)/255f);put(sunlightTransmission);count++;
        }
        /** Append immutable local geometry, rebasing only positions and preserving material bits. */
        public void append(TriangleScene source,int dx,int dy,int dz) {
            requireOpen();
            Objects.requireNonNull(source);
            if((long)count+source.triangles>max)throw new IllegalArgumentException("World triangle budget exceeded");
            var input=source.data();
            while(input.hasRemaining()) {
                if(data.remaining()<TRIANGLE_BYTES) {
                    var next=ByteBuffer.allocateDirect(Math.min(max*TRIANGLE_BYTES,Math.max(data.capacity()*2,(count+1)*TRIANGLE_BYTES))).order(ByteOrder.nativeOrder());
                    data.flip();next.put(data);data=next;
                }
                for(int vertex=0;vertex<3;vertex++){put(input.getFloat()+dx);put(input.getFloat()+dy);put(input.getFloat()+dz);put(input.getFloat());}
                for(int value=0;value<12;value++)put(input.getFloat());
                count++;
            }
        }
        private void put(float value) { data.putFloat(value);hash=(hash^Integer.toUnsignedLong(Float.floatToRawIntBits(value)))*0x100000001b3L; }
        public TriangleScene finish() {
            requireOpen();
            var result=ByteBuffer.allocateDirect(count*TRIANGLE_BYTES).order(ByteOrder.nativeOrder());
            var copy=data.duplicate();copy.flip();result.put(copy).flip();
            return new TriangleScene(x,y,z,count,result,hash);
        }
        /** Transfer exact storage once. Legacy finish() remains a reusable copying snapshot. */
        public TriangleScene finishOwned() {
            requireOpen();
            if(data.capacity()!=count*TRIANGLE_BYTES){var compact=finish();transferred=true;data=null;return compact;}
            var result=data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());result.flip();
            transferred=true;data=null;return new TriangleScene(x,y,z,count,result,hash);
        }
        private void requireOpen() {if(transferred)throw new IllegalStateException("Triangle storage has been transferred");}
    }
}
