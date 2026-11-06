# AC8 clouds

The `ac8-clouds` branch starts from `sheno4/rtx-remix-minecraft` main commit
`76edfb0`. It keeps the project's Minecraft **1.21.1**, Fabric, Java 21 and
Remix API 0.16 contract. It is not a Minecraft 26.x port. The native HDR path
requires the matching custom runtime, including the Cloudly recording extension.

The private Windows build bundles Hangar, Mission 3 and Mission 11. In the
Minecraft graphics settings, the AC8 controls enable clouds, select a scene,
move the authored cloud layers by -16000 to +16000 blocks and set their maximum
distance from 1 to 200 km. Chinese labels are included. Loading is asynchronous;
a ready scene replaces the previous one. `/rtx status` reports active, waiting,
disabled or failed cloud state. The AC8 settings use the existing settings
transaction and persistence mechanism.

The render thread captures a ready scene before submitting its sun/moon lights.
The same scene index is acknowledged in the native frame packet, so asynchronous
preparation does not publish a different sky after its lights were submitted.
The authored solar path, radiance, color and angular size drive the distant
lights. The ordinary Minecraft celestial meshes are removed while Cloudly runs.

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

The JNI bridge registers a command-stream recorder through
`remixapi_RegisterCloudly`; `remixapi_SetCloudlyFrameData` copies immutable
game state before Submit. Cloudly borrows Remix's Vulkan device and command
buffer. Its library owns its allocations and descriptors, and GPU completion
leases keep them alive until their exact native uses finish.

The PREPARE phase records the original density-dependent surface transmittance
before world light tracing. Distant-light samples read that field at their
world position; the old global rain attenuation is not applied on top of it.
The COMPOSITE phase runs after reconstruction and upscaling, before exposure,
bloom and display mapping. Both sides use scene-linear BT.709 directly, without
an inverse-Reinhard/Reinhard display conversion. Physical first-hit reversed
depth remains separate from reconstruction and PSR guides.

The custom-runtime migration is under development. World-space ray transport
for sky reflections and indirect illumination must be connected before a new
complete package is delivered. Compilation does not establish AC8 visual parity.
The earlier `.2` package uses the display-only compositor; these source changes
must not be packaged with its stock runtime.

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
Gradle composite build links that source directly. Stage a matching custom
native runtime before packaging; a stock SDK lacks the recording exports. The
bundle input is outside both public source checkouts. It contains game-derived
textures, programs and parameter templates; do not commit it or publish it in
public CI. Source-only builds compile the host and library but do not contain
the authored scenes. The complete private delivery includes those scenes,
Remix's Windows runtime, the JNI bridge, Vulkan/VMA dependencies and the VMA
native library in one Fabric JAR.

Distribution packaging refuses missing private scenes. Public CI has no private
bundle and cannot generate the complete AC8 delivery. The public source build
depends on library version `0.1.0-remix.3`.

The nested library carries its LGPL/GPL license texts and corresponding source
archive under `META-INF/cloudly-source/`. It can be rebuilt and replaced
independently. Game-derived private inputs are outside that license grant.

## Validation

Local Java/JNI compilation, bridge SPIR-V compilation/validation and package
integrity are distinct from rendering acceptance. The upstream package task
also invokes its existing JVM tests, including bitmap-based font metrics;
delivery commands explicitly skip these tests to respect the task's testing
limits. The custom-runtime migration has not been launched in Minecraft or
validated through GPU rendering. No screenshot recognition is used. Feedback
from the installed `.2` build applies to its older display-only path. Package
checks do not prove visual agreement or frame rate.
