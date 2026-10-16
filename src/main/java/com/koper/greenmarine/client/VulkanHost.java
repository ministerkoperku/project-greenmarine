package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.mixin.FrontendGpuDeviceAccessor;
import com.koper.greenmarine.mixin.VulkanDeviceAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

public final class VulkanHost {
    private static long attachedDevice;
    private static boolean reported;

    private VulkanHost() {}

    public static void attachIfReady() {
        var gpu = RenderSystem.tryGetDevice();
        var engine = GreenMarineClient.engine();
        if (gpu == null || engine == null) return;
        if (!(gpu instanceof FrontendGpuDeviceAccessor frontend)) return;
        var backend = frontend.greenmarine$backend();
        if (!reported) {
            reported = true;
            GreenMarine.LOGGER.info("Host backend={}, GPU={}, native={}, worldPointLights=true, voxelShadowsAvailable={}, hardwareRayTracing=false, FSR=false",
                gpu.getDeviceInfo().backendName(), gpu.getDeviceInfo().name(), engine.available(),ShaderRuntime.capabilities().voxelRayTracing());
        }
        if (!(backend instanceof VulkanDevice vk) || !engine.available()) return;
        long device = vk.vkDevice().address();
        if (attachedDevice == device) return;
        int result = engine.attach(vk.instance().vkInstance().address(), vk.vkDevice().getPhysicalDevice().address(),
            device, vk.graphicsQueue().vkQueue().address(), vk.graphicsQueue().queueFamilyIndex());
        attachedDevice = device;
        GreenMarine.LOGGER.info("Borrowed host Vulkan context: status={}, enabledFeatures={}",
            result, ((VulkanDeviceAccessor) vk).greenmarine$enabledFeatures());
    }

    public static void reset() {
        com.koper.greenmarine.client.rt.RayWorld.shutdown();
        var engine = GreenMarineClient.engine();
        if (engine != null) engine.detach();
        attachedDevice = 0;
    }
}
