# Third-party playback runtime

- libmpv native Windows x64 build: shinchiro/mpv-winbuild-cmake release 20261009, mpv git 36abaa32d0. Source/build recipes: https://github.com/shinchiro/mpv-winbuild-cmake and https://github.com/mpv-player/mpv . The upstream installation page links these Windows builds: https://mpv.io/installation/ . Native runtime is distributed separately, not embedded in this mod JAR; upstream build/dependency licenses apply (the standard Windows build includes GPL components).
- Archive: mpv-dev-x86_64-20261009-git-36abaa32d0.7z. SHA256: 8a15fc07996a33235778256157151c42cbaceee976f0a98860f55047856935b2 (matched release asset digest before extraction).
- Java binding: JNA 5.15.0, Apache-2.0 / LGPL-2.1 dual licensed, https://github.com/java-native-access/jna . Bundled by Fabric Loom include.
- Runtime interfaces follow upstream client.h and render.h; this project implements its own small JNA binding, not a copied player implementation.
