# Third-party playback runtime

- libmpv native Windows x64 build: shinchiro/mpv-winbuild-cmake release 20261009, mpv git 36abaa32d0. Source/build recipes: https://github.com/shinchiro/mpv-winbuild-cmake and https://github.com/mpv-player/mpv . The upstream installation page links these Windows builds: https://mpv.io/installation/ . Native runtime is distributed separately, not embedded in this mod JAR; upstream build/dependency licenses apply (the standard Windows build includes GPL components).
- Archive: mpv-dev-x86_64-20261009-git-36abaa32d0.7z. SHA256: 8a15fc07996a33235778256157151c42cbaceee976f0a98860f55047856935b2 (matched release asset digest before extraction).
- Java binding: JNA 5.15.0, Apache-2.0 / LGPL-2.1 dual licensed, https://github.com/java-native-access/jna . Bundled by Fabric Loom include.
- Runtime interfaces follow upstream client.h and render.h; this project implements its own small JNA binding, not a copied player implementation.

The optional Windows x64 PCL client ZIP includes the unchanged native DLL as a separate file under `cinema-native/windows-x86_64/`; it remains outside the mod JAR. That pack includes upstream mpv/FFmpeg license texts, the Fabric API license and pinned source/build provenance. Cinema's MIT license does not relicense the native runtime. See `docs/PCL-CLIENT-PACK.zh-CN.md`.

PCL pack revision 2 additionally includes a Java 21.0.12.1 x64 runtime image generated with Temurin's jlink from the existing verified JDK. It includes all JDK modules and the upstream notices under `jre21/legal/`; OpenJDK/Temurin retains its GPLv2 with Classpath Exception and component licenses. Upstream distribution/source release: https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1 .

The 0.4.0 / Minecraft 26.1.2 pack includes Temurin 25.0.4.1+1 Windows x64 (full modules, without jlink trimming), with upstream notices retained under `jre25/legal/` and `jre25/NOTICE`. Source distribution: https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1 .

## Optional Cinema minimal native runtime · 2026-10-22

The `0.4.1-pcl.2` Slim pack uses a custom cross-compiled Windows x64 DLL, not the unchanged shinchiro DLL described above. It retains the same mpv/FFmpeg revisions and links pinned libplacebo, libass, FreeType, HarfBuzz, FriBidi, dav1d, zlib and OpenSSL 3.5.9 statically. Vulkan-Headers and fast_float are build/header dependencies. Windows system DLLs remain external; no third-party runtime DLL is required.

This build enables FFmpeg GPL version3 for compatibility with OpenSSL 3's Apache-2.0 license. The native combination is distributed under GPLv3 and component notices, independently of Cinema's MIT license. The corresponding-source ZIP `cinema-libmpv-minimal-20261022-sources.zip` contains every pinned component archive, checksums, recipes, notices and all three build patches; distribute that ZIP alongside the binary/client pack. The GCC runtime and MinGW notices are retained as well.

`tools/native/sources.lock.json` pins all upstream sources. `mpv-minimal.patch` fixes static C++ and D3D11-helper linking with GPU output disabled; `ffmpeg-long-timeout.patch` retains the player's long loopback timeout with a 64-bit TCP timeout field; `ffmpeg-windows-trust.patch` connects OpenSSL to the Windows root certificate store without bypassing verification. The per-binary `native-build.json` records the exact DLL hash, enabled formats, imports and patch checksums. See `tools/native/README.zh-CN.md` for reproduction and validation.
