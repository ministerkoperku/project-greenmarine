package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.*;
import java.util.*;

/** A callback-scoped snapshot. Borrowed textures must never be closed by consumers. */
public final class FrameContext {
    private final Map<String, GpuTextureView> textures;
    private final Matrix4f projection, view;
    private final int width, height;
    private final double cameraX, cameraY, cameraZ;
    private final float seconds;
    private final boolean zeroToOne;
    private final boolean worldRaysReady,surfacesReady,surfaceRaysReady;
    private boolean active = true;
    public FrameContext(Map<String, GpuTextureView> textures, int width, int height,
                        Matrix4fc projection, Matrix4fc view, double cameraX, double cameraY,
                        double cameraZ, float seconds, boolean zeroToOne) {
        this(textures,width,height,projection,view,cameraX,cameraY,cameraZ,seconds,zeroToOne,false);
    }
    public FrameContext(Map<String, GpuTextureView> textures, int width, int height,
                        Matrix4fc projection, Matrix4fc view, double cameraX, double cameraY,
                        double cameraZ, float seconds, boolean zeroToOne, boolean worldRaysReady) {
        this(textures,width,height,projection,view,cameraX,cameraY,cameraZ,seconds,zeroToOne,worldRaysReady,false,false);
    }
    public FrameContext(Map<String, GpuTextureView> textures, int width, int height,
                        Matrix4fc projection, Matrix4fc view, double cameraX, double cameraY,
                        double cameraZ, float seconds, boolean zeroToOne, boolean worldRaysReady,
                        boolean surfacesReady, boolean surfaceRaysReady) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid frame dimensions");
        this.textures = Map.copyOf(textures); this.width = width; this.height = height;
        this.projection = new Matrix4f(projection); this.view = new Matrix4f(view);
        this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ;
        this.seconds = seconds; this.zeroToOne = zeroToOne;
        this.worldRaysReady=worldRaysReady;this.surfacesReady=surfacesReady;this.surfaceRaysReady=surfaceRaysReady;
    }
    public void checkActive() { if (!active) throw new IllegalStateException("Frame callback has ended"); }
    public void expire() { active = false; }
    public GpuTextureView texture(String name) {
        checkActive(); var value = textures.get(name);
        if (value == null) throw new IllegalArgumentException("Unknown frame texture: " + name);
        return value;
    }
    public int width() { checkActive(); return width; }
    public int height() { checkActive(); return height; }
    public Matrix4f projection() { checkActive(); return new Matrix4f(projection); }
    public Matrix4f view() { checkActive(); return new Matrix4f(view); }
    public float seconds() { checkActive(); return seconds; }
    public boolean zeroToOne() { checkActive(); return zeroToOne; }
    public boolean worldRaysReady() { checkActive(); return worldRaysReady; }
    public boolean surfacesReady() {checkActive();return surfacesReady;}
    public boolean surfaceRaysReady() {checkActive();return surfaceRaysReady;}
    public Matrix4f modelAt(double x, double y, double z) {
        checkActive(); return relativeModel(cameraX, cameraY, cameraZ, x, y, z);
    }
    public static Matrix4f relativeModel(double cameraX, double cameraY, double cameraZ, double x, double y, double z) {
        if (!Double.isFinite(x-cameraX) || !Double.isFinite(y-cameraY) || !Double.isFinite(z-cameraZ))
            throw new IllegalArgumentException("Non-finite model position");
        return new Matrix4f().translation((float)(x-cameraX), (float)(y-cameraY), (float)(z-cameraZ));
    }
}
