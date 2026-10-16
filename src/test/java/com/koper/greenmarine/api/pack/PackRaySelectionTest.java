package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PackRaySelectionTest {
    private final RenderCapabilities caps=new RenderCapabilities("Vulkan",true,true,true,true);
    private ShaderPack pack(PackPass.Requirement requirement) {
        return new ShaderPack("test:rays","Rays",null,List.of(),List.of(new PackPass("light",PackPass.Stage.SCENE,
            "test:light","test:fallback",requirement,Quality.LOW,1,null)));
    }
    @Test void newHardwareCapabilityDoesNotDisableExistingVoxelOrPortablePacks() {
        for(var requirement:List.of(PackPass.Requirement.VOXEL_RT,PackPass.Requirement.NONE))
            for(var requested:List.of(RayMode.AUTO,RayMode.HARDWARE))
                assertEquals(RayMode.VOXEL,RenderPolicy.rays(requested,Quality.ULTRA,caps,pack(requirement)).effective());
        assertEquals(RayMode.HARDWARE,RenderPolicy.rays(RayMode.AUTO,Quality.ULTRA,caps,pack(PackPass.Requirement.HARDWARE_RT)).effective());
        assertEquals(RayMode.OFF,RenderPolicy.rays(RayMode.AUTO,Quality.LOW,caps,pack(PackPass.Requirement.HARDWARE_RT)).effective());
        assertEquals(RayMode.HARDWARE,RenderPolicy.rays(RayMode.HARDWARE,Quality.ULTRA,caps).effective());
    }
}
