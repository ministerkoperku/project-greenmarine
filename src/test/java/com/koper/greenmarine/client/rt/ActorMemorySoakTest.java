package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ActorMemorySoakTest {
    @Test void repeatedActorCaptureSurvivesSmallDirectBudgetWithExplicitGcDisabled() throws Exception {
        run("actor");
    }
    @Test void repeatedSceneSnapshotsSurviveSmallDirectBudgetWithExplicitGcDisabled() throws Exception {
        run("scene");
    }
    private void run(String mode) throws Exception {
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-Xmx256m","-XX:MaxDirectMemorySize=1m","-XX:+UseZGC","-XX:+DisableExplicitGC",
            "--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("greenmarine.test.runtimeClasspath"),ActorMemorySoak.class.getName(),mode);
        command.redirectErrorStream(true);
        var process=command.start();
        try {
            assertTrue(process.waitFor(45,TimeUnit.SECONDS),"Actor memory soak timed out");
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("completed 50000 "+mode+" frames"),output);
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
}
