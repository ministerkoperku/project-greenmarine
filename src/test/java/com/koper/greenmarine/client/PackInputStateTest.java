package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.UniformExpressions;
import com.koper.greenmarine.client.iris.IrisFrameInputs;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackInputStateTest {
    private static final Object WORLD=new Object(),PACK=new Object();
    private static UniformExpressions.Declaration expression(String name,String value) {
        return new UniformExpressions.Declaration(UniformExpressions.Kind.UNIFORM,UniformExpressions.Type.FLOAT,name,value);
    }
    private static IrisFrameInputs.Snapshot sample(IrisFrameInputs frames,Object world,long nanos,boolean paused,float target) {
        return frames.sample(new IrisFrameInputs.Input(world,PACK,nanos,paused,1000,500,1,70,2,
            new Matrix4f().setPerspective(1,2,1000,.05f,true).get(new float[16]),new Matrix4f().get(new float[16]),true,true,
            45,225,0,Map.of("target",target,"eyeBrightness",new int[]{32,240})));
    }
    @Test void selectedExpressionsMergeConstantsAndExactHostSourcesWithoutUnusedDhDependencies() {
        var inputs=new PackInputs(Map.of("SEA_LEVEL",62,"flags",new boolean[]{true,false}),
            List.of(expression("height","eyeAltitude - SEA_LEVEL"),expression("optional","dhFarPlane")),0);
        var state=new PackInputState();var frame=sample(new IrisFrameInputs(),WORLD,0,false,0);
        var result=state.resolve(inputs,WORLD,PACK,frame,Set.of("height","eyeBrightness.y","gbufferProjection","flags.x"));
        assertEquals(8f,result.get("height"));assertEquals(240,result.get("eyeBrightness.y"));assertEquals(true,result.get("flags.x"));
        assertEquals(16,((float[])result.get("gbufferProjection")).length);
        assertEquals(4,result.size());
        assertThrows(IllegalArgumentException.class,()->new PackInputState().resolve(inputs,WORLD,PACK,frame,Set.of("optional")));
    }
    @Test void sameFrameIsMemoizedAndWorldChangeResetsSmoothing() {
        var inputs=new PackInputs(Map.of(),List.of(expression("fade","smooth(target,1.0,1.0)")),0);
        var state=new PackInputState();var frames=new IrisFrameInputs();
        state.resolve(inputs,WORLD,PACK,sample(frames,WORLD,0,false,0),Set.of("fade"));
        var frame=sample(frames,WORLD,100_000_000,false,1);
        assertEquals(.5f,(Float)state.resolve(inputs,WORLD,PACK,frame,Set.of("fade")).get("fade"),1e-6f);
        assertEquals(.5f,(Float)state.resolve(inputs,WORLD,PACK,frame,Set.of("fade")).get("fade"),1e-6f);
        assertThrows(IllegalStateException.class,()->state.resolve(inputs,WORLD,PACK,frame,Set.of("fade","eyeAltitude")));
        Object nextWorld=new Object();
        assertEquals(1f,state.resolve(inputs,nextWorld,PACK,sample(frames,nextWorld,200_000_000,false,1),Set.of("fade")).get("fade"));
        state.reset();assertEquals(0f,state.resolve(inputs,nextWorld,PACK,sample(frames,nextWorld,300_000_000,false,0),Set.of("fade")).get("fade"));
    }
    @Test void pauseAndInvalidReferencesDoNotAdvanceSmoothing() {
        var inputs=new PackInputs(Map.of(),List.of(expression("fade","smooth(target,1.0,1.0)")),0);
        var state=new PackInputState();var frames=new IrisFrameInputs();
        state.resolve(inputs,WORLD,PACK,sample(frames,WORLD,0,false,0),Set.of("fade"));
        assertThrows(IllegalArgumentException.class,()->state.resolve(inputs,WORLD,PACK,sample(frames,WORLD,100_000_000,false,1),Set.of("fade.bad")));
        assertEquals(.5f,(Float)state.resolve(inputs,WORLD,PACK,sample(frames,WORLD,200_000_000,false,1),Set.of("fade")).get("fade"),1e-6f);
        assertEquals(.5f,(Float)state.resolve(inputs,WORLD,PACK,sample(frames,WORLD,300_000_000,true,0),Set.of("fade")).get("fade"),1e-6f);
        assertEquals(.25f,(Float)state.resolve(inputs,WORLD,PACK,sample(frames,WORLD,400_000_000,false,0),Set.of("fade")).get("fade"),1e-6f);
    }
    @Test void returnedConstantsAndCachedFramesAreDefensiveAndPreserveIntegerPrecision() {
        var input=new PackInputs(Map.of("weights",new float[]{1,2},"limit",new BigDecimal("4294967295")),List.of(),0);
        var state=new PackInputState();var frame=sample(new IrisFrameInputs(),WORLD,0,false,0);var sources=Set.of("weights","limit");
        var first=state.resolve(input,WORLD,PACK,frame,sources);((float[])first.get("weights"))[0]=99;
        var next=state.resolve(input,WORLD,PACK,frame,sources);assertArrayEquals(new float[]{1,2},(float[])next.get("weights"));
        var block=new PackUniformBlock(List.of(new PackUniformBlock.Member("value","uint","limit")));
        assertEquals(-1,block.pack(next).getInt(0));assertThrows(UnsupportedOperationException.class,()->next.clear());
    }
}
