package com.koper.greenmarine.client.rt;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.List;
import java.lang.reflect.Method;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

class PlayerReflectionKAvatarTest {
    private static final Path ROOT=Path.of(System.getenv().getOrDefault("GREENMARINE_KAVATAR_FIXTURES","/root/mods-src/kavatar"));
    private static URLClassLoader loader() throws Exception {
        Path avatar=ROOT.resolve("build/libs/kavatar-0.3.0-alpha.jar");
        Path kodel=ROOT.resolveSibling("koperlib").resolve("build/libs/koperlib-kodel-0.1.2-alpha.jar");
        Assumptions.assumeTrue(Files.isRegularFile(avatar)&&Files.isRegularFile(kodel),"Optional real KAvatar/Kodel fixture jars unavailable");
        return new URLClassLoader(new java.net.URL[]{avatar.toUri().toURL(),kodel.toUri().toURL()},PlayerReflectionKAvatarTest.class.getClassLoader());
    }
    private static Object importedPart(ClassLoader loader,String name) throws Exception {
        var repository=loader.loadClass("com.koper.kavatar.client.AvatarRepository");
        var bundle=repository.getMethod("load",String.class).invoke(repository.getConstructor(Path.class).newInstance(ROOT.resolve("src/main/resources/assets/kavatar/examples")),name);
        var importer=loader.loadClass("com.koper.kavatar.asset.AvatarImporter");
        var imported=importer.getMethod("load",bundle.getClass()).invoke(null,bundle);
        Object part=((List<?>)imported.getClass().getMethod("parts").invoke(imported)).getFirst();
        return part;
    }
    private static Object fixture(ClassLoader loader,String name,boolean slim) throws Exception {
        var part=importedPart(loader,name);return part.getClass().getMethod("model",boolean.class).invoke(part,slim);
    }
    private static float[] rest(ClassLoader loader,Object model) throws Exception {
        for(Method method:loader.loadClass("com.koper.kavatar.client.render.AvatarPose").getMethods())
            if(method.getName().equals("sample")&&method.getParameterCount()==7) return (float[])method.invoke(null,model,null,0f,null,0f,1f,null);
        throw new AssertionError("Real pose sampler missing");
    }

