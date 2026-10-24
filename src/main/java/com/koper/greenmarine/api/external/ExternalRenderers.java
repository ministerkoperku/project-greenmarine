package com.koper.greenmarine.api.external;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.nativebridge.GreenNative;
import com.koper.greenmarine.nativebridge.NativeRayTracer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Integration points for renderers that draw their own geometry outside Minecraft's
 * pipelines (native Vulkan instancing, moving block grids, model batches).
 *
 * <ul>
 * <li><b>Ray geometry</b>: meshes uploaded once, placed per frame. Surface RT traces them,
 * so they cast sun shadows, appear in reflections and bounce GI.</li>
 * <li><b>Material capture</b>: a raw Vulkan command buffer inside GreenMarine's open
 * material pass, so external pixels receive RT lighting like terrain.</li>
 * <li><b>Surface outputs</b>: the RT lighting/reflection images an external forward
 * shader samples to shade its own pixels.</li>
 * </ul>
 *
 * All entry points are optional: nothing here links back to the caller, and every
 * feature reports availability instead of failing when hardware RT is off.
 * See docs/EXTERNAL-RENDERERS.md for the full contract.
 */
public final class ExternalRenderers {
    public static final int API_VERSION = 1;
    /** Scene triangle ABI: 24 floats per triangle, see {@link #TRIANGLE_FLOATS}. */
    public static final int TRIANGLE_BYTES = 96, TRIANGLE_FLOATS = 24;
    public static final int MAX_INSTANCES = NativeRayTracer.MAX_EXTERNAL_INSTANCES;
    public static final int MAX_MESH_TRIANGLES = NativeRayTracer.MAX_EXTERNAL_MESH_TRIANGLES;
    /** uv2.z values: opaque atlas texel, alpha-tested atlas texel, untextured tint. */
    public static final float SURFACE_OPAQUE = 0, SURFACE_CUTOUT = 1, SURFACE_TINT = 2;
    /** Vulkan formats of the material pass attachments. */
    public static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97, VK_FORMAT_R32G32B32A32_SFLOAT = 109;

    public interface Registration extends AutoCloseable { @Override void close(); }

    private ExternalRenderers() {}

    // ---------------------------------------------------------------- ray geometry

    /** Per-frame placement context. Positions are absolute world coordinates. */
    public record RayFrame(double cameraX, double cameraY, double cameraZ, float partialTick, long frame) {}

    /** Receives this frame's instances; returns false once the instance budget is spent. */
    public interface RayInstances {
        boolean add(RayMesh mesh, double x, double y, double z, Matrix3fc basis);
        default boolean add(RayMesh mesh, double x, double y, double z) { return add(mesh, x, y, z, IDENTITY); }
        int remaining();
    }

    /** Called on the render thread before each surface RT recording. Keep it cheap. */
    @FunctionalInterface
    public interface RayContributor { void contribute(RayFrame frame, RayInstances instances); }

