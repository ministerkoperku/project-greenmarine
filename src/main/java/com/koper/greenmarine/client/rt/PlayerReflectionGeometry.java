package com.koper.greenmarine.client.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts actual baked cube polygons, including deformation and mirrored skin UVs. */
final class PlayerReflectionGeometry {
    static final int MAX_BOXES=16, EXTENDED_MAX_BOXES=64, BOX_BYTES=288, BYTES=16+MAX_BOXES*BOX_BYTES, EXTENDED_BYTES=16+EXTENDED_MAX_BOXES*BOX_BYTES;
    private PlayerReflectionGeometry() {}

    static final class Builder {
        private final ByteBuffer buffer;
        private final int capacity;
        private int count;
        Builder() {this(MAX_BOXES);}
        Builder(int capacity) {
            if(capacity!=MAX_BOXES && capacity!=EXTENDED_MAX_BOXES)throw new IllegalArgumentException("Unsupported actor capacity");
            this.capacity=capacity;buffer=ByteBuffer.allocateDirect(16+capacity*BOX_BYTES).order(ByteOrder.nativeOrder());
        }
        int count() {return count;}
        ByteBuffer data() {return buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        boolean add(ModelPart.Cube cube,Matrix4fc pose) {
            if(count==capacity || cube.polygons.length!=6 || !pose.isFinite()
                || pose.m03()!=0 || pose.m13()!=0 || pose.m23()!=0 || pose.m33()!=1) return false;
            var inverse=new Matrix4f(pose).scale(1f/16f);
            if(!Float.isFinite(inverse.determinant()) || Math.abs(inverse.determinant())<1e-15f) return false;
            inverse.invert();if(!inverse.isFinite()) return false;
            float[] lower={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
            float[] upper={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(var polygon:cube.polygons) {
                if(polygon==null || polygon.vertices().length!=4) return false;
                for(var vertex:polygon.vertices()) {
                    float[] xyz={vertex.x(),vertex.y(),vertex.z()};
                    if(!Float.isFinite(vertex.u()) || !Float.isFinite(vertex.v())) return false;
                    for(int axis=0;axis<3;axis++) {
                        if(!Float.isFinite(xyz[axis])) return false;
                        lower[axis]=Math.min(lower[axis],xyz[axis]);upper[axis]=Math.max(upper[axis],xyz[axis]);
                    }
                }
            }
            for(int axis=0;axis<3;axis++) if(!(upper[axis]>lower[axis])) return false;
            float[][] us=new float[6][],vs=new float[6][];
            for(var polygon:cube.polygons) {
                var vertices=polygon.vertices();var first=vertices[0];int face=-1;
                for(int axis=0;axis<3;axis++) {
                    float plane=coordinate(first,axis);boolean flat=true;
                    for(var vertex:vertices) flat&=Math.abs(coordinate(vertex,axis)-plane)<1e-5f;
                    if(flat) {
                        if(face!=-1) return false;
                        boolean positive=Math.abs(plane-upper[axis])<1e-5f;
                        if(!positive && Math.abs(plane-lower[axis])>=1e-5f) return false;
                        face=axis*2+(positive?1:0);
                    }
                }
                if(face<0 || us[face]!=null) return false;
                us[face]=uvRow(vertices,true);vs[face]=uvRow(vertices,false);
                if(us[face]==null || vs[face]==null) return false;
            }
            int offset=16+count*BOX_BYTES;
            inverse.get(offset,buffer);
            for(int axis=0;axis<3;axis++) {buffer.putFloat(offset+64+axis*4,lower[axis]);buffer.putFloat(offset+80+axis*4,upper[axis]);}
            for(int face=0;face<6;face++) for(int component=0;component<4;component++) {
                buffer.putFloat(offset+96+face*16+component*4,us[face][component]);
                buffer.putFloat(offset+192+face*16+component*4,vs[face][component]);
            }
            buffer.putFloat(0,++count);return true;
        }
    }
    private static float coordinate(ModelPart.Vertex vertex,int axis) {return axis==0?vertex.x():axis==1?vertex.y():vertex.z();}
    /** Fit an affine plane to three vertices, then verify the fourth corner. */
    private static float[] uvRow(ModelPart.Vertex[] vertices,boolean u) {
        var origin=vertices[0];double[] e=new double[3],f=new double[3];
        double ee=0,ff=0,ef=0;
        for(int axis=0;axis<3;axis++) {
            e[axis]=coordinate(vertices[1],axis)-coordinate(origin,axis);
            f[axis]=coordinate(vertices[2],axis)-coordinate(origin,axis);
            ee+=e[axis]*e[axis];ff+=f[axis]*f[axis];ef+=e[axis]*f[axis];
        }
        double determinant=ee*ff-ef*ef;if(!(determinant>1e-12)) return null;
        double base=u?origin.u():origin.v();
        double de=(u?vertices[1].u():vertices[1].v())-base,df=(u?vertices[2].u():vertices[2].v())-base;
        double a=(de*ff-df*ef)/determinant,b=(df*ee-de*ef)/determinant;
        float[] row=new float[4];double offset=base;
        for(int axis=0;axis<3;axis++) {row[axis]=(float)(a*e[axis]+b*f[axis]);offset-=row[axis]*coordinate(origin,axis);}
        row[3]=(float)offset;
        for(float value:row) if(!Float.isFinite(value)) return null;
        for(var vertex:vertices) {
            float value=row[0]*vertex.x()+row[1]*vertex.y()+row[2]*vertex.z()+row[3];
            if(!Float.isFinite(value) || Math.abs(value-(u?vertex.u():vertex.v()))>1e-4f) return null;
        }
        return row;
    }
    static Builder capture(ModelPart root,PoseStack pose) {
        var builder=new Builder();
        root.visit(pose,(partPose,path,index,cube)->{if(visible(root,path))builder.add(cube,partPose.pose());});
        return builder;
    }
    /** ModelPart.visit deliberately ignores visibility; rendering does not. */
    private static boolean visible(ModelPart root,String path) {
        var part=root;if(!part.visible)return false;
        for(String name:path.split("/")) if(!name.isEmpty()) {
            if(!part.hasChild(name))return false;
            part=part.getChild(name);if(!part.visible)return false;
        }
        return !part.skipDraw;
    }
}
