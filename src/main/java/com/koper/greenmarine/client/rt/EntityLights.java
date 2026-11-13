package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.api.pack.PackStatus;
import com.koper.greenmarine.client.GreenMarineClient;
import com.koper.greenmarine.client.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Solstice's held, dropped and burning lights reach entities through the host lightmap path. */
public final class EntityLights {
    private EntityLights() {}

    public static int packedLight(Entity entity,float partialTick,int vanilla) {
        if((vanilla&255)>=240 || !GreenMarineClient.enabled())return vanilla;
        var pack=ShaderRuntime.active();
        var script=ShaderRuntime.script();
        if(pack==null || script==null || script.failed() || ShaderRuntime.status().state()==PackStatus.State.FAILED
            || pack.definition().programs().stream().noneMatch(p->"greenmarine:core/solstice_terrain".equals(p.fragment())))return vanilla;
        var client=Minecraft.getInstance();
        if(client.level==null || entity.level()!=client.level)return vanilla;
        var at=entity.getLightProbePosition(partialTick);
        int packed=vanilla;
        for(var source:DynamicLights.collect(client)) {
            double dx=source.x()-at.x,dy=source.y()-at.y,dz=source.z()-at.z;
            packed=EntityLightPolicy.apply(packed,true,dx*dx+dy*dy+dz*dz,source.level());
            if((packed&255)>=240)break;
        }
        return packed;
    }
}
