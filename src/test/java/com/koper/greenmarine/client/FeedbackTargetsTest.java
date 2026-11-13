package com.koper.greenmarine.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackTargetsTest {
    @Test void feedbackReadsPreviousVersionAndPublishesAllOutputsOnlyAfterCommit() throws Exception {
        var type=Class.forName("com.koper.greenmarine.client.FeedbackTargets");
        var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);var state=ctor.newInstance();
        var define=type.getDeclaredMethod("define",String.class,Object.class,Object.class);define.setAccessible(true);
        var read=type.getDeclaredMethod("read",String.class);read.setAccessible(true);
        var write=type.getDeclaredMethod("write",String.class);write.setAccessible(true);
        var commit=type.getDeclaredMethod("commit",java.util.Collection.class);commit.setAccessible(true);
        define.invoke(state,"A","A0","A1");define.invoke(state,"B","B0","B1");
        assertEquals("A0",read.invoke(state,"A"));assertEquals("A1",write.invoke(state,"A"));
        commit.invoke(state,List.of("A","B"));
        assertEquals("A1",read.invoke(state,"A"));assertEquals("B1",read.invoke(state,"B"));
        assertEquals("A0",write.invoke(state,"A"));
        commit.invoke(state,List.of("A"));assertEquals("A0",read.invoke(state,"A"));assertEquals("B1",read.invoke(state,"B"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->commit.invoke(state,List.of("A","Missing")));
        assertEquals("A0",read.invoke(state,"A"));assertEquals("B1",read.invoke(state,"B"));
    }
}
