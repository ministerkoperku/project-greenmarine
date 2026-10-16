package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.BinaryShaderSource;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlslCompiler.class)
public abstract class PackSpirvMixin {
    @Unique private static final ThreadLocal<Boolean> GREENMARINE_OPTIMIZE=ThreadLocal.withInitial(()->false);
    @Inject(method="compileToSpv",at=@At("HEAD"),cancellable=true)
    private void greenmarine$binary(String name,String source,ShaderType type,ShaderDefines defines,ShaderSource resources,CallbackInfoReturnable<SpvModule> ci) {
        GREENMARINE_OPTIMIZE.set(resources instanceof BinaryShaderSource || name.equals("greenmarine:core/glow"));
        if(resources instanceof BinaryShaderSource pack) {
            var module=pack.rawModule(name,type); if(module!=null) { GREENMARINE_OPTIMIZE.remove(); ci.setReturnValue(module); }
        }
    }
    @ModifyArg(method="createBaseShaderOptions",at=@At(value="INVOKE",target="Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_options_set_optimization_level(JI)V"),index=1)
    private int greenmarine$optimization(int original) {
        return GREENMARINE_OPTIMIZE.get()?org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance:original;
    }
    @Inject(method="compileToSpv",at=@At("RETURN"))
    private void greenmarine$optimizationDone(String name,String source,ShaderType type,ShaderDefines defines,ShaderSource resources,CallbackInfoReturnable<SpvModule> ci) { GREENMARINE_OPTIMIZE.remove(); }
}
