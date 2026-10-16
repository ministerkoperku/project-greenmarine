package com.koper.greenmarine.probe.mixin;

import com.koper.greenmarine.probe.HardwareRtProbe;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class ProbeCameraMixin {
    @Inject(method="renderLevel", at=@At("HEAD"))
    private void greenmarine$camera(CallbackInfo ci) {
        if(Boolean.getBoolean("greenmarine.probe.performance")) {
            var state=((GameRenderer)(Object)this).gameRenderState();
            state.levelRenderState.skyRenderState.sunAngle=0;state.levelRenderState.skyRenderState.rainBrightness=1;
            state.optionsRenderState.bobView=false;
            var level=net.minecraft.client.Minecraft.getInstance().level;
            if(level!=null){level.setRainLevel(0);level.setThunderLevel(0);}
            return;
        }
        if(Boolean.getBoolean("greenmarine.probe.dynamic_rays")) {com.koper.greenmarine.probe.DynamicRaySceneProbe.camera((GameRenderer)(Object)this);return;}
        if (!Boolean.getBoolean("greenmarine.probe.hardware_rt") && !Boolean.getBoolean("greenmarine.probe.world_programs")) return;
        var renderer=(GameRenderer)(Object)this;
        var state=renderer.gameRenderState();
        if(Boolean.getBoolean("greenmarine.probe.world_programs")) {
            if(com.koper.greenmarine.probe.WorldProgramsProbe.wireframe)state.levelRenderState.renderWireframeTerrain=true;
            state.levelRenderState.skyRenderState.sunAngle=com.koper.greenmarine.probe.WorldProgramsProbe.night?(float)Math.PI:0;
            state.levelRenderState.skyRenderState.rainBrightness=com.koper.greenmarine.probe.WorldProgramsProbe.rainy?.35f:1;
            if(Boolean.getBoolean("greenmarine.probe.solstice")) {
                var level=net.minecraft.client.Minecraft.getInstance().level;
                level.setRainLevel(com.koper.greenmarine.probe.WorldProgramsProbe.rainy?.8f:com.koper.greenmarine.probe.WorldProgramsProbe.originalRain);
                level.setThunderLevel(com.koper.greenmarine.probe.WorldProgramsProbe.rainy?.4f:com.koper.greenmarine.probe.WorldProgramsProbe.originalThunder);
            }
            if(com.koper.greenmarine.probe.WorldProgramsProbe.flashing)net.minecraft.client.Minecraft.getInstance().level.setSkyFlashTime(2);
        }
        var entity=state.levelRenderState.cameraRenderState.entityRenderState;
        entity.isPlayer=true;
        entity.bob=(HardwareRtProbe.bobbing || com.koper.greenmarine.probe.WorldProgramsProbe.bobbing) ? .25f : 0;
        entity.backwardsInterpolatedWalkDistance=.35f;
        state.optionsRenderState.bobView=true;
    }
}
