package com.koper.greenmarine.client.iris;

import com.koper.greenmarine.api.render.UniformExpressions;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisFrameInputsTest {
    private final Object world = new Object(), pack = new Object();
    private IrisFrameInputs.Input input(long nanos, boolean paused, double x, Matrix4f view, Map<String,Object> values) {
        return new IrisFrameInputs.Input(world, pack, nanos, paused, 1280, 720, x, 64, -3.25,
            new Matrix4f().perspective(1.2f, 1280f/720, .05f, 256).get(new float[16]),
            view.get(new float[16]), false, false, 0, 180, -35, values);
    }
    @Test void resolvesOnlyRequestedRootsAndReportsMissingIntegrationInputs() {
        var snapshot = new IrisFrameInputs().sample(input(0,false,0,new Matrix4f(),Map.of("eyeBrightness",new int[]{16,240})));
        var values = snapshot.resolve(Set.of("eyeBrightness.y", "viewWidth"));
        assertEquals(Set.of("eyeBrightness","viewWidth"), values.keySet());
        assertArrayEquals(new int[]{16,240}, (int[])values.get("eyeBrightness"));
        assertInstanceOf(Float.class, values.get("viewWidth"));
        assertEquals(Set.of("dhNearPlane","dhFarPlane","dhRenderDistance"),
            snapshot.unsupported(Set.of("dhNearPlane","dhFarPlane","dhRenderDistance","eyeBrightness.y")));
        assertThrows(IllegalArgumentException.class, () -> snapshot.resolve(Set.of("dhNearPlane")));
        assertThrows(IllegalArgumentException.class, () -> snapshot.resolve(Set.of("eyeBrightness.z")));
    }
    @Test void inverseMatricesUseColumnMajorAndFeedRealExpressionComponents() {
        var view = new Matrix4f().rotateX(.6f).rotateY(-.3f);
        var snapshot = new IrisFrameInputs().sample(input(0,false,3,view,Map.of()));
        var expected = new Matrix4f(view).invert().get(new float[16]);
        assertArrayEquals(expected, (float[])snapshot.resolve(Set.of("gbufferModelViewInverse")).get("gbufferModelViewInverse"),1e-6f);
        var program = UniformExpressions.compile(List.of(new UniformExpressions.Declaration(
            UniformExpressions.Kind.UNIFORM,UniformExpressions.Type.FLOAT,"element","gbufferModelViewInverse.1.2")));
        assertEquals(expected[6], (Float)program.evaluate(snapshot.resolve(program.requiredInputs()),new UniformExpressions.State(),0).uniforms().get("element"),1e-6f);
    }
    @Test void layoutSourcesResolveExactTypedComponents() {
        var snapshot=new IrisFrameInputs().sample(input(0,false,0,new Matrix4f(),Map.of("eyeBrightness",new int[]{16,240})));
        var values=snapshot.resolveValues(Set.of("eyeBrightness.y","gbufferModelViewInverse.1.1","viewWidth"));
        assertEquals(Set.of("eyeBrightness.y","gbufferModelViewInverse.1.1","viewWidth"),values.keySet());
        assertEquals(240,values.get("eyeBrightness.y"));assertEquals(1f,values.get("gbufferModelViewInverse.1.1"));
        assertThrows(IllegalArgumentException.class,()->snapshot.resolveValues(Set.of("eyeBrightness.z")));
    }
    @Test void previousMatricesAdvanceOncePerSampleAndFirstFrameStartsWithIdentity() {
        var provider = new IrisFrameInputs();
        var a = new Matrix4f().rotateY(.5f); var b = new Matrix4f().rotateX(.2f);
        var first = provider.sample(input(0,false,4,a,Map.of()));
        assertArrayEquals(new Matrix4f().get(new float[16]), (float[])first.resolve(Set.of("gbufferPreviousModelView")).get("gbufferPreviousModelView"));
        var second = provider.sample(input(20_000_000,false,5,b,Map.of()));
        assertArrayEquals(a.get(new float[16]), (float[])second.resolve(Set.of("gbufferPreviousModelView")).get("gbufferPreviousModelView"));
        assertArrayEquals(new float[]{4,64,-3.25f},(float[])second.resolve(Set.of("previousCameraPosition")).get("previousCameraPosition"));
        assertArrayEquals(a.get(new float[16]), (float[])second.resolve(Set.of("gbufferPreviousModelView")).get("gbufferPreviousModelView"));
    }
    @Test void realFrameTimeContinuesWhilePausedButExpressionDeltaStops() {
        var provider = new IrisFrameInputs();
        assertEquals(0,provider.sample(input(1_000_000,false,0,new Matrix4f(),Map.of())).expressionDeltaSeconds());
        var paused = provider.sample(input(25_900_000,true,0,new Matrix4f(),Map.of()));
        assertEquals(.024f, paused.resolve(Set.of("frameTime")).get("frameTime"));
        assertEquals(0,paused.expressionDeltaSeconds());
        var resumed = provider.sample(input(40_900_000,false,0,new Matrix4f(),Map.of()));
        assertEquals(.015,resumed.expressionDeltaSeconds(),1e-7);
        assertEquals(3,resumed.resolve(Set.of("frameCounter")).get("frameCounter"));
        assertEquals(.039f,(Float)resumed.resolve(Set.of("frameTimeCounter")).get("frameTimeCounter"),1e-6f);
    }
    @Test void worldAndPackIdentityChangesResetTemporalState() {
        var provider = new IrisFrameInputs();
        provider.sample(input(0,false,20,new Matrix4f().rotateY(.3f),Map.of()));
        var original = input(30_000_000,false,21,new Matrix4f(),Map.of());
        var switched = new IrisFrameInputs.Input(new Object(),pack,original.frameStartNanos(),original.paused(),1280,720,
            21,64,-3.25,original.projection(),original.modelView(),false,false,0,180,-35,Map.of());
        var sample = provider.sample(switched);
        assertEquals(1,sample.resolve(Set.of("frameCounter")).get("frameCounter"));
        assertEquals(0,sample.expressionDeltaSeconds());
        provider.reset();
        assertEquals(1,provider.sample(original).resolve(Set.of("frameCounter")).get("frameCounter"));
    }
    @Test void cameraShiftPreservesMotionAndUsesFloorForNegativeFractionalCoordinates() {
        var provider = new IrisFrameInputs();
        provider.sample(input(0,false,29999,new Matrix4f(),Map.of()));
        var values=provider.sample(input(1,false,30001,new Matrix4f(),Map.of())).resolve(Set.of("cameraPosition","previousCameraPosition","cameraPositionInt","cameraPositionFract"));
        assertArrayEquals(new float[]{1,64,-3.25f},(float[])values.get("cameraPosition"));
        assertArrayEquals(new float[]{-1,64,-3.25f},(float[])values.get("previousCameraPosition"));
        assertArrayEquals(new int[]{30001,64,-4},(int[])values.get("cameraPositionInt"));
        assertArrayEquals(new float[]{0,0,.75f},(float[])values.get("cameraPositionFract"));
    }
    @Test void snapshotsAndInputsDefensivelyCopyMutableArrays() {
        var light=new int[]{16,240};var input=input(0,false,0,new Matrix4f(),Map.of("eyeBrightness",light));
        light[1]=0; input.projection()[0]=100;
        var sample=new IrisFrameInputs().sample(input);
        ((int[])sample.resolve(Set.of("eyeBrightness")).get("eyeBrightness"))[1]=0;
        assertArrayEquals(new int[]{16,240},(int[])sample.resolve(Set.of("eyeBrightness")).get("eyeBrightness"));
        assertThrows(UnsupportedOperationException.class,()->sample.resolve(Set.of("viewWidth")).put("viewWidth",0));
    }
    @Test void zeroToOneHostProjectionIsConvertedToIrisClipDepth() {
        var projection=new Matrix4f().perspective(1.2f,1,.05f,256,true);
        var input=input(0,false,0,new Matrix4f(),Map.of());
        input=new IrisFrameInputs.Input(world,pack,0,false,1280,720,0,64,0,projection.get(new float[16]),input.modelView(),true,false,0,180,0,Map.of());
        var values=new IrisFrameInputs().sample(input).resolve(Set.of("gbufferProjection","gbufferProjectionInverse"));
        assertArrayEquals(new Matrix4f().perspective(1.2f,1,.05f,256,false).get(new float[16]),(float[])values.get("gbufferProjection"),1e-6f);
        assertTrue(new Matrix4f().set((float[])values.get("gbufferProjection")).mul(new Matrix4f().set((float[])values.get("gbufferProjectionInverse"))).equals(new Matrix4f(),1e-4f));
    }
    @Test void reversedHostDepthConvertsNearToMinusOneAndFarToPlusOne() {
        for(boolean zeroToOne : List.of(false,true)) {
            var host=new Matrix4f().perspective(1.2f,1,256,.05f,zeroToOne);
            var sample=new IrisFrameInputs().sample(new IrisFrameInputs.Input(world,pack,0,false,1280,720,0,64,0,host.get(new float[16]),new Matrix4f().get(new float[16]),zeroToOne,true,0,180,0,Map.of()));
            var iris=new Matrix4f().set((float[])sample.resolve(Set.of("gbufferProjection")).get("gbufferProjection"));
            assertEquals(-1,iris.transformProject(new org.joml.Vector3f(0,0,-.05f)).z,1e-5f);
            assertEquals(1,iris.transformProject(new org.joml.Vector3f(0,0,-256)).z,1e-5f);
            assertArrayEquals(host.get(new float[16]),sample.rawHostProjection());
        }
    }
    @Test void celestialVectorsFollowIrisOrderedRotationsAndUsePackSunPath() {
        var input=input(0,false,0,new Matrix4f(),Map.of());
        var values=new IrisFrameInputs().sample(input).resolve(Set.of("sunPosition","moonPosition","sunAngle","upPosition"));
        // angle=0 is noon; -35 degree pack path tilts towards +Z after Y(-90).
        assertArrayEquals(new float[]{0,81.9152f,57.3576f},(float[])values.get("sunPosition"),1e-3f);
        assertArrayEquals(new float[]{0,-81.9152f,-57.3576f},(float[])values.get("moonPosition"),1e-3f);
        assertArrayEquals(new float[]{0,100,0},(float[])values.get("upPosition"),1e-4f);
        assertEquals(.25f,values.get("sunAngle"));
    }
    @Test void playerVitalsUseSurvivalRatiosAndCreativeSentinel() {
        var values=IrisFrameInputs.playerVitals(true,7,28,75,300);
        assertEquals(.25f,values.get("currentPlayerHealth"));assertEquals(.25f,values.get("currentPlayerAir"));
        assertEquals(28f,values.get("maxPlayerHealth"));
        var creative=IrisFrameInputs.playerVitals(false,20,20,300,300);
        assertEquals(-1f,creative.get("currentPlayerHealth"));assertEquals(-1f,creative.get("currentPlayerAir"));assertEquals(-1f,creative.get("maxPlayerHealth"));
    }
    @Test void biomeConstantsMatchMinecraft263DeclarationOrderAndIrisCategoryPriority() {
        assertEquals(12,IrisFrameInputs.biomeId("minecraft:dark_forest"));
        assertEquals(13,IrisFrameInputs.biomeId("minecraft:pale_garden"));
        assertEquals(0,IrisFrameInputs.biomeId("other:unmapped_biome"));
        assertEquals(7,IrisFrameInputs.biomeCategory(Set.of("HAS_VILLAGE_SNOWY","IS_TAIGA")));
        assertEquals(10,IrisFrameInputs.biomeCategory(Set.of("IS_FOREST","ALLOWS_SURFACE_SLIME_SPAWNS")));
        assertEquals(14,IrisFrameInputs.biomeCategory(Set.of("ALLOWS_SURFACE_SLIME_SPAWNS")));
        var constants=new IrisFrameInputs().sample(input(0,false,0,new Matrix4f(),Map.of())).resolve(Set.of("BIOME_DARK_FOREST","CAT_MESA","CAT_DESERT"));
        assertEquals(12,constants.get("BIOME_DARK_FOREST"));assertEquals(4,constants.get("CAT_MESA"));assertEquals(12,constants.get("CAT_DESERT"));
    }
    @Test void everyBiomeIdentifierMatchesTheActualMinecraftRegistrationOrder() throws Exception {
        var bytecode=new ClassNode();
        try(var in=getClass().getResourceAsStream("/net/minecraft/world/level/biome/Biomes.class")) {
            assertNotNull(in);new ClassReader(in).accept(bytecode,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        int count=0;
        for(var method:bytecode.methods)if(method.name.equals("<clinit>"))
            for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("register")) {
                assertInstanceOf(LdcInsnNode.class,call.getPrevious());
                String name=(String)((LdcInsnNode)call.getPrevious()).cst;
                assertEquals(count++,IrisFrameInputs.biomeId("minecraft:"+name),name);
            }
        assertEquals(67,count,"Minecraft's biome declarations changed: revalidate Iris's numeric constants");
    }
    @Test void worldTimeUsesIrisDivisionRemainderAndDimensionFixedTimeRules() {
        assertEquals(6000,IrisFrameInputs.worldTime(54000,true,true));
        assertEquals(0,IrisFrameInputs.worldTime(54000,true,false));
        assertEquals(-1,IrisFrameInputs.worldTime(-1,false,false));
    }
    @Test void singularMatricesFailWithoutAdvancingHistory() {
        var provider=new IrisFrameInputs();provider.sample(input(0,false,2,new Matrix4f(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->provider.sample(input(25_000_000,false,3,new Matrix4f().zero(),Map.of())));
        var after=provider.sample(input(50_000_000,false,4,new Matrix4f(),Map.of()));
        assertEquals(2,after.resolveValues(Set.of("frameCounter")).get("frameCounter"));
        assertEquals(.05f,after.resolveValues(Set.of("frameTime")).get("frameTime"));
        assertArrayEquals(new float[]{2,64,-3.25f},(float[])after.resolveValues(Set.of("previousCameraPosition")).get("previousCameraPosition"));
    }
}
