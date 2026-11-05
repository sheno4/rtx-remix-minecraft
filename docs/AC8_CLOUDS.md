# AC8 clouds

The `ac8-clouds` branch starts from `sheno4/rtx-remix-minecraft` main commit
`76edfb0`. It keeps the project's Minecraft **1.21.1**, Fabric, Java 21 and
published Remix runtime contract. It is not a Minecraft 26.x port.

The private Windows build bundles Hangar, Mission 3 and Mission 11. In the
Minecraft graphics settings, the AC8 controls enable clouds, select a scene,
move the authored cloud layers by -16000 to +16000 blocks and set their maximum
distance from 1 to 200 km. Chinese labels are included. Loading is asynchronous;
a ready scene replaces the previous one. `/rtx status` reports active, waiting,
disabled or failed cloud state. The AC8 settings use the existing settings
transaction and persistence mechanism.

The active scene's authored solar path also drives Remix's existing sun/moon
lights and celestial mesh orientation, so a scene change does not leave those
lights on the default solar path.

## Rendering boundary

The independently linked Cloudly library runs the original 81 programs and
recipes for density, atmosphere, tracing, reconstruction and foreground-cloud
composition. The prepared private resources come from Caustica's `ac8-main.1`
bundle. It retains the physical foreground-cloud transport and uses Remix's
actual primary depth for terrain occlusion, with projected-depth conversion at
the host boundary.

Terrain convergence uses Remix's resolved distance-fade endpoints, including
world/profile/user overrides, rather than a nominal chunk distance or the cloud
distance setting. The AC8 atmosphere normalization remains independent.

This branch uses the published Remix color/depth interop API without changing
the copied C header or JNI ABI. OpenGL and a UUID-matched Vulkan device share
dedicated images and binary semaphores. No rendered image is read back to the
CPU. Clouds composite before the existing weather, overlays and HUD.

Remix currently exposes its **final display image**, not scene-linear HDR
color. The adapter uses an inverse-Reinhard/Reinhard display bridge around
Cloudly. It cannot recover clipped HDR values or Remix's original exposure.
Cloud reflections, cloud-shaped shadows on world geometry and illumination of
the path-traced scene are not integrated through this display interface. Those
need an extension in the matching Remix runtime. Do not describe this build as
complete integration with Remix's path tracer or as verified AC8 visual parity.

The interop design follows the existing Remix GL synchronization flow and the
external-image/semaphore pattern in the actively maintained, Apache-licensed
[Khronos Vulkan sample](https://github.com/KhronosGroup/Vulkan-Samples/tree/main/samples/extensions/open_gl_interop).
An internal custom compute pass would offer HDR and lighting integration, but
the published C API has no such extension point; its pinned runtime is built
from a branch whose matching source is not public. No speculative C API entry
was added.

## Source and build

Cloudly is LGPL-3.0-or-later and remains an independently replaceable library.
The Apache host source is not a copy of Caustica's renderer. Obtain the library
next to this checkout:

```powershell
git clone --branch rtx-remix-cloudly-runtime https://github.com/sheno4/Caustica.git ../ac8-cloudly-runtime
```

Use JDK 21. An unmodified library uses its checked-in library-owned bridge
programs, verified against source and binary hashes. Rebuilding those shaders
requires Slang 2026.14.1 in `SLANG_SDK` and a Vulkan SDK providing
`Bin/spirv-val.exe` in `VULKAN_SDK`.

```powershell
./gradlew.bat package -Parch=win64 -x test -x fabricTest '-PcloudlyBundleInputRoot=<prepared-private-bundle-directory>'
```

Use `-PcloudlyRuntimeProject=<directory>` for another library checkout. The
bundle input is outside both public source checkouts. It contains game-derived
textures, programs and parameter templates; do not commit it or publish it in
public CI. Source-only builds compile the host and library but do not contain
the authored scenes. The complete private delivery includes those scenes,
Remix's Windows runtime, the JNI bridge, Vulkan/VMA dependencies and the VMA
native library in one Fabric JAR.

Distribution packaging refuses missing private scenes. Public CI has no private
bundle and cannot generate the complete AC8 delivery. The public source build
uses library revision `b6efd87d9751818478575b6963e2a71642eb08dc`.

The nested library carries its LGPL/GPL license texts and corresponding source
archive under `META-INF/cloudly-source/`. It can be rebuilt and replaced
independently. Game-derived private inputs are outside that license grant.

## Validation

Local Java/JNI compilation, bridge SPIR-V compilation/validation and package
integrity are distinct from rendering acceptance. The upstream package task
also invokes its existing JVM tests, including bitmap-based font metrics;
delivery commands explicitly skip these tests to respect the task's testing
limits. No Minecraft launch, GPU rendering, screenshot recognition or
performance measurement has been performed for this port. Package checks do
not prove visual agreement or frame rate.
