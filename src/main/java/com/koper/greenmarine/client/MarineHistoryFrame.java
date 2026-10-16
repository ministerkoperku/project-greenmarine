package com.koper.greenmarine.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import java.nio.ByteBuffer;
import java.util.Map;

/** Submitted per-pass camera state; independent of the GPU attachment lifetime. */
public final class MarineHistoryFrame {
    public static final int BYTES = 160;
    public record Camera(Matrix4fc projection, Matrix4fc view, double x, double y, double z,
                         Object world, Map<String,Float> parameters) {
        public Camera {
            projection = new Matrix4f(projection); view = new Matrix4f(view);
            parameters = Map.copyOf(parameters);
        }
    }
    private Camera previous;
    private boolean normals;
    private int age;
    private long submittedNanos;

    public void invalidate() { previous = null; normals = false; age = 0; submittedNanos=0; }

    public void write(ByteBuffer data, Camera current) {
        write(data,current,System.nanoTime());
    }
    void write(ByteBuffer data,Camera current,long now) {
        boolean ready = continuous(current);
        var source = ready ? previous : current;
        var projection = finite(source) ? source.projection() : new Matrix4f();
        var view = finite(source) ? source.view() : new Matrix4f();
        projection.get(0,data); view.get(64,data);
        data.putFloat(128,ready ? (float)(current.x()-previous.x()) : 0);
        data.putFloat(132,ready ? (float)(current.y()-previous.y()) : 0);
        data.putFloat(136,ready ? (float)(current.z()-previous.z()) : 0).putFloat(140,0);
        data.putFloat(144,ready ? 1 : 0).putFloat(148,ready && normals ? 1 : 0);
        data.putFloat(152,ready ? age : 0).putFloat(156,ready?(float)Math.clamp((now-submittedNanos)/1e9,0,.25):0);
    }

    /** Called only after the command encoder successfully submitted the pass output. */
    public void submitted(Camera camera, boolean normalDepthAvailable) {
        submitted(camera,normalDepthAvailable,System.nanoTime());
    }
    void submitted(Camera camera,boolean normalDepthAvailable,long now) {
        if (!finite(camera)) { invalidate(); return; }
        age = continuous(camera) ? Math.min(age+1,1_000_000) : 1;
        previous = camera; normals = normalDepthAvailable;
        submittedNanos=now;
    }

    private boolean continuous(Camera current) {
        if (previous == null || !finite(current) || previous.world() != current.world()
            || !previous.parameters().equals(current.parameters())) return false;
        double dx=current.x()-previous.x(), dy=current.y()-previous.y(), dz=current.z()-previous.z();
        if (dx*dx+dy*dy+dz*dz > 64) return false;
        var rotation = new Matrix4f(current.view()).getNormalizedRotation(new Quaternionf());
        var prior = new Matrix4f(previous.view()).getNormalizedRotation(new Quaternionf());
        if (Math.abs(rotation.dot(prior)) < Math.cos(Math.PI/8)) return false;
        return sameScale(rowScale(current.projection(),0),rowScale(previous.projection(),0))
            && sameScale(rowScale(current.projection(),1),rowScale(previous.projection(),1));
    }
    private static boolean sameScale(double current,double previous) {
        return Math.abs(current-previous) <= Math.max(current,previous)*1e-4;
    }
    // Rigid bob/hurt transforms preserve these row lengths; perspective FOV/aspect do not.
    private static double rowScale(Matrix4fc matrix,int row) {
        double x=matrix.get(0,row), y=matrix.get(1,row), z=matrix.get(2,row);
        return Math.sqrt(x*x+y*y+z*z);
    }
    private static boolean finite(Camera camera) {
        return camera.world()!=null && Double.isFinite(camera.x()) && Double.isFinite(camera.y())
            && Double.isFinite(camera.z()) && camera.projection().isFinite() && camera.view().isFinite()
            && rowScale(camera.projection(),0)>0 && rowScale(camera.projection(),1)>0;
    }
}
