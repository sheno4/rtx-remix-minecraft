/*
 * SPDX-FileCopyrightText: Copyright (c) 2012-present Lightweight Java Game Library. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.nvidia.rtxremix.minecraft.mixin;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import static org.lwjgl.system.JNI.callPPP;
import static org.lwjgl.system.JNI.callPPPI;
import static org.lwjgl.system.JNI.callPPPPI;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/** Backports LWJGL 3.4.1's heap allocation for driver-sized Vulkan extension arrays. */
@Mixin(targets = "org.lwjgl.vulkan.VkInstance", remap = false)
public abstract class LwjglVulkanInstanceMixin {

    /**
     * @author LWJGL contributors
     * @reason Driver extension arrays can exceed Minecraft's default MemoryStack capacity.
     *         The public function provider supplies the same loader entry point as LWJGL's
     *         package-private global commands. Source: LWJGL 3.4.1 VkInstance.java.
     */
    @Overwrite(remap = false)
    private static Set<String> getAvailableDeviceExtensions(long instance) {
        HashSet<String> extensions = new HashSet<>();

        out:
        try (MemoryStack stack = stackPush()) {
            IntBuffer ip = stack.callocInt(1);
            long getInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
            long enumeratePhysicalDevices = callPPP(instance,
                    memAddress(stack.ASCII("vkEnumeratePhysicalDevices")), getInstanceProcAddr);
            long enumerateDeviceExtensionProperties = callPPP(instance,
                    memAddress(stack.ASCII("vkEnumerateDeviceExtensionProperties")), getInstanceProcAddr);
            if (enumeratePhysicalDevices == NULL || enumerateDeviceExtensionProperties == NULL) {
                break out;
            }
            int err = callPPPI(instance, memAddress(ip), NULL, enumeratePhysicalDevices);
            if (err != VK_SUCCESS || ip.get(0) == 0) {
                break out;
            }

            PointerBuffer physicalDevices = stack.mallocPointer(ip.get(0));
            err = callPPPI(instance, memAddress(ip), memAddress(physicalDevices), enumeratePhysicalDevices);
            if (err != VK_SUCCESS) {
                break out;
            }
            for (int i = 0; i < physicalDevices.remaining(); i++) {
                err = callPPPPI(physicalDevices.get(i), NULL, memAddress(ip), NULL,
                        enumerateDeviceExtensionProperties);
                if (err != VK_SUCCESS || ip.get(0) == 0) {
                    continue;
                }
                try (VkExtensionProperties.Buffer deviceExtensions = VkExtensionProperties.malloc(ip.get(0))) {
                    err = callPPPPI(physicalDevices.get(i), NULL, memAddress(ip), deviceExtensions.address(),
                            enumerateDeviceExtensionProperties);
                    if (err != VK_SUCCESS) {
                        continue;
                    }
                    for (int j = 0; j < deviceExtensions.remaining(); j++) {
                        extensions.add(deviceExtensions.get(j).extensionNameString());
                    }
                }
            }
        }

        return extensions;
    }
}
