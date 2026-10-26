package com.koper.greenmarine.development;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EditorLayoutTest {
    @Test void editorGroupsStayInsideWindowAndLeaveRoomForCode() {
        for(int[] size:new int[][]{{320,180},{480,270},{960,540},{1920,1080}})
            for(boolean down:new boolean[]{false,true}) {
                var layout=EditorLayout.calculate(size[0],size[1],true,168,true,down,.5);
                for(var pane:new EditorLayout.Rect[]{layout.first(),layout.second()}) {
                    assertTrue(pane.width()>=60);assertTrue(pane.height()>=30);
                    assertTrue(pane.x()>=layout.explorer());assertTrue(pane.right()<=size[0]);
                    assertTrue(pane.y()>=44);assertTrue(pane.bottom()<=size[1]-18);
                }
                assertFalse(layout.first().contains(layout.second().x()+2,layout.second().y()+2));
            }
    }
    @Test void hidingExplorerReturnsItsSpaceToEditorAndResizeIsBounded() {
        var layout=EditorLayout.calculate(480,270,false,1000,false,false,.99);
        assertEquals(0,layout.explorer());assertEquals(480,layout.first().width());
        var resized=EditorLayout.calculate(480,270,true,1000,true,false,.99);
        assertTrue(resized.explorer()<=192);assertTrue(resized.second().width()>=60);
    }
}
