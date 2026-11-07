# Cinema libmpv 精简构建

目标是 Windows 10/11 x64 的影院客户端。保留原 mpv `36abaa32d0`、FFmpeg `538d10d18` 源码版本、软件 render API、WASAPI、D3D11/DXVA2 硬件解码与软件回退、网络/磁盘缓存、HTTPS 证书验证及 HLS/TS/fMP4。

保留 H.264、HEVC（包括 10-bit）、VP8/9、dav1d AV1、常见图片、AAC/MP3/FLAC/Opus/Vorbis/AC3/EAC3/PCM 等解码。裁掉媒体编码器、命令行播放器、Lua/JavaScript、光盘、设备采集、GPU 输出与着色器编译器、无关网络协议和多余音频输出。OBS 仍由服务器 FFmpeg 转换为 HLS；客户端不直接接收 RTMP。新运行库不保证播放未列出的罕见格式，原完整版可用于回退。

HTTPS 使用静态 OpenSSL 3.5.9 LTS，启用 Windows 系统证书存储并保留自定义 CA 文件；不依赖 Schannel 的在线吊销检查。三份可审阅补丁修改 mpv Meson 的 C++/D3D11 辅助链接条件、FFmpeg TCP 微秒超时字段为 64 位，并将 Windows 根证书存储接入 OpenSSL，兼容现有播放器 3660 秒的回环读取等待。媒体解码与渲染逻辑没有裁改；不关闭证书和主机名验证。OpenSSL 3 的 Apache-2.0 许可使本次 FFmpeg GPL 构建启用 version3，原生组合按 GPLv3 及组件许可分发。

## 在 Ubuntu 24.04 / WSL 重现

安装工具：`gcc-mingw-w64-x86-64-posix g++-mingw-w64-x86-64-posix mingw-w64-tools ninja-build cmake pkg-config nasm python3-pip perl make git`。构建脚本不使用 sudo，不修改生产数据；Python 构建工具安装在独立工作目录。

```bash
bash tools/native/build-libmpv-minimal.sh
```

默认工作目录为 `$HOME/cinema-native-build-20261022`，可用 `CINEMA_NATIVE_BUILD_ROOT` 指定新目录，`CINEMA_NATIVE_JOBS` 默认 8。源码 archive 和 SHA256 在 `sources.lock.json` 固定；缓存放在仓库 `.tools/native-build/archives`。对应源码 ZIP 中的 `archives/` 可提前复制到此缓存后离线验证/解包。Python 构建依赖仍需预先安装或可访问 PyPI。

构建结束后（在 Linux / WSL 中）收集产物：

```bash
export PYTHONPATH="$HOME/cinema-native-build-20261022/python"
python3 tools/native/collect_runtime.py \
  --work "$HOME/cinema-native-build-20261022" \
  --output /你的产物目录/libmpv-minimal-20261022 \
  --source-output /你的发布目录/cinema-libmpv-minimal-20261022-sources.zip
```

产物包含单个 `libmpv-2.dll`、源版本/功能/编译配置/导入依赖清单及所有链接组件的许可证。DLL 只依赖 Windows 系统 DLL；打包器按 SHA256 校验构建清单和许可证。软件视频渲染仍由 Minecraft 上传纹理，精简不会替换播放和缓冲架构。

分发 DLL 或精简客户端包时，请一并提供同版本 `cinema-libmpv-minimal-20261022-sources.zip`（包含固定版本源码、构建脚本和许可证），保留 `cinema-licenses/`。原生库继续遵循 mpv/FFmpeg 的 GPL 及组件各自许可，不能按 Cinema 的 MIT 重新许可。

验收使用现有媒体集成测试和 `NativeRuntimeTest` 格式/直播矩阵，设置 `CINEMA_MPV_LIBRARY` 为新 DLL，`CINEMA_NATIVE_MATRIX=true`，然后运行 `gradlew test -PintegrationTest=true`。仅构建成功不代表可替换正式运行库，必须先通过这些播放测试。

通过后可使用 `tools/build_pcl_pack.py --native-manifest 产物目录/native-build.json --mpv-library 产物目录/libmpv-2.dll --fabric-api 你的FabricAPI.jar --java-runtime 你的Java25目录`，生成 `0.4.1-pcl.2` 精简包。Java 和模组版本不变。
