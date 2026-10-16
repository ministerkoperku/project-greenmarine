package com.koper.greenmarine.script;

import com.koper.greenmarine.api.Light;
import com.koper.greenmarine.api.LightRegistry;
import com.koper.greenmarine.api.pack.*;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseMathLib;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class PackScript implements AutoCloseable {
    private static final Map<String, java.util.function.Supplier<LuaValue>> EXTENSIONS = new java.util.LinkedHashMap<>();
    public record Frame(double time, double day, double cameraX, double cameraY, double cameraZ,
                        RenderCapabilities capabilities, Quality quality, RayMode rays) {}
    public static final int MAX_INSTRUCTIONS = 20_000;
    private final ShaderPack pack;
    private final LightRegistry lights;
    private final long[] handles = new long[32];
    private final float[] values = new float[32], authored = new float[32], staging = new float[32];
    private Map<String,Float> overrides=Map.of();
    private final Map<String, Integer> slots = new HashMap<>();
    private final Budget budget = new Budget();
    private final LuaValue update;
    private final LuaTable frame = new LuaTable(), camera = new LuaTable(), capabilities = new LuaTable();
    private boolean failed, closed;
    private String error = "";
    private long ticks, nanos;

    public PackScript(ShaderPack pack, String source, LightRegistry lights) {
        this.pack = pack; this.lights = lights;
        for (int i = 0; i < pack.parameters().size(); i++) {
            slots.put(pack.parameters().get(i).name(), i);
            values[i] = pack.parameters().get(i).defaultValue();
        }
        if (source.length() > 65_536) throw new IllegalArgumentException("Lua source exceeds 64 KiB");
        Globals globals = new Globals();
        var loaded = new LuaTable();
        loaded.set("loaded", new LuaTable());
        globals.set("package", loaded);
        globals.load(new BaseLib()); globals.load(new JseMathLib()); globals.load(budget);
        LuaC.install(globals);
        for (String name : new String[]{"package", "debug", "dofile", "loadfile", "load", "loadstring", "require",
            "collectgarbage", "print", "pcall", "xpcall"}) globals.set(name, LuaValue.NIL);
        globals.set("marine", api());
        budget.root=globals;
        System.arraycopy(values, 0, staging, 0, values.length);
        try {
            budget.reset(); globals.load(new java.io.StringReader(source), pack.id(), globals).call();
            update = globals.get("update").checkfunction();
            budget.checkHeap();
            System.arraycopy(staging, 0, values, 0, values.length);
        } catch (RuntimeException | StackOverflowError exception) {
            close(); throw new IllegalArgumentException("Invalid pack script: " + exception.getMessage(), exception);
        }
        frame.set("camera", camera); frame.set("capabilities", capabilities);
        System.arraycopy(values,0,authored,0,values.length);
    }

    private LuaTable api() {
        var api = new LuaTable();
        api.set("uniform", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue name, LuaValue value) {
                var slot = slots.get(name.checkjstring());
                if (slot == null) throw new LuaError("Unknown parameter: " + name);
                staging[slot] = pack.parameters().get(slot).validate((float)value.checkdouble());
                return LuaValue.NIL;
            }
        });
        api.set("light", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int slot = lightSlot(args.arg1());
                var light = new Light(args.checkdouble(2), args.checkdouble(3), args.checkdouble(4),
                    (float)args.checkdouble(5), (float)args.checkdouble(6), (float)args.checkdouble(7),
                    (float)args.checkdouble(8), (float)args.checkdouble(9));
                if (handles[slot] == 0 || !lights.update(handles[slot], light)) handles[slot] = lights.create(light);
                return LuaValue.NIL;
            }
        });
        api.set("remove_light", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                int slot = lightSlot(value); lights.remove(handles[slot]); handles[slot] = 0; return LuaValue.NIL;
            }
        });
        api.set("clamp", new ThreeArgFunction() {
            @Override public LuaValue call(LuaValue value, LuaValue min, LuaValue max) {
                return LuaValue.valueOf(Math.max(min.checkdouble(), Math.min(max.checkdouble(), value.checkdouble())));
            }
        });
        api.set("mix", new ThreeArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b, LuaValue weight) {
                return LuaValue.valueOf(a.checkdouble() + (b.checkdouble() - a.checkdouble()) * weight.checkdouble());
            }
        });
        synchronized (EXTENSIONS) { EXTENSIONS.forEach((name, factory) -> api.set(name, factory.get())); }
        return api;
    }

    /** Trusted mods may install bounded engine functions; existing scripts are not modified. */
    public static AutoCloseable registerExtension(String name, java.util.function.Supplier<LuaValue> factory) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]{0,47}")
            || java.util.Set.of("uniform", "light", "remove_light", "clamp", "mix").contains(name)) {
            throw new IllegalArgumentException("Invalid or reserved script extension name");
        }
        java.util.Objects.requireNonNull(factory);
        synchronized (EXTENSIONS) {
            if (EXTENSIONS.size() >= 32 || EXTENSIONS.putIfAbsent(name, factory) != null) {
                throw new IllegalArgumentException("Duplicate extension or extension limit exceeded");
            }
        }
        return () -> { synchronized (EXTENSIONS) { EXTENSIONS.remove(name, factory); } };
    }

    private static int lightSlot(LuaValue value) {
        double number = value.checkdouble();
        if (number < 0 || number >= 32 || number != Math.floor(number)) throw new LuaError("Light slot must be an integer in 0..31");
        return (int)number;
    }

    public void tick(Frame state) {
        if (failed || closed) return;
        frame.set("time", LuaValue.valueOf(state.time())); frame.set("day", LuaValue.valueOf(state.day()));
        frame.set("quality", LuaValue.valueOf(state.quality().name().toLowerCase(java.util.Locale.ROOT)));
        frame.set("rays", LuaValue.valueOf(state.rays().name().toLowerCase(java.util.Locale.ROOT)));
        camera.set("x", LuaValue.valueOf(state.cameraX())); camera.set("y", LuaValue.valueOf(state.cameraY())); camera.set("z", LuaValue.valueOf(state.cameraZ()));
        capabilities.set("vulkan", LuaValue.valueOf(state.capabilities().vulkan()));
        capabilities.set("hardware_rt", LuaValue.valueOf(state.capabilities().hardwareRayTracing()));
        capabilities.set("voxel_rt", LuaValue.valueOf(state.capabilities().voxelRayTracing()));
        capabilities.set("spirv", LuaValue.valueOf(state.capabilities().spirv()));
        System.arraycopy(authored, 0, staging, 0, values.length);
        long started = System.nanoTime();
        try {
            budget.reset(); update.call(frame); budget.checkHeap();
            System.arraycopy(staging, 0, values, 0, values.length);
            System.arraycopy(staging,0,authored,0,values.length);
            applyOverrides();
        } catch (RuntimeException | StackOverflowError exception) {
            failed = true; error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            releaseLights();
        } finally { ticks++; nanos += System.nanoTime() - started; }
    }

    public void setOverrides(Map<String,Float> next) {
        next.forEach((name,value)-> {
            var slot=slots.get(name);
            if(slot==null) throw new IllegalArgumentException("Unknown parameter: "+name);
            pack.parameters().get(slot).validate(value);
        });
        overrides=Map.copyOf(next);
        System.arraycopy(authored,0,values,0,values.length);
        applyOverrides();
    }
    private void applyOverrides() { overrides.forEach((name,value)->values[slots.get(name)]=value); }

    public float value(String name) { return values[slots.get(name)]; }
    public void writeParameters(ByteBuffer buffer, int offset) { for (int i = 0; i < values.length; i++) buffer.putFloat(offset + i * 4, values[i]); }
    public boolean failed() { return failed; }
    public String error() { return error; }
    public long ticks() { return ticks; }
    public double averageMicros() { return ticks == 0 ? 0 : nanos / (ticks * 1000.0); }
    private void releaseLights() { for (int i = 0; i < handles.length; i++) { lights.remove(handles[i]); handles[i] = 0; } }
    @Override public void close() { releaseLights(); closed = true; }

    private static final class Budget extends DebugLib {
        private record Call(LuaClosure closure,LuaValue[] stack) {}
        private final java.util.ArrayDeque<Call> calls=new java.util.ArrayDeque<>();
        private final java.util.IdentityHashMap<LuaValue,Boolean> seen=new java.util.IdentityHashMap<>();
        private final java.util.ArrayDeque<LuaValue> queue=new java.util.ArrayDeque<>();
        private Globals root;
        private int remaining;
        void reset() { remaining = MAX_INSTRUCTIONS; calls.clear(); }
        @Override public void onCall(LuaFunction function) {
            super.onCall(function); calls.addLast(new Call(null,null));
            if(calls.size()>64) throw new LuaError("GreenMarine call-depth budget exceeded");
        }
        @Override public void onCall(LuaClosure closure,Varargs args,LuaValue[] stack) {
            super.onCall(closure,args,stack); calls.addLast(new Call(closure,stack));
            if(calls.size()>64) throw new LuaError("GreenMarine call-depth budget exceeded");
        }
        @Override public void onReturn() { super.onReturn(); if(!calls.isEmpty()) calls.removeLast(); }
        @Override public void onInstruction(int pc, Varargs args, int top) {
            if (--remaining < 0) throw new LuaError("GreenMarine instruction budget exceeded");
            var call=calls.peekLast();
            if(call!=null && call.closure()!=null) {
                int instruction=call.closure().p.code[pc];
                if(Lua.GET_OPCODE(instruction)==Lua.OP_CONCAT) {
                    long bytes=0;
                    for(int i=Lua.GETARG_B(instruction);i<=Lua.GETARG_C(instruction);i++) {
                        var value=call.stack()[i]; bytes+=value!=null && value.type()==LuaValue.TSTRING?value.rawlen():32;
                    }
                    if(bytes>65_536) throw new LuaError("GreenMarine string budget exceeded");
                }
            }
            if((remaining&63)==0) checkHeap();
        }
        private void enqueue(LuaValue value) { if(value!=null && !value.isnil() && !seen.containsKey(value)) { seen.put(value,Boolean.TRUE); queue.addLast(value); } }
        void checkHeap() {
            seen.clear(); queue.clear(); enqueue(root); long bytes=0; int entries=0;
            for(var call:calls) {
                enqueue(call.closure());
                if(call.stack()!=null) for(var value:call.stack()) enqueue(value);
            }
            while(!queue.isEmpty()) {
                var value=queue.removeFirst(); bytes+=32;
                enqueue(value.getmetatable());
                if(value.type()==LuaValue.TSTRING) bytes+=value.rawlen();
                if(value instanceof LuaTable table) {
                    LuaValue key=LuaValue.NIL;
                    while(true) {
                        var pair=table.next(key); key=pair.arg1(); if(key.isnil()) break;
                        enqueue(key); enqueue(pair.arg(2));
                        if(++entries>2048) throw new LuaError("GreenMarine table-entry budget exceeded");
                    }
                } else if(value instanceof LuaClosure closure) {
                    for(var upvalue:closure.upValues) if(upvalue!=null) enqueue(upvalue.getValue());
                }
                if(bytes>1_048_576 || seen.size()>16384) throw new LuaError("GreenMarine script-memory budget exceeded");
            }
            seen.clear(); queue.clear();
        }
    }
}
