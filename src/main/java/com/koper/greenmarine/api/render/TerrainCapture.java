package com.koper.greenmarine.api.render;

import com.koper.greenmarine.client.terrain.TerrainMesher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.ToIntFunction;

/** Opt-in vanilla terrain attributes. Configure before rebuilding visible sections. */
public final class TerrainCapture {
    public enum Profile { DISABLED, IRIS_SEPARATE_AO }
    /** Fully materialized immutable mapping; worker threads never invoke user callbacks. */
    public static final class MappingSnapshot {
        private final Map<BlockState,Integer> ids;
        private MappingSnapshot(Map<BlockState,Integer> ids) {this.ids=Map.copyOf(ids);}
        public static MappingSnapshot capture(ToIntFunction<BlockState> mapper) {
            Objects.requireNonNull(mapper);var result=new HashMap<BlockState,Integer>();
            for(var block:BuiltInRegistries.BLOCK)for(var state:block.getStateDefinition().getPossibleStates()) {
                int id=mapper.applyAsInt(state);if(id!=-1)result.put(state,id);
            }
            return new MappingSnapshot(result);
        }
        public static MappingSnapshot unmapped() {return new MappingSnapshot(Map.of());}
        /** -1 is the legitimate Iris default, not an unavailable-data sentinel. */
        public int blockId(BlockState state) {return ids.getOrDefault(state,-1);}
    }
    public record Configuration(long generation,MappingSnapshot mapping,Profile profile) {
        public boolean enabled(){return profile!=Profile.DISABLED;}
    }
    private static volatile Configuration current=new Configuration(0,MappingSnapshot.unmapped(),Profile.DISABLED);
    private TerrainCapture() {}
    public static Configuration configuration(){return current;}
    /** Pack/profile changes invalidate every previous capture, including in-flight builds. */
    public static synchronized void configure(MappingSnapshot mapping,Profile profile) {
        current=new Configuration(current.generation()+1,Objects.requireNonNull(mapping),Objects.requireNonNull(profile));
        TerrainMesher.invalidate();
    }
    /** Called on world/atlas replacement; keeps the selected mapping/profile. */
    public static synchronized void invalidate() {
        current=new Configuration(current.generation()+1,current.mapping(),current.profile());TerrainMesher.invalidate();
    }
}
