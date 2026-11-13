package com.koper.greenmarine.api.pack;

import java.util.List;
import java.util.Objects;

/** An initialized pack-owned 2D colour attachment; every write preserves its preceding version. */
public record PackTarget(PackResources.Format format,int width,int height,float scale,List<Double> clear,boolean clearEachFrame) {
    public PackTarget {
        Objects.requireNonNull(format);clear=List.copyOf(clear);
        boolean fixed=width>0 && height>0 && width<=16384 && height<=16384 && scale==0;
        boolean relative=width==0 && height==0 && Float.isFinite(scale) && scale>0 && scale<=4;
        if(!fixed && !relative)throw new IllegalArgumentException("Targets require a 2D size up to 16384 or a positive scale up to 4");
        if(clear.size()!=4)throw new IllegalArgumentException("Target clear requires four components");
        boolean integer=integer(format);
        for(double component:clear) if(!Double.isFinite(component) || (integer
                ? component<0 || component>4294967295d || component!=Math.rint(component)
                : Math.abs(component)>Float.MAX_VALUE))
            throw new IllegalArgumentException("Invalid "+(integer?"uint32":"floating-point")+" target clear component: "+component);
    }
    public static boolean integer(PackResources.Format format) {
        return format==PackResources.Format.R32UI || format==PackResources.Format.RG32UI || format==PackResources.Format.RGBA32UI;
    }
    public int width(int sceneWidth) {return scaled(width,sceneWidth);}
    public int height(int sceneHeight) {return scaled(height,sceneHeight);}
    private int scaled(int fixed,int scene) {
        double value=scale==0?fixed:Math.max(1,Math.ceil(scene*(double)scale));
        if(value>16384)throw new IllegalArgumentException("Resolved scene target exceeds 16384 pixels");
        return (int)value;
    }
    /** The actual union bits for a typed Vulkan clear, including unsigned values above INT_MAX. */
    public int[] clearBits() {
        int[] result=new int[4];boolean integer=integer(format);
        for(int i=0;i<4;i++)result[i]=integer?(int)(long)(double)clear.get(i):Float.floatToRawIntBits(clear.get(i).floatValue());
        return result;
    }
}
