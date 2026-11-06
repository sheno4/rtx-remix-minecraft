/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Records cloud commands into the runtime's Vulkan command buffer.
 * Callbacks run on native threads and must not access Minecraft or OpenGL.
 */
public interface CloudlyCallbacks {
    /**
     * All buffers are direct views valid only until this call returns. Their contents
     * use native byte order; set the Java buffer order before reading or writing.
     * The frame and host data are inputs; world is the resource-description output.
     * The command buffer belongs to Remix and must not be submitted or ended.
     *
     * @return a nonzero resource lease, or zero when no commands or resources were used
     */
    long record(ByteBuffer frame, ByteBuffer world, ByteBuffer hostData);

    /** Releases a lease after the GPU completes its commands. May run on a retirement thread. */
    void retire(long lease);

    /** Releases the registration after its final lease retires. */
    void release();
}
