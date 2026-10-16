package com.koper.greenmarine.probe;

/** Throws only after a real host RT submission, once, from this test source set. */
public final class ProbeSubmissionFault {
    private static boolean armed,triggered;
    private ProbeSubmissionFault() {}
    public static void arm() {armed=true;triggered=false;}
    public static boolean armed() {return armed;}
    public static boolean triggered() {return triggered;}
    public static void afterHostSubmit() {
        if(!armed || !Boolean.getBoolean("greenmarine.probe.dynamic_rays"))return;
        boolean raySubmit=StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
            frame.getClassName().equals("com.koper.greenmarine.client.rt.RayWorld") && frame.getMethodName().equals("submit")));
        if(!raySubmit)return;
        armed=false;triggered=true;
        throw new IllegalStateException("Intentional probe failure after actual host RT submission");
    }
}
