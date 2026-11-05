/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import dev.comfyfluffy.cloudly.CloudlyRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;

/**
 * Render-thread GL imports for the independently distributed Cloudly runtime.
 * Images and semaphore HANDLEs remain owned by that runtime. No image crosses the CPU.
 */
public final class CloudlyGlInterop implements AutoCloseable {
    private static final int[] NO_BUFFERS = new int[0];
    private static final String INPUT_SHADER =
            "/assets/rtx-remix-renderer/shaders/cloudly_inputs.comp";

    private final ImportedImage color = new ImportedImage();
    private final ImportedImage depth = new ImportedImage();
    private final ImportedImage output = new ImportedImage();
    private final ImageBinding colorBinding = new ImageBinding(0);
    private final ImageBinding depthBinding = new ImageBinding(1);
    private final int[] textures = new int[3];
    private final int[] layouts = {
            GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT};
    private int readySemaphore;
    private int doneSemaphore;
    private int program;
    private int width;
    private int height;

    /** The UUID is used to select the same physical GPU as Minecraft's GL context. */
    public static byte[] deviceUuid() {
        while (glGetError() != GL_NO_ERROR) {
            // A sticky error from vanilla is not a UUID-query failure.
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uuid = stack.malloc(EXTMemoryObject.GL_UUID_SIZE_EXT);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, uuid);
            checkGl("read OpenGL device UUID");
            byte[] result = new byte[EXTMemoryObject.GL_UUID_SIZE_EXT];
            uuid.get(result);
            return result;
        }
    }

    public CloudlyGlInterop(CloudlyRuntime.SharedFrame frame) {
        try {
            color.importImage(frame.colorInput(), GL_RGBA16F);
            depth.importImage(frame.depthInput(), GL_R32F);
            output.importImage(frame.output(), GL_RGBA16F);
            readySemaphore = importSemaphore(frame.inputReadyHandle());
            doneSemaphore = importSemaphore(frame.outputReadyHandle());
            textures[0] = color.texture;
            textures[1] = depth.texture;
            textures[2] = output.texture;
            width = frame.colorInput().width();
            height = frame.colorInput().height();
            program = createProgram();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** Stages the already-acquired Remix images, then releases all shared images to Vulkan. */
    public void stageInputs(int remixColor, int remixDepth) {
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int previousColor = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(GL_TEXTURE1);
        int previousDepth = glGetInteger(GL_TEXTURE_BINDING_2D);
        colorBinding.save();
        depthBinding.save();
        try {
            glUseProgram(program);
            glBindTextureUnit(0, remixColor);
            glBindTextureUnit(1, remixDepth);
            glBindImageTexture(0, color.texture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glBindImageTexture(1, depth.texture, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
            glSignalSemaphoreEXT(readySemaphore, NO_BUFFERS, textures, layouts);
            glFlush();
            checkGl("stage Cloudly inputs");
        } finally {
            colorBinding.restore();
            depthBinding.restore();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, previousColor);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, previousDepth);
            glActiveTexture(previousActiveTexture);
            glUseProgram(previousProgram);
        }
    }

    /** Acquires every shared image so the next GL frame can write the inputs again. */
    public int awaitOutput() {
        glWaitSemaphoreEXT(doneSemaphore, NO_BUFFERS, textures, layouts);
        checkGl("wait for Cloudly output");
        return output.texture;
    }

    public int width() { return width; }
    public int height() { return height; }

    /** Call before the runtime frees or resizes its exported Vulkan allocations. */
    @Override
    public void close() {
        color.close();
        depth.close();
        output.close();
        if (readySemaphore != 0) {
            glDeleteSemaphoresEXT(readySemaphore);
            readySemaphore = 0;
        }
        if (doneSemaphore != 0) {
            glDeleteSemaphoresEXT(doneSemaphore);
            doneSemaphore = 0;
        }
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
    }

    private static int importSemaphore(long handle) {
        if (handle == 0 || handle == -1L) {
            throw new IllegalArgumentException("Missing Cloudly semaphore handle");
        }
        int semaphore = glGenSemaphoresEXT();
        try {
            glImportSemaphoreWin32HandleEXT(semaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
            checkGl("import Cloudly semaphore");
            return semaphore;
        } catch (RuntimeException | Error failure) {
            glDeleteSemaphoresEXT(semaphore);
            throw failure;
        }
    }

    private static int createProgram() {
        String source;
        try (InputStream stream = CloudlyGlInterop.class.getResourceAsStream(INPUT_SHADER)) {
            if (stream == null) throw new IllegalStateException("Missing shader " + INPUT_SHADER);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        int linkedProgram = 0;
        try {
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new IllegalStateException("Cloudly input shader: " + glGetShaderInfoLog(shader));
            }
            linkedProgram = glCreateProgram();
            glAttachShader(linkedProgram, shader);
            glLinkProgram(linkedProgram);
            if (glGetProgrami(linkedProgram, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("Cloudly input program: " + glGetProgramInfoLog(linkedProgram));
            }
            return linkedProgram;
        } catch (RuntimeException | Error failure) {
            if (linkedProgram != 0) glDeleteProgram(linkedProgram);
            throw failure;
        } finally {
            glDeleteShader(shader);
        }
    }

    private static void checkGl(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException(operation + " failed: GL 0x" + Integer.toHexString(error));
        }
    }

    private static final class ImportedImage implements AutoCloseable {
        private int memory;
        private int texture;

        private void importImage(CloudlyRuntime.Image image, int format) {
            memory = glCreateMemoryObjectsEXT();
            glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
            glImportMemoryWin32HandleEXT(memory, image.allocationSize(),
                    GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, image.memoryHandle());
            texture = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorageMem2DEXT(texture, 1, format, image.width(), image.height(),
                    memory, image.memoryOffset());
            glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            checkGl("import Cloudly image");
        }

        @Override
        public void close() {
            if (texture != 0) {
                glDeleteTextures(texture);
                texture = 0;
            }
            if (memory != 0) {
                glDeleteMemoryObjectsEXT(memory);
                memory = 0;
            }
        }
    }

    private static final class ImageBinding {
        private final int unit;
        private int name;
        private int level;
        private boolean layered;
        private int layer;
        private int access;
        private int format;

        private ImageBinding(int unit) { this.unit = unit; }

        private void save() {
            name = glGetIntegeri(GL_IMAGE_BINDING_NAME, unit);
            level = glGetIntegeri(GL_IMAGE_BINDING_LEVEL, unit);
            layered = glGetIntegeri(GL_IMAGE_BINDING_LAYERED, unit) != 0;
            layer = glGetIntegeri(GL_IMAGE_BINDING_LAYER, unit);
            access = glGetIntegeri(GL_IMAGE_BINDING_ACCESS, unit);
            format = glGetIntegeri(GL_IMAGE_BINDING_FORMAT, unit);
        }

        private void restore() {
            glBindImageTexture(unit, name, level, layered, layer, access, format);
        }
    }
}
