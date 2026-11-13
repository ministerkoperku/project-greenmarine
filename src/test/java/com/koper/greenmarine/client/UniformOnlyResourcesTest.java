package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UniformOnlyResourcesTest {
    @org.junit.jupiter.api.Test void screenDispatchFollowsTheViewportWithoutOwnedStorageImages() {
        var reflection=new ComputeBackend.Reflection(java.util.List.of(),8,8,1);
        PackCompute.prepare(com.koper.greenmarine.api.pack.PackResources.NONE,null,801,601);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{101,76,1},PackCompute.groups("screen",reflection));
        PackCompute.prepare(com.koper.greenmarine.api.pack.PackResources.NONE,null,320,180);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{40,23,1},PackCompute.groups("screen",reflection));
    }
    @Test void rasterUniformsDoNotRequireAComputeDeviceOrStorageEncoder() {
        var uniform=new PackUniformBlock(List.of(new PackUniformBlock.Member("width","float","viewWidth")));
        var resources=new PackResources(Map.of(),Map.of(),Map.of("FrameInputs",uniform));
        assertDoesNotThrow(()->PackCompute.prepare(resources,null,320,180));
    }
}
