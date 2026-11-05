/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.gl.CloudlyGlInterop;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.RemixAPI;
import dev.comfyfluffy.cloudly.CloudlyRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft state adapter for the separately licensed Cloudly compute runtime.
 * Composites against Remix's final display image; Remix's path-traced lighting
 * and reflection inputs remain owned by Remix.
 */
public final class Ac8CloudRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ac8CloudRenderer.class);
    private static final String[] SCENES = {"hangar", "mission3", "mission11"};
    private static final CloudlyRuntime.Lighting SKY_LIGHTING = CloudlyRuntime.Lighting.vanilla();

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f inverseClip = new Matrix4f();
    private final float[] inverseClipValues = new float[16];
    private final byte[] resolvedOption = new byte[64];
    private CloudlyRuntime runtime;
    private CloudlyGlInterop interop;
    private boolean enabled = true;
    private int scene;
    private int heightOffset;
    private float maxDistanceKm = 100.0f;
    private boolean settingsDirty = true;
    private boolean failed;
    private boolean active;
    private boolean cameraValid;
    private long frameIndex;
    private double timeSeconds;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float sunAngle;
    private float starBrightness;
    private int moonPhase;
    private int seaLevel;
    private float rain;
    private float thunder;

    /** Prepares the next frame before ordinary clouds are submitted to Remix. */
    public void prepare(ClientWorld world, int width, int height) {
        cameraValid = false;
        boolean requested = enabled && RtxRemixMod.isWorldActive()
                && RtxRemixMod.isSkyEnabled() && world != null
                && world.getDimensionEffects().getSkyType() == DimensionEffects.SkyType.NORMAL
                && width > 0 && height > 0;
        if (!requested || failed) {
            if (active && runtime != null) runtime.resetHistory();
            active = false;
            return;
        }
        try {
            if (runtime == null) {
                runtime = CloudlyRuntime.create(CloudlyGlInterop.deviceUuid());
                settingsDirty = true;
            }
            if (settingsDirty) {
                runtime.setSettings(new CloudlyRuntime.Settings(
                        enabled, SCENES[scene], heightOffset, maxDistanceKm));
                settingsDirty = false;
            }
            if (!runtime.isReady()) {
                active = false;
                return;
            }
            if (interop == null || interop.width() != width || interop.height() != height) {
                releaseInterop();
                interop = new CloudlyGlInterop(runtime.resize(width, height));
                runtime.resetHistory();
            }
            active = true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    /**
     * Matches the parameterized Remix camera: zero-to-one reversed depth,
     * Vulkan's downward framebuffer Y, and a rotation-only view matrix.
     */
    public void updateCamera(Camera camera, float tickDelta, float fovY, float aspect,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ,
            float forwardX, float forwardY, float forwardZ) {
        if (!active) return;
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;
        Vec3d position = camera.getPos();
        cameraX = position.x;
        cameraY = position.y;
        cameraZ = position.z;
        timeSeconds = (world.getTime() + (double) tickDelta) / 20.0;

        view.identity()
                .m00(rightX).m10(rightY).m20(rightZ)
                .m01(upX).m11(upY).m21(upZ)
                .m02(-forwardX).m12(-forwardY).m22(-forwardZ);
        inverseClip.setPerspective((float) Math.toRadians(fovY), aspect, 1000.0f, 0.05f, true);
        inverseClip.m11(-inverseClip.m11());
        inverseClip.mul(view).invert().get(inverseClipValues);
        sunAngle = world.getSkyAngle(tickDelta) * (float) (Math.PI * 2.0);
        starBrightness = world.getStarBrightness(tickDelta);
        moonPhase = world.getMoonPhase();
        seaLevel = world.getSeaLevel();
        rain = world.getRainGradient(tickDelta);
        thunder = world.getThunderGradient(tickDelta);
        cameraValid = true;
    }

    /** Runs after the Remix GL wait and before the weather, overlays and HUD. */
    public int composite(int remixColor, int remixDepth, RemixAPI remix) {
        if (!active || !cameraValid || remixColor == 0 || remixDepth == 0) return remixColor;
        try {
            CloudlyRuntime.SkyFrame skyFrame = captureSkyFrame(remix);
            interop.stageInputs(remixColor, remixDepth);
            runtime.render(frameIndex++, timeSeconds, cameraX, cameraY, cameraZ,
                    inverseClipValues, 0.0f, 0.0f, 1.0f, skyFrame);
            return interop.awaitOutput();
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return remixColor;
        }
    }

    private CloudlyRuntime.SkyFrame captureSkyFrame(RemixAPI remix) {
        double fadeStart = 0.0;
        double fadeEnd = 0.0;
        double mode = readResolvedNumber(remix, "rtx.viewDistance.distanceMode");
        if (Double.isFinite(mode) && mode > 0.0) {
            fadeStart = readResolvedNumber(remix, "rtx.viewDistance.distanceFadeMin");
            fadeEnd = readResolvedNumber(remix, "rtx.viewDistance.distanceFadeMax");
            if (!Double.isFinite(fadeStart) || !Double.isFinite(fadeEnd)
                    || fadeStart < 0.0 || fadeEnd <= fadeStart) {
                fadeStart = 0.0;
                fadeEnd = 0.0;
            }
        }
        // Read after Remix submit resolves every conf layer. At the terrain edge the native
        // composition must converge to its own directional background, not the old Remix sky.
        return new CloudlyRuntime.SkyFrame(sunAngle, sunAngle + (float) Math.PI, sunAngle,
                starBrightness, moonPhase, seaLevel, rain, thunder, fadeStart, fadeEnd, SKY_LIGHTING);
    }

    private double readResolvedNumber(RemixAPI remix, String key) {
        int size = remix.getConfigVariableResolved(key, resolvedOption);
        if (size < 2 || size > resolvedOption.length || resolvedOption[size - 1] != 0) {
            return Double.NaN;
        }
        return parseNumber(resolvedOption, size - 1);
    }

    /** Parses the runtime's ASCII decimal/scientific scalar without allocating per-frame strings. */
    private static double parseNumber(byte[] bytes, int length) {
        int cursor = 0;
        while (cursor < length && bytes[cursor] <= ' ') cursor++;
        while (length > cursor && bytes[length - 1] <= ' ') length--;
        boolean negative = cursor < length && bytes[cursor] == '-';
        if (cursor < length && (bytes[cursor] == '-' || bytes[cursor] == '+')) cursor++;
        double value = 0.0;
        double fraction = 0.0;
        int digits = 0;
        while (cursor < length) {
            int character = bytes[cursor];
            if (character >= '0' && character <= '9') {
                if (fraction == 0.0) value = value * 10.0 + character - '0';
                else {
                    value += (character - '0') * fraction;
                    fraction *= 0.1;
                }
                digits++;
            } else if (character == '.' && fraction == 0.0) {
                fraction = 0.1;
            } else break;
            cursor++;
        }
        if (digits == 0) return Double.NaN;
        if (cursor < length && (bytes[cursor] == 'e' || bytes[cursor] == 'E')) {
            cursor++;
            boolean exponentNegative = cursor < length && bytes[cursor] == '-';
            if (cursor < length && (bytes[cursor] == '-' || bytes[cursor] == '+')) cursor++;
            int exponent = 0;
            int exponentDigits = 0;
            while (cursor < length && bytes[cursor] >= '0' && bytes[cursor] <= '9') {
                exponent = exponent * 10 + bytes[cursor++] - '0';
                if (exponent > 4096) return Double.NaN;
                exponentDigits++;
            }
            if (exponentDigits == 0) return Double.NaN;
            value *= Math.pow(10.0, exponentNegative ? -exponent : exponent);
        }
        return cursor == length ? (negative ? -value : value) : Double.NaN;
    }

    /** Ordinary cloud meshes stay active until loading and shared-image setup finish. */
    public boolean isActive() { return active; }
    public boolean isEnabled() { return enabled; }
    public int getScene() { return scene; }
    public int getHeightOffset() { return heightOffset; }
    public float getMaxDistanceKm() { return maxDistanceKm; }

    /** Keeps Remix's direct lights and reflected celestial geometry on the active sky's path. */
    public float getSunPathRotationDegrees() {
        return active ? runtime.noonTiltDegrees() : LightManager.SUN_PATH_ROTATION_DEG;
    }

    /** Status for the existing /rtx status diagnostic, outside the per-frame path. */
    public String describe() {
        String state = !enabled ? "off" : failed ? "failed" : active ? "active" : "waiting";
        return state + ", scene " + SCENES[scene] + ", height " + heightOffset
                + " blocks, distance " + maxDistanceKm + " km";
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        settingsDirty = true;
        failed = false;
        active = false;
        if (runtime != null) runtime.resetHistory();
    }

    public void setScene(int value) {
        if (value < 0 || value >= SCENES.length) throw new IllegalArgumentException("Unknown AC8 scene " + value);
        scene = value;
        settingsDirty = true;
    }

    public void setHeightOffset(int value) {
        if (value < -16000 || value > 16000) throw new IllegalArgumentException("AC8 height outside range");
        heightOffset = value;
        settingsDirty = true;
    }

    public void setMaxDistanceKm(float value) {
        if (!Float.isFinite(value) || value < 1.0f || value > 200.0f) {
            throw new IllegalArgumentException("AC8 distance outside range");
        }
        maxDistanceKm = value;
        settingsDirty = true;
    }

    /** Drops world-scoped temporal state even when the connection survives a world swap. */
    public void clearWorld() {
        active = false;
        cameraValid = false;
        frameIndex = 0;
        if (runtime != null) runtime.resetHistory();
    }

    private void fail(Throwable failure) {
        active = false;
        failed = true;
        cameraValid = false;
        LOGGER.error("AC8 clouds stopped; toggle AC8 Clouds off and on to retry", failure);
        releaseRuntime();
    }

    private void releaseInterop() {
        if (interop != null) {
            // GL may still sample the previous output; resize must not free it first.
            org.lwjgl.opengl.GL11.glFinish();
            interop.close();
            interop = null;
        }
    }

    private void releaseRuntime() {
        try {
            releaseInterop();
        } finally {
            if (runtime != null) {
                runtime.close();
                runtime = null;
            }
        }
    }

    @Override
    public void close() {
        clearWorld();
        releaseRuntime();
    }
}
