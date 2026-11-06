/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include "remix_c.h"

#ifdef __cplusplus
extern "C" {
#endif

#define REMIXAPI_CLOUDLY_ABI_VERSION 1
#define REMIXAPI_CLOUDLY_PHASE_PREPARE 1
#define REMIXAPI_CLOUDLY_PHASE_COMPOSITE 2
#define REMIXAPI_CLOUDLY_RESOURCE_LIMIT 64
#define REMIXAPI_CLOUDLY_WORLD_SURFACE_SHADOW 1
#define REMIXAPI_CLOUDLY_WORLD_RAY_TRANSPORT 2
#define REMIXAPI_CLOUDLY_CALLBACK_FAILED 0x80000000u

typedef struct remixapi_CloudlyHostDataHeader {
  uint32_t size;
  uint32_t abiVersion;
  float sceneRadianceScale;
  uint32_t flags;
} remixapi_CloudlyHostDataHeader;

typedef struct remixapi_CloudlyImage {
  uint64_t image;
  uint64_t view;
  uint32_t format;
  uint32_t width;
  uint32_t height;
  uint32_t depth;
} remixapi_CloudlyImage;

// Callbacks execute on the command-stream thread. The command buffer belongs to
// Remix and must not be ended, reset or submitted by the callback.
// Image layouts are GENERAL. Color is scene-linear BT.709; depth is the reversed
// projected depth of the first physical camera hit, with zero for environment.
// cameraRelativeFromClip is unjittered, with downward framebuffer Y and reversed Z.
typedef struct remixapi_CloudlyFrame {
  uint32_t size;
  uint32_t abiVersion;
  uint32_t phase;
  uint32_t reserved;
  uint64_t frameIndex;
  double timeSeconds;
  uint64_t instance;
  uint64_t physicalDevice;
  uint64_t device;
  uint64_t commandBuffer;
  uint32_t queueFamily;
  uint32_t apiVersion;
  uint64_t hostData;
  uint32_t hostDataSize;
  uint32_t flags;
  remixapi_CloudlyImage color;
  remixapi_CloudlyImage physicalDepth;
  double cameraPosition[3];
  float cameraRelativeFromClip[16];
  float jitter[2];
  float sceneRadianceScale;
  float terrainFadeStart;
  float terrainFadeEnd;
  float reservedFloat;
} remixapi_CloudlyFrame;

typedef struct remixapi_CloudlyDescriptor {
  uint32_t binding;
  uint32_t descriptorType;
  remixapi_CloudlyImage image;
  uint64_t sampler;
  uint64_t buffer;
  uint64_t bufferSize;
} remixapi_CloudlyDescriptor;

// For images, buffer packs viewType:8, baseMip:8, levelCount:8,
// baseLayer:16, layerCount:16 (low bits first). bufferSize packs the storage
// mip count:32 and array-layer count:32. For buffers, sampler is byteOffset.

// The callback retains every returned image, sampler, buffer and address until
// its lease retires. World-space fields use the host's coordinates and units.
// surfaceTransmittance stores texture axes X, negative host Z, host Y. Bounds
// describe the axis-aligned host-space box before that texture-coordinate swizzle.
typedef struct remixapi_CloudlyWorld {
  uint32_t size;
  uint32_t abiVersion;
  uint32_t flags;
  uint32_t descriptorCount;
  remixapi_CloudlyImage surfaceTransmittance;
  float shadowOrigin[4];
  float shadowExtent[4];
  float lightDirection[4];
  uint64_t parametersAddress;
  uint64_t cameraAddress;
  uint64_t bindingsAddress;
  uint64_t atmosphereParametersAddress;
  remixapi_CloudlyDescriptor descriptors[REMIXAPI_CLOUDLY_RESOURCE_LIMIT];
} remixapi_CloudlyWorld;

// A nonzero lease must be returned whenever recording retains GPU resources.
// Retire is called after those commands finish, possibly on a retirement worker.
typedef uint64_t (REMIXAPI_CALL* PFN_remixapi_CloudlyRecord)(
  const remixapi_CloudlyFrame* frame, remixapi_CloudlyWorld* world, void* userData);
typedef void (REMIXAPI_CALL* PFN_remixapi_CloudlyRetire)(uint64_t lease, void* userData);
typedef void (REMIXAPI_CALL* PFN_remixapi_CloudlyRelease)(void* userData);

typedef struct remixapi_CloudlyCallbacks {
  uint32_t size;
  uint32_t abiVersion;
  PFN_remixapi_CloudlyRecord record;
  PFN_remixapi_CloudlyRetire retire;
  PFN_remixapi_CloudlyRelease release;
} remixapi_CloudlyCallbacks;

// Replace or clear a registration. Release occurs only after its last GPU lease
// retires. hostData is copied synchronously and captured in Submit order.
typedef remixapi_ErrorCode (REMIXAPI_CALL* PFN_remixapi_RegisterCloudly)(
  const remixapi_CloudlyCallbacks* callbacks, void* userData);
typedef remixapi_ErrorCode (REMIXAPI_CALL* PFN_remixapi_SetCloudlyFrameData)(
  const void* hostData, uint32_t size);

#ifdef __cplusplus
}
static_assert(sizeof(remixapi_CloudlyImage) == 32);
static_assert(sizeof(remixapi_CloudlyFrame) == 264);
static_assert(sizeof(remixapi_CloudlyDescriptor) == 64);
static_assert(sizeof(remixapi_CloudlyWorld) == 4224);
#endif