    @Test void layerPlacementMatchesCustomBodyAndCustomBoneAccessoryRoots() {
        var root=new Matrix4f().translation(2,3,4);
        var body=PlayerReflectionKAvatar.rawFrame(root,true,null,null,new Matrix4f().translation(0,8,0).scale(.5f));
        var point=body.transformPosition(new Vector3f(16,0,0));
        assertEquals(1.5f,point.x,1e-5);assertEquals(4,point.y,1e-5);assertEquals(4,point.z,1e-5);
        var accessory=PlayerReflectionKAvatar.rawFrame(root,false,new Matrix4f().translation(16,0,0),null,new Matrix4f());
        point=accessory.transformPosition(new Vector3f());
        assertEquals(1,point.x,1e-5);assertEquals(4.5f,point.y,1e-5);
        var limb=new net.minecraft.client.model.geom.ModelPart(List.of(),java.util.Map.of());limb.x=8;
        point=PlayerReflectionKAvatar.rawFrame(root,false,null,limb,new Matrix4f()).transformPosition(new Vector3f());
        assertEquals(2.5f,point.x,1e-5);assertEquals(3,point.y,1e-5);
    }
    @Test void actualHyperChibiAndBabyFixturesFitCompleteBodiesWithActualPosedMatrices() throws Exception {
        try(var loader=loader()) {
            for(String name:List.of("hyper_chibi","baby_chibi")) for(boolean slim:new boolean[]{false,true}) {
                Object model=fixture(loader,name,slim);float[] matrices=rest(loader,model);
                var result=PlayerReflectionKAvatar.captureModel(model,matrices,new Matrix4f().translation(2,3,4).scale(1f/16));
                assertNull(result.error());assertEquals(name.equals("hyper_chibi")?14:12,result.geometry().count());
                assertEquals(4624,result.geometry().data().remaining());
                // The native inverse must undo both scene placement and a real sampled bone transform.
                var first=((List<?>)model.getClass().getField("bones").get(model)).getFirst();
                var cube=((List<?>)first.getClass().getField("cubes").get(first)).getFirst();
                float[] raw=(float[])cube.getClass().getField("origin").get(cube);
                var world=new Matrix4f().translation(2,3,4).scale(1f/16).mul(new Matrix4f().set(matrices,0)).transformPosition(new Vector3f(raw));
                var recovered=new Matrix4f().set(16,result.geometry().data()).transformPosition(world);
                assertEquals(raw[0],recovered.x,1e-4);assertEquals(raw[1],recovered.y,1e-4);assertEquals(raw[2],recovered.z,1e-4);
            }
        }
    }
    @Test void exactUvsAndInflatedBoundsMatchRealKodelRendererForEveryCubeFace() throws Exception {
        try(var loader=loader()) {
            for(boolean varied:new boolean[]{false,true}) {
            Object model=fixture(loader,"hyper_chibi",false);
            if(varied)for(var bone:(List<?>)model.getClass().getField("bones").get(model))for(var cube:(List<?>)bone.getClass().getField("cubes").get(bone)) {
                cube.getClass().getField("inflate").setFloat(cube,.35f);
                int faceIndex=0;
                for(var face:(Object[])cube.getClass().getField("faces").get(cube)) {
                    face.getClass().getField("rot").setInt(face,faceIndex++%4);face.getClass().getField("mirror").setBoolean(face,true);
                }
            }
            var renderer=loader.loadClass("com.koper.koper_lib.kodel.KodelModelRender");
            float[] baked=(float[])renderer.getMethod("bakeSkinned",model.getClass()).invoke(null,model);
            float[] matrices=rest(loader,model);
            var result=PlayerReflectionKAvatar.captureModel(model,matrices,new Matrix4f().scale(1f/16));assertNull(result.error());
            var data=result.geometry().data();assertEquals(result.geometry().count()*24*9,baked.length);
            for(int box=0;box<result.geometry().count();box++) {
                var inverse=new Matrix4f().set(16+box*288,data);
                for(int vertex=0;vertex<24;vertex++) {
                    int base=(box*24+vertex)*9;
                    int bone=(int)baked[base+8];
                    var world=new Matrix4f().scaling(1f/16).mul(new Matrix4f().set(matrices,bone*16))
                        .transformPosition(new Vector3f(baked[base]*16,baked[base+1]*16,baked[base+2]*16));
                    var raw=inverse.transformPosition(world);
                    int face=-1;
                    for(int axis=0;axis<3;axis++) {
                        float coordinate=raw.get(axis),normal=baked[base+5+axis];
                        if(Math.abs(normal)>.5f)face=axis*2+(normal>0?1:0);
                        assertTrue(coordinate>=data.getFloat(16+box*288+64+axis*4)-1e-4);
                        assertTrue(coordinate<=data.getFloat(16+box*288+80+axis*4)+1e-4);
                    }
                    var point=new Vector4f(raw,1);
                    assertEquals(baked[base+3],new Vector4f().set(16+box*288+96+face*16,data).dot(point),1e-4);
                    assertEquals(baked[base+4],new Vector4f().set(16+box*288+192+face*16,data).dot(point),1e-4);
                }
            }
            }
        }
    }
    @Test void samplesRealCustomWalkAnimationInsteadOfAVanillaOrRestPose() throws Exception {
        try(var loader=loader()) {
            var part=importedPart(loader,"hyper_chibi");var model=part.getClass().getMethod("model",boolean.class).invoke(part,false);
            var clips=(java.util.Map<?,?>)part.getClass().getMethod("clips").invoke(part);var clip=clips.get("chibi.walk");assertNotNull(clip);
            var tracksClass=loader.loadClass("com.koper.koper_lib.kodel.KodelSampler$ResolvedTracks");
            var tracks=tracksClass.getMethod("of",model.getClass(),clip.getClass()).invoke(null,model,clip);
            Method sample=null;
            for(var method:loader.loadClass("com.koper.kavatar.client.render.AvatarPose").getMethods())if(method.getName().equals("sample")&&method.getParameterCount()==7)sample=method;
            assertNotNull(sample);
            float[] moving=(float[])sample.invoke(null,model,null,0f,tracks,.4f,1f,null);
            var rest=PlayerReflectionKAvatar.captureModel(model,rest(loader,model),new Matrix4f().scale(1f/16));
            var walk=PlayerReflectionKAvatar.captureModel(model,moving,new Matrix4f().scale(1f/16));
            assertNull(walk.error());assertEquals(14,walk.geometry().count());assertNotEquals(rest.geometry().data(),walk.geometry().data());
        }
    }
    @Test void absentOptionalModLeavesVanillaCaptureAvailable() {
        assertDoesNotThrow(()->assertNull(PlayerReflectionKAvatar.capture(null,new net.minecraft.client.renderer.entity.state.AvatarRenderState(),null,new com.mojang.blaze3d.vertex.PoseStack())));
    }
    @Test void resolvesActualOptionalPublicApiWithoutAddingItToBaseClasspath() throws Exception {
        try(var fixtures=loader()) {
            var urls=new java.util.ArrayList<java.net.URL>();urls.add(PlayerReflectionKAvatar.class.getProtectionDomain().getCodeSource().getLocation());urls.addAll(List.of(fixtures.getURLs()));
            // KAvatar itself requires Fabric API. Keep those real dependencies isolated from base tests.
            Path fabric=Path.of(System.getProperty("user.home"),".gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api");
            Assumptions.assumeTrue(Files.isDirectory(fabric),"Optional KAvatar runtime dependency fixtures unavailable");
            try(var jars=Files.walk(fabric)) {for(var jar:jars.filter(p->p.toString().endsWith(".jar")&&!p.toString().endsWith("-sources.jar")).sorted().toList())urls.add(jar.toUri().toURL());}
            try(var isolated=new URLClassLoader(urls.toArray(java.net.URL[]::new),PlayerReflectionKAvatarTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                    if(name.startsWith("com.koper.greenmarine.client.rt.PlayerReflectionKAvatar")) {
                        synchronized(getClassLoadingLock(name)) {Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}
                    }
                    return super.loadClass(name,resolve);
                }
            }) {
                var api=Class.forName("com.koper.greenmarine.client.rt.PlayerReflectionKAvatar$Api",true,isolated);
                var constructor=api.getDeclaredConstructor();constructor.setAccessible(true);assertNotNull(constructor.newInstance());
            }
        }
    }
    @Test void rejectsAnOversizedBodyInsteadOfSilentlyClippingItToSixteen() throws Exception {
        try(var loader=loader()) {
            Object model=fixture(loader,"baby_chibi",false);
            var bones=(List<?>)model.getClass().getField("bones").get(model);
            @SuppressWarnings("unchecked") var cubes=(List<Object>)bones.getFirst().getClass().getField("cubes").get(bones.getFirst());
            for(int i=0;i<5;i++)cubes.add(cubes.getFirst());
            var result=PlayerReflectionKAvatar.captureModel(model,rest(loader,model),new Matrix4f().scale(1f/16));
            assertNotNull(result.error());assertTrue(result.error().contains("17"));assertEquals(0,result.geometry().count());
        }
    }
    @Test void malformedPoseDoesNotPublishHalfOfAPlayer() throws Exception {
        try(var loader=loader()) {
            Object model=fixture(loader,"baby_chibi",false);float[] matrices=rest(loader,model);matrices[16+12]=Float.NaN;
            var result=PlayerReflectionKAvatar.captureModel(model,matrices,new Matrix4f().scale(1f/16));
            assertNotNull(result.error());assertTrue(result.error().toLowerCase().contains("pose"));assertEquals(0,result.geometry().count());
        }
    }
}
