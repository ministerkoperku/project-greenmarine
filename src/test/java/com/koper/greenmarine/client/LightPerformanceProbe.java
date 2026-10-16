package com.koper.greenmarine.client;

import com.koper.greenmarine.api.*;
import com.google.gson.*;
import org.joml.Matrix4f;
import java.nio.file.*;
import java.util.*;

/** Development benchmark; excluded from the mod and never used as a timing assertion. */
public final class LightPerformanceProbe {
    private static volatile Object sink;
    public static void main(String[] args) throws Exception {
        var results=new JsonArray();
        for(int count:new int[]{1000,10000}) {
            var registry=new LightRegistry(); var baseline=new LinkedHashMap<Long,Light>(); var random=new Random(42);
            for(int i=0;i<count;i++) {
                var light=new Light(random.nextDouble()*512-256,random.nextDouble()*64,-random.nextDouble()*256,
                    0.2f,1,0.5f,2+random.nextFloat()*14,1);
                baseline.put(registry.create(light),light);
            }
            var selector=new LightSelector(); var clip=new Matrix4f().perspective((float)Math.toRadians(70),16f/9,256,0.1f,true);
            var snapshot=registry.snapshot();
            for(int i=0;i<300;i++) { sink=List.copyOf(baseline.values()); sink=registry.snapshot(); selector.select(snapshot,0,32,0,16,clip); }
            int iterations=500; long started=System.nanoTime();
            for(int i=0;i<iterations;i++) sink=List.copyOf(baseline.values());
            double copyMicros=(System.nanoTime()-started)/(iterations*1000.0);
            started=System.nanoTime(); for(int i=0;i<iterations;i++) sink=registry.snapshot();
            double cacheMicros=(System.nanoTime()-started)/(iterations*1000.0);
            started=System.nanoTime(); for(int i=0;i<iterations;i++) { selector.select(snapshot,0,32,0,16,clip); sink=selector.get(0); }
            double selectionMicros=(System.nanoTime()-started)/(iterations*1000.0);
            var result=new JsonObject(); result.addProperty("lights",count); result.addProperty("iterations",iterations);
            result.addProperty("oldSnapshotCopyMicros",copyMicros); result.addProperty("cachedSnapshotMicros",cacheMicros);
            result.addProperty("frustumSelectionMicros",selectionMicros); result.addProperty("selectedLights",selector.size()); results.add(result);
        }
        var report=new JsonObject(); report.addProperty("scope","CPU microbenchmark, fixed seed and warmed paths; no Minecraft FPS claim"); report.add("results",results);
        var text=new GsonBuilder().setPrettyPrinting().create().toJson(report);
        var path=Path.of("build/reports/light-benchmark.json"); Files.createDirectories(path.getParent()); Files.writeString(path,text+"\n");
        System.out.println(text);
    }
}
