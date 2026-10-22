package com.koper.greenmarine.nativebridge;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;

/** Additive RT ABI v1; all device/command/image handles are borrowed from the verified host. */
public final class NativeRayTracer {
    private MethodHandle init,scene,record,reset;
    private MethodHandle surfaceInit,surfaceRecord,surfaceActorsRecord,surfaceActorsExtendedRecord;
    public static final int SURFACE_ACTOR_BYTES=4624, SURFACE_EXTENDED_ACTOR_BYTES=18448;
    private String surfaceError="Surface RT native module is unavailable";
    private volatile String error="RT native module is unavailable";
    private MethodHandle asyncBegin,asyncSubmitted,asyncCompleted,asyncCancel,prepare,publish,discard,sceneAsync,resetAsync,prepareStatusHandle,prepareErrorHandle,asyncStatsHandle;
    private String asyncError="Async RT native module is unavailable";
    private MethodHandle meshPrepare,meshRelease,externalFrame,externalStats;
    /** Result of an external mesh upload: token 0 with status 1 means the budget is full. */
    public record MeshResult(long token,int status,String error) {}
    private volatile int lastPrepareStatus=-2;
    private volatile String lastPrepareError="Async RT native module is unavailable";
    NativeRayTracer(SymbolLookup symbols) {
        if(symbols==null) return;
        try {
            var linker=Linker.nativeLinker();
            var version=linker.downcallHandle(symbols.find("greenmarine_rt_api_version").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            if((int)version.invokeExact()!=1) throw new IllegalStateException("Unsupported RT ABI");
            init=linker.downcallHandle(symbols.find("greenmarine_rt_init").orElseThrow(),FunctionDescriptor.of(JAVA_INT,JAVA_INT));
            scene=linker.downcallHandle(symbols.find("greenmarine_rt_scene").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
            record=linker.downcallHandle(symbols.find("greenmarine_rt_record").orElseThrow(),FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG));
            reset=linker.downcallHandle(symbols.find("greenmarine_rt_reset_scene").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            error="";
        } catch(Throwable failed) {init=scene=record=reset=null;error="RT module unavailable: "+failed.getMessage();}
        // Optional symbols must never make the established RT ABI unavailable.
        try {
            var linker=Linker.nativeLinker();
            var version=linker.downcallHandle(symbols.find("greenmarine_surface_rt_api_version").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            if((int)version.invokeExact()!=1) throw new IllegalStateException("Unsupported surface RT ABI");
            surfaceInit=linker.downcallHandle(symbols.find("greenmarine_surface_rt_init").orElseThrow(),FunctionDescriptor.of(JAVA_INT,JAVA_INT));
            surfaceRecord=linker.downcallHandle(symbols.find("greenmarine_surface_rt_record").orElseThrow(),FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG));
            surfaceError="";
        } catch(Throwable failed) {surfaceInit=surfaceRecord=null;surfaceError="Surface RT module unavailable: "+failed.getMessage();}
        try {
            var linker=Linker.nativeLinker();
            var version=linker.downcallHandle(symbols.find("greenmarine_surface_rt_actors_api_version").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            if((int)version.invokeExact()!=1) throw new IllegalStateException("Unsupported surface actor ABI");
            surfaceActorsRecord=linker.downcallHandle(symbols.find("greenmarine_surface_rt_record_actors").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,ADDRESS,JAVA_LONG));
        } catch(Throwable unavailable) {surfaceActorsRecord=null;}
        try {
            var linker=Linker.nativeLinker();
            var version=linker.downcallHandle(symbols.find("greenmarine_surface_rt_actors_extended_api_version").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            if((int)version.invokeExact()!=1) throw new IllegalStateException("Unsupported surface actor ABI");
            surfaceActorsExtendedRecord=linker.downcallHandle(symbols.find("greenmarine_surface_rt_record_actors_extended").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,ADDRESS,JAVA_LONG));
        } catch(Throwable unavailable) {surfaceActorsExtendedRecord=null;}
        try {
            var linker=Linker.nativeLinker();
            var version=linker.downcallHandle(symbols.find("greenmarine_rt_external_api_version").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            if((int)version.invokeExact()!=1) throw new IllegalStateException("Unsupported external geometry ABI");
            meshPrepare=linker.downcallHandle(symbols.find("greenmarine_rt_mesh_prepare").orElseThrow(),FunctionDescriptor.of(JAVA_LONG,ADDRESS,JAVA_INT));
            meshRelease=serialCall(linker,symbols,"greenmarine_rt_mesh_release");
            externalFrame=linker.downcallHandle(symbols.find("greenmarine_rt_external_frame").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT));
            externalStats=linker.downcallHandle(symbols.find("greenmarine_rt_external_stats").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
        } catch(Throwable unavailable) {meshPrepare=meshRelease=externalFrame=externalStats=null;}
        try {
            var linker=Linker.nativeLinker();
            var version=linker.downcallHandle(symbols.find("greenmarine_rt_async_api_version").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            if((int)version.invokeExact()!=1) throw new IllegalStateException("Unsupported async RT ABI");
            asyncBegin=serialCall(linker,symbols,"greenmarine_rt_async_begin");
            asyncSubmitted=serialCall(linker,symbols,"greenmarine_rt_async_submitted");
            asyncCompleted=serialCall(linker,symbols,"greenmarine_rt_async_completed");
            asyncCancel=serialCall(linker,symbols,"greenmarine_rt_async_cancel");
            prepare=linker.downcallHandle(symbols.find("greenmarine_rt_scene_prepare").orElseThrow(),FunctionDescriptor.of(JAVA_LONG,ADDRESS,JAVA_INT));
            publish=serialCall(linker,symbols,"greenmarine_rt_scene_publish");
            discard=serialCall(linker,symbols,"greenmarine_rt_scene_discard");
            sceneAsync=linker.downcallHandle(symbols.find("greenmarine_rt_scene_async").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
            resetAsync=linker.downcallHandle(symbols.find("greenmarine_rt_reset_scene_async").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            prepareStatusHandle=linker.downcallHandle(symbols.find("greenmarine_rt_prepare_status").orElseThrow(),FunctionDescriptor.of(JAVA_INT));
            prepareErrorHandle=linker.downcallHandle(symbols.find("greenmarine_rt_prepare_error").orElseThrow(),FunctionDescriptor.of(ADDRESS));
            asyncStatsHandle=linker.downcallHandle(symbols.find("greenmarine_rt_async_stats").orElseThrow(),FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT));
            asyncError="";
        } catch(Throwable failed) {asyncBegin=null;asyncError="Async RT module unavailable: "+failed.getMessage();}

    }
    public boolean available() {return init!=null;}
    public String error() {return error;}
    private int result(int code,String operation) {if(code!=0) error=operation+" failed (native status "+code+")";return code;}
    public int initialize(boolean enabled) {
        if(!available()) return -2;
        try {return result((int)init.invokeExact(enabled?1:0),"Hardware RT initialization");}
        catch(Throwable failed) {error=failed.toString();return -3;}
    }
    public int scene(ByteBuffer data,int triangles) {
        if(!available()) return -2;
        if(!data.isDirect() || triangles<1 || triangles>com.koper.greenmarine.client.rt.TriangleScene.MAX_TRIANGLES || data.remaining()!=(long)triangles*96) throw new IllegalArgumentException("Invalid native triangle ABI");
        try {return result((int)scene.invokeExact(MemorySegment.ofBuffer(data),triangles),"BLAS/TLAS allocation");}
        catch(Throwable failed) {error=failed.toString();return -3;}
    }
    public int record(long command,ByteBuffer frame,int width,int height,long color,long normal,long albedo,long atlas) {
        if(!available()) return -2;
        if(!frame.isDirect() || frame.remaining()!=320) throw new IllegalArgumentException("Invalid native frame ABI");
        try {return result((int)record.invokeExact(command,MemorySegment.ofBuffer(frame),width,height,color,normal,albedo,atlas),"Hardware ray query dispatch");}
        catch(Throwable failed) {error=failed.toString();return -3;}
    }
    public int resetScene() {
        if(!available()) return -2;
        try {return result((int)reset.invokeExact(),"RT scene cleanup");}
        catch(Throwable failed) {error=failed.toString();return -3;}
    }
    public boolean surfaceAvailable() { return available() && surfaceInit!=null && surfaceRecord!=null; }
    public String surfaceError() { return surfaceError; }
    public int initializeSurface(boolean enabled) {
        if(!surfaceAvailable()) return -2;
        try {
            int code=(int)surfaceInit.invokeExact(enabled?1:0);
            surfaceError=code==0?"":"Surface RT initialization failed (native status "+code+")";
            return code;
        } catch(Throwable failed) {surfaceError=failed.toString();return -3;}
    }
    /** Inputs are sampled GENERAL-layout views; outputs are distinct RGBA16F TRANSFER_DST images. */
    public int recordSurface(long command,ByteBuffer frame,int width,int height,long lightingImage,long reflectionImage,long atlasView,long surfaceNormalView,long surfaceAlbedoView) {
        if(!surfaceAvailable()) return -2;
        if(!frame.isDirect() || frame.remaining()!=320) throw new IllegalArgumentException("Invalid native surface frame ABI");
        if(command==0 || width<1 || height<1 || lightingImage==0 || reflectionImage==0
            || lightingImage==reflectionImage || atlasView==0 || surfaceNormalView==0 || surfaceAlbedoView==0)
            throw new IllegalArgumentException("Invalid native surface handles or dimensions");
        try {
            int code=(int)surfaceRecord.invokeExact(command,MemorySegment.ofBuffer(frame),width,height,
                lightingImage,reflectionImage,atlasView,surfaceNormalView,surfaceAlbedoView);
            surfaceError=code==0?"":"Surface ray query dispatch failed (native status "+code+")";
            return code;
        } catch(Throwable failed) {surfaceError=failed.toString();return -3;}
    }

    public boolean actorsAvailable() {return surfaceAvailable() && surfaceActorsRecord!=null;}
    /** Actor payload is copied during recording; the borrowed GENERAL-layout skin view must
     * remain live until this submission's host fence completes. Legacy modules ignore actors. */
    public int recordSurfaceActors(long command,ByteBuffer frame,int width,int height,long lightingImage,long reflectionImage,
        long atlasView,long surfaceNormalView,long surfaceAlbedoView,ByteBuffer actors,long skin) {
        if(!actorsAvailable())return recordSurface(command,frame,width,height,lightingImage,reflectionImage,atlasView,surfaceNormalView,surfaceAlbedoView);
        if(!frame.isDirect() || frame.remaining()!=320)throw new IllegalArgumentException("Invalid native surface frame ABI");
        if(command==0 || width<1 || height<1 || lightingImage==0 || reflectionImage==0 || lightingImage==reflectionImage
            || atlasView==0 || surfaceNormalView==0 || surfaceAlbedoView==0)throw new IllegalArgumentException("Invalid native surface handles or dimensions");
        if(actors==null || !actors.isDirect() || actors.remaining()!=SURFACE_ACTOR_BYTES)throw new IllegalArgumentException("Invalid surface actor ABI");
        var payload=actors.duplicate().order(java.nio.ByteOrder.nativeOrder());
        int base=payload.position();float count=payload.getFloat(base);
        if(!Float.isFinite(count) || count<0 || count>16 || count!=(int)count || (count>0 && skin==0))throw new IllegalArgumentException("Invalid actor count or skin");
        for(int offset=0;offset<SURFACE_ACTOR_BYTES;offset+=4)
            if(!Float.isFinite(payload.getFloat(base+offset)))throw new IllegalArgumentException("Non-finite actor payload");
        for(int offset=4;offset<16;offset+=4)
            if(payload.getFloat(base+offset)!=0)throw new IllegalArgumentException("Invalid actor header padding");
        try {
            int code=(int)surfaceActorsRecord.invokeExact(command,MemorySegment.ofBuffer(frame),width,height,
                lightingImage,reflectionImage,atlasView,surfaceNormalView,surfaceAlbedoView,MemorySegment.ofBuffer(actors),skin);
            surfaceError=code==0?"":"Surface actor ray query dispatch failed (native status "+code+")";
            return code;
        } catch(Throwable failed) {surfaceError=failed.toString();return -3;}
    }

    public boolean extendedActorsAvailable() {return surfaceAvailable() && surfaceActorsExtendedRecord!=null;}
    /** Actor payload is copied during recording; the borrowed GENERAL-layout skin view must
     * remain live until this submission's host fence completes. Unavailable extended modules reject the call; payloads are never truncated. */
    public int recordSurfaceActorsExtended(long command,ByteBuffer frame,int width,int height,long lightingImage,long reflectionImage,
        long atlasView,long surfaceNormalView,long surfaceAlbedoView,ByteBuffer actors,long skin) {
        if(!extendedActorsAvailable()) {surfaceError="Extended surface actor module unavailable";return -2;}
        if(!frame.isDirect() || frame.remaining()!=320)throw new IllegalArgumentException("Invalid native surface frame ABI");
        if(command==0 || width<1 || height<1 || lightingImage==0 || reflectionImage==0 || lightingImage==reflectionImage
            || atlasView==0 || surfaceNormalView==0 || surfaceAlbedoView==0)throw new IllegalArgumentException("Invalid native surface handles or dimensions");
        if(actors==null || !actors.isDirect() || actors.remaining()!=SURFACE_EXTENDED_ACTOR_BYTES)throw new IllegalArgumentException("Invalid surface actor ABI");
        var payload=actors.duplicate().order(java.nio.ByteOrder.nativeOrder());
        int base=payload.position();float count=payload.getFloat(base);
        if(!Float.isFinite(count) || count<0 || count>64 || count!=(int)count || (count>0 && skin==0))throw new IllegalArgumentException("Invalid actor count or skin");
        for(int offset=0;offset<SURFACE_EXTENDED_ACTOR_BYTES;offset+=4)
            if(!Float.isFinite(payload.getFloat(base+offset)))throw new IllegalArgumentException("Non-finite actor payload");
        for(int offset=4;offset<16;offset+=4)
            if(payload.getFloat(base+offset)!=0)throw new IllegalArgumentException("Invalid actor header padding");
        try {
            int code=(int)surfaceActorsExtendedRecord.invokeExact(command,MemorySegment.ofBuffer(frame),width,height,
                lightingImage,reflectionImage,atlasView,surfaceNormalView,surfaceAlbedoView,MemorySegment.ofBuffer(actors),skin);
            surfaceError=code==0?"":"Surface actor ray query dispatch failed (native status "+code+")";
            return code;
        } catch(Throwable failed) {surfaceError=failed.toString();return -3;}
    }

    private static MethodHandle serialCall(Linker linker,SymbolLookup symbols,String name) {
        return linker.downcallHandle(symbols.find(name).orElseThrow(),FunctionDescriptor.of(JAVA_INT,JAVA_LONG));
    }
    public boolean asyncAvailable() {return available() && asyncBegin!=null;}
    public String asyncError() {return asyncError;}
    private int asyncResult(int code,String operation) {
        if(code<0) {asyncError=operation+" failed (native status "+code+")";error=asyncError;}
        return code;
    }
    private int serialOperation(MethodHandle handle,long serial,String operation) {
        if(!asyncAvailable())return -2;
        try {return asyncResult((int)handle.invokeExact(serial),operation);}
        catch(Throwable failed) {asyncError=error=failed.toString();return -3;}
    }
    public int beginSubmission(long serial) {return serialOperation(asyncBegin,serial,"RT submission lease");}
    public int submissionSubmitted(long serial) {return serialOperation(asyncSubmitted,serial,"RT submission confirmation");}
    /** Call only after the oldest submission's host fence reports completion. */
    public int submissionCompleted(long serial) {return serialOperation(asyncCompleted,serial,"RT submission completion");}
    /** The caller guarantees the recorded command will never be submitted or executed. */
    public int submissionCancel(long serial) {return serialOperation(asyncCancel,serial,"RT submission cancellation");}
    public int publishScene(long token) {return serialOperation(publish,token,"Prepared RT scene publication");}
    public int discardPreparedScene(long token) {return serialOperation(discard,token,"Prepared RT scene discard");}
    private static void validateTriangles(ByteBuffer data,int triangles) {
        if(!data.isDirect() || triangles<1 || triangles>com.koper.greenmarine.client.rt.TriangleScene.MAX_TRIANGLES
            || data.remaining()!=(long)triangles*96)throw new IllegalArgumentException("Invalid native triangle ABI");
    }
    /** Run on the scene worker; join that worker before native detach or host-device destruction. */
    public long prepareScene(ByteBuffer data,int triangles) {
        if(!asyncAvailable()){lastPrepareStatus=-2;lastPrepareError=asyncError;return 0;}
        validateTriangles(data,triangles);
        try {
            long token=(long)prepare.invokeExact(MemorySegment.ofBuffer(data),triangles);
            lastPrepareStatus=(int)prepareStatusHandle.invokeExact();
            var pointer=(MemorySegment)prepareErrorHandle.invokeExact();
            lastPrepareError=pointer.address()==0?"":pointer.reinterpret(16384).getString(0);
            if(lastPrepareStatus<0)error=lastPrepareError;
            return token;
        } catch(Throwable failed) {lastPrepareStatus=-3;lastPrepareError=error=failed.toString();return 0;}
    }
    public int prepareStatus() {return lastPrepareStatus;}
    public String prepareError() {return lastPrepareError;}
    /** Synchronous preparation convenience for tests; rendering uses prepareScene on its worker. */
    public int sceneAsync(ByteBuffer data,int triangles) {
        if(!asyncAvailable())return -2;
        validateTriangles(data,triangles);
        try {return asyncResult((int)sceneAsync.invokeExact(MemorySegment.ofBuffer(data),triangles),"Async RT scene replacement");}
        catch(Throwable failed) {asyncError=error=failed.toString();return -3;}
    }
    public int resetSceneAsync() {
        if(!asyncAvailable())return -2;
        try {return asyncResult((int)resetAsync.invokeExact(),"Async RT scene reset");}
        catch(Throwable failed) {asyncError=error=failed.toString();return -3;}
    }
    /** Counts/nanoseconds: prepare count/ns, publish count/ns, slots, pending, retired, busy,
     * retired bytes, current scene bytes, prepared tokens, idle count/ns, retirement cleanup ns. */
    public long[] asyncStats() {
        if(!asyncAvailable())return new long[14];
        try(var arena=Arena.ofConfined()) {
            var out=arena.allocate(JAVA_LONG,14);
            int code=(int)asyncStatsHandle.invokeExact(out,14);
            if(asyncResult(code,"Async RT statistics")!=0)return new long[14];
            return out.toArray(JAVA_LONG);
        } catch(Throwable failed) {asyncError=error=failed.toString();return new long[14];}
    }

    public static final int MAX_EXTERNAL_INSTANCES=512, MAX_EXTERNAL_MESH_TRIANGLES=262_144;
    public boolean externalAvailable() {return surfaceAvailable() && meshPrepare!=null && prepareStatusHandle!=null;}
    /** Any thread; uploads mesh-local triangles (scene ABI) and records a BLAS build for later. */
    public MeshResult prepareMesh(ByteBuffer data,int triangles) {
        if(!externalAvailable())return new MeshResult(0,-2,"External geometry native module is unavailable");
        if(!data.isDirect() || triangles<1 || triangles>MAX_EXTERNAL_MESH_TRIANGLES || data.remaining()!=(long)triangles*96)
            throw new IllegalArgumentException("Invalid external mesh ABI");
        try {
            long token=(long)meshPrepare.invokeExact(MemorySegment.ofBuffer(data),triangles);
            int status=(int)prepareStatusHandle.invokeExact();
            var pointer=(MemorySegment)prepareErrorHandle.invokeExact();
            return new MeshResult(token,status,pointer.address()==0?"":pointer.reinterpret(16384).getString(0));
        } catch(Throwable failed) {return new MeshResult(0,-3,failed.toString());}
    }
    /** In-flight recordings keep their own references; release only forgets the token. */
    public int releaseMesh(long token) {
        if(meshRelease==null)return -2;
        try {return (int)meshRelease.invokeExact(token);} catch(Throwable failed) {return -3;}
    }
    /** Render thread. Row-major 3x4 transforms relative to the published scene origin; -23 names a stale token. */
    public int externalFrame(long[] tokens,float[] transforms,int count) {
        if(externalFrame==null)return -2;
        if(count<0 || count>MAX_EXTERNAL_INSTANCES || tokens.length<count || transforms.length<count*12)
            throw new IllegalArgumentException("Invalid external frame");
        try(var arena=Arena.ofConfined()) {
            var ids=arena.allocate(JAVA_LONG,Math.max(1,count));var matrices=arena.allocate(JAVA_FLOAT,Math.max(1,count*12));
            MemorySegment.copy(tokens,0,ids,JAVA_LONG,0,count);MemorySegment.copy(transforms,0,matrices,JAVA_FLOAT,0,count*12);
            return (int)externalFrame.invokeExact(ids,matrices,count);
        } catch(Throwable failed) {surfaceError=failed.toString();return -3;}
    }
    /** Registered meshes, their bytes, instances queued for the next recording. */
    public long[] externalStats() {
        if(externalStats==null)return new long[3];
        try(var arena=Arena.ofConfined()) {
            var out=arena.allocate(JAVA_LONG,3);
            return (int)externalStats.invokeExact(out,3)==0?out.toArray(JAVA_LONG):new long[3];
        } catch(Throwable failed) {return new long[3];}
    }
}
