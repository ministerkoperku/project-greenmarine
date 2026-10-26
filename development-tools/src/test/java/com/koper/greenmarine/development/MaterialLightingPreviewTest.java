package com.koper.greenmarine.development;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialLightingPreviewTest {
    @Test void emissionAndReflectionChangeTheIllustrativeBlockPreview() {
        int[] dark=MaterialLightingPreview.render(64,new MaterialCanvas.Material("matte",.8f,0,0));
        int[] light=MaterialLightingPreview.render(64,new MaterialCanvas.Material("matte",.8f,0,1));
        int sumDark=0,sumLight=0;
        for(int i=0;i<dark.length;i++){sumDark+=dark[i]&255;sumLight+=light[i]&255;}
        assertTrue(sumLight>sumDark);
        assertFalse(java.util.Arrays.equals(dark,MaterialLightingPreview.render(64,new MaterialCanvas.Material("metal",.05f,1,0))));
    }
}
