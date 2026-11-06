/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.nvidia.rtxremix.CloudlyCallbacks;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import dev.comfyfluffy.cloudly.BorrowedCloudlyRuntime;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.world.ClientWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Captures Minecraft sky state for the separately licensed native-thread cloud recorder. */
public final class Ac8CloudRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ac8CloudRenderer.class);
    private static final String[] SCENES = {"hangar", "mission3", "mission11"};
    private static final int HOST_BYTES = 96;
    private static final float WORLD_SUN_REFERENCE = 30.0f;
    private static final float BLOCK_EMISSION = 2000.0f;
    private static final float NIGHT_AIRGLOW = 0.002f;
    private static final float STAR_LUMINANCE = 10.0f;
    private static final float MOON_FIXED_FRACTION = 0.1f;

    private final ByteBuffer packet = ByteBuffer.allocateDirect(HOST_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private RemixAPI api;
    private Recorder recorder;
    private BorrowedCloudlyRuntime.ReadyScene lightScene;
    private boolean enabled = true;
    private int scene;
    private int heightOffset;
    private float maxDistanceKm = 100.0f;
    private boolean resetHistory = true;
    private boolean failed;
    private boolean active;
    private boolean requested;
    private boolean cameraValid;
    private double timeSeconds;
    private float sunAngle;
    private float starBrightness;
    private int moonPhase;
    private int seaLevel;
    private float rain;
    private float thunder;

    /** Freezes the scene publication before this frame submits its lights and sky. */
    public void prepare(ClientWorld world, int width, int height, RemixAPI remix) {
        cameraValid = false;
        requested = enabled && RtxRemixMod.isWorldActive() && RtxRemixMod.isSkyEnabled()
                && world != null && world.getDimensionEffects().getSkyType() == DimensionEffects.SkyType.NORMAL
                && width > 0 && height > 0;
        if (!requested || failed) {
            active = false;
            lightScene = null;
            unregister();
            return;
        }
        try {
            if (recorder != null && recorder.failure != null) throw new IllegalStateException(
                    "Native AC8 cloud recording failed", recorder.failure);
            if (recorder == null) {
                Recorder created = new Recorder();
                remix.registerCloudly(created);
                api = remix;
                recorder = created;
                resetHistory = true;
            }
            lightScene = recorder.runtime.readyScene();
            active = lightScene != null;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    /** Captures game state only on the render thread; native callbacks never read the client. */
    public void captureWorld(ClientWorld world, float tickDelta) {
        if (!requested || failed || recorder == null || world == null) return;
        timeSeconds = (world.getTime() + (double) tickDelta) / 20.0;
        sunAngle = world.getSkyAngle(tickDelta) * (float) (Math.PI * 2.0);
        starBrightness = world.getStarBrightness(tickDelta);
        moonPhase = world.getMoonPhase();
        seaLevel = world.getSeaLevel();
        rain = world.getRainGradient(tickDelta);
        thunder = world.getThunderGradient(tickDelta);
        cameraValid = true;
    }

    /** Copies this frame's immutable state immediately before the matching native Submit. */
    public void stageFrame(RemixAPI remix) {
        if (recorder == null) return;
        boolean render = requested && cameraValid && !failed && RtxRemixMod.isRtxEnabled();
        var lighting = lightScene;
        float sourceSun = lighting == null ? 128000.0f : lighting.sunIlluminanceLux();
        float sourceMoon = lighting == null ? 5.0f : lighting.moonIlluminanceLux();
        packet.putInt(0, HOST_BYTES).putInt(4, 1).putFloat(8, WORLD_SUN_REFERENCE / sourceSun);
        packet.putInt(12, (render ? 1 : 0) | (resetHistory ? 2 : 0));
        packet.putInt(16, scene).putInt(20, heightOffset).putFloat(24, maxDistanceKm);
        packet.putFloat(28, sunAngle).putFloat(32, sunAngle + (float) Math.PI).putFloat(36, sunAngle);
        packet.putFloat(40, starBrightness).putInt(44, moonPhase).putInt(48, seaLevel);
        packet.putFloat(52, rain).putFloat(56, thunder);
        packet.putFloat(60, sourceSun * LightManager.getSunIntensity());
        packet.putFloat(64, sourceMoon * LightManager.getMoonIntensity());
        packet.putFloat(68, BLOCK_EMISSION).putFloat(72, NIGHT_AIRGLOW).putFloat(76, STAR_LUMINANCE);
        packet.putFloat(80, MOON_FIXED_FRACTION).putInt(84, lighting == null ? -1 : lighting.sceneIndex());
        packet.putDouble(88, timeSeconds);
        packet.position(0).limit(HOST_BYTES);
        remix.setCloudlyFrameData(packet);
        if (render) resetHistory = false;
    }

    public boolean isActive() { return active; }
    public boolean isEnabled() { return enabled; }
    public int getScene() { return scene; }
    public int getHeightOffset() { return heightOffset; }
    public float getMaxDistanceKm() { return maxDistanceKm; }
    public float getSunAngleRadians() { return sunAngle; }
    public int getMoonPhase() { return moonPhase; }

    /** This snapshot is the same one acknowledged by this frame's native sky packet. */
    public BorrowedCloudlyRuntime.ReadyScene getLightScene() { return active ? lightScene : null; }

    public float getSceneRadianceScale() {
        return lightScene == null ? 1.0f : WORLD_SUN_REFERENCE / lightScene.sunIlluminanceLux();
    }

    public float getSunPathRotationDegrees() {
        return active ? lightScene.noonTiltDegrees() : LightManager.SUN_PATH_ROTATION_DEG;
    }

    public String describe() {
        String state = !enabled ? "off" : failed ? "failed" : active ? "active" : "waiting";
        return state + ", scene " + SCENES[scene] + ", height " + heightOffset
                + " blocks, distance " + maxDistanceKm + " km";
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        failed = false;
        active = false;
        resetHistory = true;
    }

    public void setScene(int value) {
        if (value < 0 || value >= SCENES.length) throw new IllegalArgumentException("Unknown AC8 scene " + value);
        scene = value;
    }

    public void setHeightOffset(int value) {
        if (value < -16000 || value > 16000) throw new IllegalArgumentException("AC8 height outside range");
        heightOffset = value;
    }

    public void setMaxDistanceKm(float value) {
        if (!Float.isFinite(value) || value < 1.0f || value > 200.0f) {
            throw new IllegalArgumentException("AC8 distance outside range");
        }
        maxDistanceKm = value;
    }

    public void clearWorld() {
        requested = false;
        active = false;
        cameraValid = false;
        lightScene = null;
        resetHistory = true;
        unregister();
    }

    private void fail(Throwable failure) {
        active = false;
        requested = false;
        failed = true;
        cameraValid = false;
        lightScene = null;
        LOGGER.error("AC8 clouds stopped; toggle AC8 Clouds off and on to retry", failure);
        unregister();
    }

    private void unregister() {
        if (recorder == null) return;
        api.registerCloudly(null);
        api.setCloudlyFrameData(null);
        recorder = null;
        api = null;
    }

    @Override public void close() { clearWorld(); }

    private static final class Recorder implements CloudlyCallbacks {
        private final BorrowedCloudlyRuntime runtime = new BorrowedCloudlyRuntime();
        private volatile Throwable failure;

        @Override public long record(ByteBuffer frame, ByteBuffer world, ByteBuffer hostData) {
            try {
                long lease = runtime.record(frame, world, hostData);
                if ((world.order(ByteOrder.LITTLE_ENDIAN).getInt(8) & 0x80000000) != 0) {
                    failure = new IllegalStateException("Cloudly rejected the native frame; see its recording error");
                }
                return lease;
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            }
        }
        @Override public void retire(long lease) { runtime.retire(lease); }
        @Override public void release() { runtime.release(); }
    }
}
