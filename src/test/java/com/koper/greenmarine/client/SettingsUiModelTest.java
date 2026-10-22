package com.koper.greenmarine.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsUiModelTest {
    @Test void narrowAndShortLayoutsKeepRowsAndNavigationInsideViewport() {
        for(int width:new int[]{120,240,854}) for(int height:new int[]{160,240,480}) {
            var layout=SettingsUiModel.layout(width,height);
            assertTrue(layout.x()>=0);
            assertTrue(layout.x()+layout.width()<=width);
            assertTrue(layout.rows()>=1);
            assertTrue(layout.bodyY()+layout.rows()*layout.rowPitch()<=layout.navigationY());
            assertTrue(layout.doneY()+layout.buttonHeight()<=height);
            assertTrue(layout.buttonHeight()>=24);
        }
    }
    @Test void paginationClampsStalePagesAndKeepsEveryChoiceReachable() {
        for(int count:new int[]{0,1,4,99}) for(int capacity:new int[]{1,3,8}) {
            assertEquals(0,SettingsUiModel.page(-5,count,capacity).index());
            var last=SettingsUiModel.page(999,count,capacity);
            assertEquals(count,last.end());
            int visited=0;
            for(int index=0;index<last.pages();index++) {
                var page=SettingsUiModel.page(index,count,capacity);
                assertEquals(visited,page.start());visited=page.end();
            }
            assertEquals(count,visited);
        }
    }
    @Test void packSelectionUsesStableIdsAndRetainsMissingCurrentSelection() {
        var packs=List.of(new SettingsUiModel.PackChoice("a:one","Shared name"),new SettingsUiModel.PackChoice("b:two","Shared name"));
        var choices=SettingsUiModel.packChoices(packs,"gone:pack");
        assertEquals("greenmarine:none",choices.getFirst().id());
        assertTrue(choices.stream().anyMatch(p->p.id().equals("gone:pack")));
        assertEquals(4,choices.size());
        assertEquals(3,SettingsUiModel.packChoices(packs,"a:one").size());
    }
    @Test void parameterLabelsSeparateCamelCaseAndUnderscores() {
        assertEquals("Water Wave Speed",SettingsUiModel.friendlyName("waterWave_speed"));
        assertEquals("GI Strength",SettingsUiModel.friendlyName("GI_strength"));
    }
    @Test void timingSnapshotsAreReusedForHalfASecond() {
        var calls=new AtomicInteger();
        var cache=new SettingsUiModel.TimedCache<Integer>(500_000_000L, calls::incrementAndGet);
        assertEquals(1,cache.get(0));assertEquals(1,cache.get(499_999_999));
        assertEquals(2,cache.get(500_000_000));
        assertEquals(2,calls.get());
    }
}