    private static final Matrix3fc IDENTITY = new Matrix3f();
    private static final Map<String, RayContributor> RAY = new LinkedHashMap<>();
    private record MaterialCallbacks(Runnable prepare,MaterialContributor draw) {}
    private static final Map<String, MaterialCallbacks> MATERIALS = new LinkedHashMap<>();
    private static volatile List<Map.Entry<String, RayContributor>> raySnapshot = List.of();
    private static volatile List<Map.Entry<String, MaterialCallbacks>> materialSnapshot = List.of();
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    /**
     * Mesh-local triangles in the scene ABI, kept on the CPU so the mesh can be uploaded
     * again after a device change. Create one per distinct shape and reuse it across frames;
     * geometry changes mean a new mesh (and {@link #close()} on the old one).
     */
    public static final class RayMesh implements AutoCloseable {
        private final ByteBuffer data;
        private final int triangles;
        private long token, generation = -1;
        private boolean closed, budgetFull;
        private RayMesh(ByteBuffer data, int triangles) { this.data = data; this.triangles = triangles; }
        public int triangles() { return triangles; }
        public synchronized boolean closed() { return closed; }
        /** Any thread. Forgets the native mesh; in-flight GPU work keeps its own reference. */
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            var tracer = tracer();
            if (token != 0 && tracer != null && generation == GreenNative.deviceGeneration()) tracer.releaseMesh(token);
            token = 0;
        }
        /** Any thread: uploads now instead of on first use (large meshes off the render thread). */
        public synchronized boolean prepare() {
            if (closed) return false;
            long current = GreenNative.deviceGeneration();
            if (token != 0 && generation == current) return true;
            var tracer = tracer();
            if (tracer == null || !tracer.externalAvailable()) return false;
            var result = tracer.prepareMesh(data.duplicate(), triangles);
            budgetFull = result.status() == 1;
            if (result.token() == 0) {
                if (result.status() < 0) GreenMarine.LOGGER.warn("External RT mesh upload failed: {}", result.error());
                return false;
            }
            token = result.token(); generation = current;
            return true;
        }
        synchronized long token() { return prepare() ? token : 0; }
        synchronized void invalidate() { token = 0; generation = -1; }
    }

    /** Copies {@code triangles} (native or any order, position..limit) into a new mesh. */
    public static RayMesh createMesh(ByteBuffer triangles) {
        int bytes = triangles.remaining();
        if (bytes == 0 || bytes % TRIANGLE_BYTES != 0 || bytes / TRIANGLE_BYTES > MAX_MESH_TRIANGLES)
            throw new IllegalArgumentException("External mesh needs 1.." + MAX_MESH_TRIANGLES + " triangles of " + TRIANGLE_BYTES + " bytes");
        var source = triangles.duplicate().order(triangles.order());
        var copy = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < bytes; i += 4) {
            float value = source.getFloat(source.position() + i);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite external mesh value at byte " + i);
            copy.putFloat(i, value);
        }
        return new RayMesh(copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), bytes / TRIANGLE_BYTES);
    }

    public static synchronized Registration registerRayGeometry(String id, RayContributor contributor) {
        requireId(id); Objects.requireNonNull(contributor);
        if (RAY.putIfAbsent(id, contributor) != null) throw new IllegalArgumentException("Duplicate ray geometry contributor: " + id);
        raySnapshot = List.copyOf(RAY.entrySet()); FAILED.remove(id);
        return () -> { synchronized (ExternalRenderers.class) { if (RAY.remove(id, contributor)) raySnapshot = List.copyOf(RAY.entrySet()); } };
    }

    /** True while hardware surface RT is running with the external geometry extension. */
    public static boolean rayGeometryAvailable() {
        var tracer = tracer();
        return tracer != null && tracer.externalAvailable() && com.koper.greenmarine.client.rt.RayWorld.surfaceReady();
    }

    /** Host use only: collected instances for this recording, transforms relative to the scene origin. */
    public record Collected(long[] tokens, float[] transforms, RayMesh[] meshes, int count, int dropped) {}

    /** Host use only (render thread). */
    public static Collected collectRayGeometry(RayFrame frame, int originX, int originY, int originZ) {
        var entries = raySnapshot;
        var tokens = new long[MAX_INSTANCES];var transforms = new float[MAX_INSTANCES * 12];var meshes = new RayMesh[MAX_INSTANCES];
        int[] count = {0}, dropped = {0};
        RayInstances sink = new RayInstances() {
            @Override public boolean add(RayMesh mesh, double x, double y, double z, Matrix3fc basis) {
                if (count[0] >= MAX_INSTANCES) {dropped[0]++;return false;}
                if (mesh == null || basis == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return true;
                float determinant = basis.determinant();
                if (!Float.isFinite(determinant) || Math.abs(determinant) < 1e-12f) return true;
                long token = mesh.token();
                if (token == 0) {if (mesh.budgetFull) dropped[0]++;return true;}
                int i = count[0]++;
                tokens[i] = token; meshes[i] = mesh;
                writeTransform(transforms, i * 12, x, y, z, basis, originX, originY, originZ);
                return count[0] < MAX_INSTANCES;
            }
            @Override public int remaining() { return MAX_INSTANCES - count[0]; }
        };
        for (var entry : entries) {
            if (FAILED.contains(entry.getKey())) continue;
            try { entry.getValue().contribute(frame, sink); }
            catch (RuntimeException | LinkageError failure) {
                FAILED.add(entry.getKey());
                GreenMarine.LOGGER.error("External ray geometry contributor {} failed and was disabled until re-registered", entry.getKey(), failure);
            }
        }
        return new Collected(tokens, transforms, meshes, count[0], dropped[0]);
    }

    /** Row-major 3x4 (VkTransformMatrixKHR) mesh-to-scene matrix; translation subtracts the integer origin in double precision. */
    static void writeTransform(float[] out, int offset, double x, double y, double z, Matrix3fc basis, int originX, int originY, int originZ) {
        double[] translation = {x - originX, y - originY, z - originZ};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) out[offset + row * 4 + column] = basis.get(column, row);
            out[offset + row * 4 + 3] = (float) translation[row];
        }
    }

    /** Host use only: the native module rejected a stale token; force re-upload. */
    public static void invalidate(Collected collected) {
        for (int i = 0; i < collected.count(); i++) collected.meshes()[i].invalidate();
    }

    // ---------------------------------------------------------------- material capture

    /**
     * The open material pass. Draw with dynamic rendering state compatible with:
     * color 0 {@link #VK_FORMAT_R32G32B32A32_SFLOAT} = (world normal xyz, gl_FragCoord.z),
     * color 1 {@link #VK_FORMAT_R16G16B16A16_SFLOAT} = (linear albedo rgb, surfaceClass + roughness * 0.5),
     * depth {@code depthFormat}, reversed-Z (compare GREATER_OR_EQUAL, write on), no blending.
     * Clip = projection * viewRotation * (world - camera). Viewport/scissor cover width x height;
     * GreenMarine records nothing else in the pass after contributors, so bound state may be left dirty.
     */
    public record MaterialTarget(long vkCommandBuffer, int width, int height, int depthFormat,
                                 Matrix4fc projection, Matrix4fc viewRotation,
                                 double cameraX, double cameraY, double cameraZ, boolean zZeroToOne) {}

    @FunctionalInterface
    public interface MaterialContributor { void drawMaterials(MaterialTarget target); }

    public static Registration registerMaterials(String id,MaterialContributor contributor) {
        return registerMaterials(id,()->{},contributor);
    }
    /** Preparation runs on the render thread before any material render pass; upload textures here. */
    public static synchronized Registration registerMaterials(String id,Runnable prepare,MaterialContributor contributor) {
        requireId(id);Objects.requireNonNull(prepare);Objects.requireNonNull(contributor);
        var callbacks=new MaterialCallbacks(prepare,contributor);
        if(MATERIALS.putIfAbsent(id,callbacks)!=null)throw new IllegalArgumentException("Duplicate material contributor: "+id);
        materialSnapshot=List.copyOf(MATERIALS.entrySet());FAILED.remove(id);
        return ()->{synchronized(ExternalRenderers.class){if(MATERIALS.remove(id,callbacks))materialSnapshot=List.copyOf(MATERIALS.entrySet());}};
    }
    /** Host use only, before opening capture passes. A preparation failure also disables its draw. */
    public static void prepareMaterials() {
        for(var entry:materialSnapshot) {
            if(FAILED.contains(entry.getKey()))continue;
            try {entry.getValue().prepare().run();}
            catch(RuntimeException|LinkageError failure) {
                FAILED.add(entry.getKey());
                GreenMarine.LOGGER.error("External material preparation {} failed and was disabled until re-registered",entry.getKey(),failure);
            }
        }
    }

    private static final Matrix4f PROJECTION = new Matrix4f(), VIEW = new Matrix4f();
    private static double cameraX, cameraY, cameraZ;
    private static boolean zZeroToOne;

    /** Host use only: the camera of the frame being captured. */
    public static void captureCamera(Matrix4fc projection, Matrix4fc viewRotation, double x, double y, double z, boolean zeroToOne) {
        PROJECTION.set(projection); VIEW.set(viewRotation); cameraX = x; cameraY = y; cameraZ = z; zZeroToOne = zeroToOne;
    }
    public static boolean hasMaterialContributors() { return !materialSnapshot.isEmpty(); }

    /** Host use only (render thread), inside the open material pass after terrain. */
    public static void drawMaterials(long commandBuffer, int width, int height, int depthFormat) {
        var entries = materialSnapshot;
        if (entries.isEmpty() || commandBuffer == 0) return;
        var target = new MaterialTarget(commandBuffer, width, height, depthFormat, new Matrix4f(PROJECTION), new Matrix4f(VIEW),
            cameraX, cameraY, cameraZ, zZeroToOne);
        for (var entry : entries) {
            if (FAILED.contains(entry.getKey())) continue;
            try { entry.getValue().draw().drawMaterials(target); }
            catch (RuntimeException | LinkageError failure) {
                FAILED.add(entry.getKey());
                GreenMarine.LOGGER.error("External material contributor {} failed and was disabled until re-registered", entry.getKey(), failure);
            }
        }
    }

    // ---------------------------------------------------------------- surface outputs

    /**
     * RT results for this frame, as raw VkImageView handles in GENERAL layout, valid from the
     * world pass until the next frame's surface recording. Lighting is linear irradiance
     * (rgb) and sun visibility (a); reflection is linear radiance (rgb) and hit coverage (a);
     * both are {@code width x height} (often half resolution) RGBA16F. Normal/albedo are the
     * full-resolution material captures. Shade: albedo * lighting.rgb, then mix in
     * reflection.rgb by reflection.a times your Fresnel weight (see greenmarine:external_surface.glsl).
     */
    public record SurfaceOutputs(boolean ready, long lightingView, long reflectionView, int width, int height,
                                 long normalDepthView, long albedoView) {
        public static final SurfaceOutputs UNAVAILABLE = new SurfaceOutputs(false, 0, 0, 0, 0, 0, 0);
    }

    /** Render thread. */
    public static SurfaceOutputs surfaceOutputs() {
        RenderSystem.assertOnRenderThread();
        return com.koper.greenmarine.client.rt.RayWorld.externalOutputs();
    }

    // ----------------------------------------------------------------

    private static NativeRayTracer tracer() { return com.koper.greenmarine.client.rt.RayWorld.tracer(); }
    private static void requireId(String id) {
        if (id == null || !id.contains(":")) throw new IllegalArgumentException("Contributor IDs must be namespaced");
        try { Identifier.parse(id); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid contributor id: " + id, invalid); }
    }
}
