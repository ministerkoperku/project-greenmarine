package com.koper.greenmarine.client.rt;

import java.util.concurrent.*;

/** One immutable snapshot in preparation; render-thread callers never wait in poll(). */
final class ScenePreparation<T> implements AutoCloseable {
    private final ExecutorService executor=Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"GreenMarine scene preparation");thread.setDaemon(true);return thread;
    });
    private Future<T> pending;
    private boolean closed;

    boolean start(Callable<T> task) {
        if(closed || pending!=null)return false;
        pending=executor.submit(task);return true;
    }
    boolean busy() {return pending!=null;}
    T poll() {return pending!=null && pending.isDone()?awaitAndTake():null;}
    T awaitAndTake() {
        if(pending==null)return null;
        boolean interrupted=false;
        try {
            for(;;)try {return pending.get();}
            catch(InterruptedException error) {interrupted=true;}
            catch(ExecutionException error) {throw new CompletionException(error.getCause());}
        } finally {pending=null;if(interrupted)Thread.currentThread().interrupt();}
    }
    @Override public void close() {
        closed=true;executor.shutdown();boolean interrupted=false;
        try {
            while(!executor.isTerminated())try {executor.awaitTermination(1,TimeUnit.SECONDS);}
            catch(InterruptedException error) {interrupted=true;}
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
}
