package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackPassHistoryTest {
    @Test void existingConstructorsKeepHistoryDisabled() {
        assertFalse(new PackPass("grade",PackPass.Stage.SCENE,"demo:grade",null,PackPass.Requirement.NONE,Quality.LOW,1,null).history());
        assertFalse(new PackPass("grade",PackPass.Stage.SCENE,"demo:grade",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.RGBA16F).history());
    }
    @Test void historyIsSceneOnly() {
        assertTrue(new PackPass("resolve",PackPass.Stage.SCENE,"demo:resolve",null,PackPass.Requirement.NONE,Quality.LOW,.5f,null,PackPass.Format.RGBA16F,true).history());
        assertThrows(IllegalArgumentException.class,()->new PackPass("lightmap",PackPass.Stage.LIGHTMAP,"demo:lightmap",null,PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.SCENE,true));
    }
}
