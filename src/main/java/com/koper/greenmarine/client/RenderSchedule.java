package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class RenderSchedule {
    public record Pass(RenderPolicy.ResolvedPass resolved, int width, int height) {}
    private RenderSchedule() {}
    public static com.mojang.renderpearl.api.GpuFormat format(PackPass pass,com.mojang.renderpearl.api.GpuFormat scene) {
        return pass.format()==PackPass.Format.RGBA16F?com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT:scene;
    }
    public static boolean canCopy(int width,int height,com.mojang.renderpearl.api.GpuFormat format,
                                  int targetWidth,int targetHeight,com.mojang.renderpearl.api.GpuFormat targetFormat) {
        return width==targetWidth && height==targetHeight && format==targetFormat;
    }
    public static List<Pass> scene(ShaderPack pack, Quality quality, RenderCapabilities caps, RayMode rays, int width, int height) {
        if (width <= 0 || height <= 0) return List.of();
        var result = new ArrayList<Pass>();
        for (var pass : pack.passes()) if (pass.stage() == PackPass.Stage.SCENE) {
            RenderPolicy.pass(pass,quality,caps,rays).ifPresent(resolved -> result.add(new Pass(resolved,
                Math.max(1,(int)Math.ceil(width * pass.scale())), Math.max(1,(int)Math.ceil(height * pass.scale())))));
        }
        var available=new HashSet<String>();
        for(var selected:result) {
            var definition=selected.resolved().definition();
            for(var producer:definition.inputs().values()) if(!available.contains(producer))
                throw new IllegalArgumentException("Selected pass "+definition.name()+" requires unavailable scene pass: "+producer);
            available.add(definition.name());
        }
        return List.copyOf(result);
    }
    public static Set<String> retainedOutputs(List<Pass> schedule) {
        var retained=new HashSet<String>();
        for(var pass:schedule) retained.addAll(pass.resolved().definition().inputs().values());
        return Set.copyOf(retained);
    }
}
