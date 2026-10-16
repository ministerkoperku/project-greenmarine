package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenePreparationTest {
    @Test void boundsWorkUntilTheCompletedResultIsConsumed() throws Exception {
        try(var worker=new ScenePreparation<Integer>()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            assertTrue(worker.start(()->{entered.countDown();release.await();return 17;}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertNull(worker.poll());assertFalse(worker.start(()->99));
            release.countDown();
            assertEquals(17,worker.awaitAndTake());
            assertTrue(worker.start(()->23));assertEquals(23,worker.awaitAndTake());
        }
    }
    @Test void closeJoinsPreparationAndKeepsItsResultForDisposal() throws Exception {
        var worker=new ScenePreparation<Integer>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        worker.start(()->{entered.countDown();release.await();return 31;});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        var closing=CompletableFuture.runAsync(worker::close);
        assertThrows(TimeoutException.class,()->closing.get(30,TimeUnit.MILLISECONDS));
        release.countDown();closing.get(5,TimeUnit.SECONDS);
        assertEquals(31,worker.poll());assertFalse(worker.start(()->1));
    }
    @Test void preparationFailureCanBeConsumedWithoutPoisoningTheWorker() {
        try(var worker=new ScenePreparation<Integer>()) {
            worker.start(()->{throw new IllegalArgumentException("bad geometry");});
            var error=assertThrows(CompletionException.class,worker::awaitAndTake);
            assertInstanceOf(IllegalArgumentException.class,error.getCause());
            assertTrue(worker.start(()->7));assertEquals(7,worker.awaitAndTake());
        }
    }
}
