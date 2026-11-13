package com.koper.greenmarine.api.render;

import com.koper.greenmarine.api.pack.ShaderPack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.Predicate;

/** Optional render-thread geometry for data-driven blocks whose baked parent is only a fallback. */
public final class BlockRayModels {
    public static final int ALL_FACES=63;
    /** Face mask uses Direction.ordinal(); transform applies before the block's own model offset. */
    public record Part(BlockState material,float x,float y,float z,float scale,int faceMask) {
        public Part {
            Objects.requireNonNull(material);
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(scale)||scale<=0 || (faceMask&~ALL_FACES)!=0)
                throw new IllegalArgumentException("Invalid custom block geometry transform");
        }
    }
    public record Context(ClientLevel level,BlockPos position,BlockState state,CompoundTag localData) {}
    @FunctionalInterface public interface Provider {List<Part> parts(Context context);}
    public interface Registration extends AutoCloseable {@Override void close();}
    private record Entry(String id,Predicate<BlockState> supports,Provider provider) {}
    private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();
    private static volatile List<Entry> providers=List.of();
    private BlockRayModels() {}
    public static synchronized Registration register(String id,Predicate<BlockState> supports,Provider provider) {
        ShaderPack.requireId(id);var entry=new Entry(id,Objects.requireNonNull(supports),Objects.requireNonNull(provider));
        if(ENTRIES.putIfAbsent(id,entry)!=null)throw new IllegalArgumentException("Duplicate block ray model provider: "+id);
        providers=List.copyOf(ENTRIES.values());
        return ()->{synchronized(BlockRayModels.class){if(ENTRIES.remove(id,entry))providers=List.copyOf(ENTRIES.values());}};
    }
    /** Null means ordinary baked geometry; an empty list is a deliberately empty custom block. */
    public static List<Part> parts(ClientLevel level,BlockPos position,BlockState state,CompoundTag localData) {
        for(var entry:providers)if(entry.supports().test(state)) {
            var supplied=entry.provider().parts(new Context(level,position.immutable(),state,localData));
            if(supplied==null)return null;
            var result=List.copyOf(supplied);
            if(result.size()>4096)throw new IllegalArgumentException("Custom block geometry exceeds 4096 parts: "+entry.id());
            return result;
        }
        return null;
    }
}
