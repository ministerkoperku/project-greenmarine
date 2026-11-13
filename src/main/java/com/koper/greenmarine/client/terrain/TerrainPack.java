package com.koper.greenmarine.client.terrain;

import com.koper.greenmarine.api.pack.PackTerrain;
import com.koper.greenmarine.api.render.TerrainCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/** Owns manifest capture activation; explicit Java capture users retain their existing lifecycle. */
public final class TerrainPack {
    private static boolean active;
    private TerrainPack() {}
    public static void activate(PackTerrain terrain,Minecraft client) {
        if(!terrain.enabled())return;
        validateHost(client);
        if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium"))
            throw new IllegalArgumentException("This converted pack requires vanilla terrain capture; the Sodium capture adapter is unavailable");
        var mapping=TerrainCapture.MappingSnapshot.capture(state->{
            var properties=new HashMap<String,String>();
            for(var property:state.getProperties())properties.put(property.getName(),value(state,property));
            return terrain.materialId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),properties);
        });
        TerrainCapture.configure(mapping,TerrainCapture.Profile.IRIS_SEPARATE_AO);
        TerrainDrawBindings.configure(true);active=true;
        rebuild(client);
    }
    public static void validateHost(Minecraft client) {
        if(!com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("Vulkan"))
            throw new IllegalArgumentException("Converted terrain capture currently requires the native Vulkan backend");
        if(client.options.improvedTransparency().get() || client.gameRenderer.gameRenderState().levelRenderState.renderWireframeTerrain)
            throw new IllegalArgumentException("Converted terrain requires regular transparency and filled terrain; OIT/wireframe adapters are unavailable");
    }
    public static void release(Minecraft client) {
        if(!active){TerrainCapture.invalidate();return;}
        active=false;TerrainDrawBindings.configure(false);
        TerrainCapture.configure(TerrainCapture.MappingSnapshot.unmapped(),TerrainCapture.Profile.DISABLED);
        rebuild(client);
    }
    private static void rebuild(Minecraft client) {
        if(client.level!=null)client.levelRenderer.invalidateCompiledGeometry(client.level,client.options,client.gameRenderer.mainCamera(),client.getBlockColors());
    }
    private static <T extends Comparable<T>> String value(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
}
