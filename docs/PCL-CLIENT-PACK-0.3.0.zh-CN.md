# PCL 客户端整合包 · 0.3.0 修订 3 · Windows x64 · Java 21

产物：`dist/Cinema-R2-0.3.0-PCL-Windows-x64-Java21.zip`，包版本 `0.3.0-pcl.3`，模组版本仍为 0.3.0。使用 PCL 支持的 CurseForge `manifest.json` 格式。模组已直接包含在 `overrides/mods`，不需要 CurseForge 账号或 API 下载。播放器原生库使用实例目录下的相对路径。

修订 3 修复游戏内黑屏：Windows/JNA 在实际 PCL 客户端中无法加载带正斜杠的相对 DLL 路径。加载前将显式文件路径转换为规范绝对路径，配置文件仍保持可迁移的相对路径。已在实际 PCL 联机客户端恢复解码和屏幕画面；服务器无需升级协议。

首版错误报告确认 PCL 自动选择 Java 25，旧 Fabric/Mixin 报 `Unsupported class file major version 69` 后崩溃。修订 2 随包携带 Java 21，设置 `VersionArgumentJavaV2:2`（使用版本文件夹中的 Java），并保存迁移完成标记，避免设置被旧配置迁移重置。无需升级模组、服务端或 Fabric API。

## 导入与使用

1. 打开 PCL，在「版本选择 → 导入整合包」选择 ZIP，无需先解压；安装成新实例，保留版本隔离。
2. 等待启动器安装 Minecraft 1.21.1 与 Fabric Loader 0.16.10。Java 21 x64 已在实例 `jre21` 中，PCL 应显示「使用版本文件夹中的 Java」及 Java 21。不要切换回全局自动选择；更换其他启动器时手动选实例内 `jre21/bin/java.exe`。
3. 启动后，在「多人游戏」填入服主提供的 MC 游戏地址。网站/CDN 域名不能代替 MC 游戏地址。
4. libmpv 已随包携带，无需另装 FFmpeg 或 mpv.exe，也无需手工修改 DLL 路径。`cinema-native/windows-x86_64` 和配置应随实例一起移动。

已包含 Cinema 0.3.0、Fabric API 0.116.12+1.21.1、JNA 5.15.0（模组内嵌）、固定版本的 Windows x64 libmpv、Temurin Java 21.0.12.1 x64、客户端配置和第三方许可/来源说明。Minecraft、Fabric Loader 由启动器安装。Java 运行镜像使用现有官方 JDK 的 jlink 生成，保留全部模块及 `jre21/legal`。

没有携带账号、网页登录密码、MC 桥接令牌、R2 凭据、原有存档、影片、缓存、日志或服务器列表。默认媒体域名仍为现有影院 R2、EO 和阿里云；加入其他影院时按服主要求修改。客户端本地服务器配置关闭管理桥及影院保护，防止本地单人地图被自动改写规则；远端服务器保护仍由服主控制。

## 目录结构

```text
manifest.json
README-导入说明.txt
pack-files.sha256.json
overrides/
  mods/
    cinema-r2-0.3.0.jar
    fabric-api-0.116.12+1.21.1.jar
  cinema-native/windows-x86_64/libmpv-2.dll
  jre21/
  config/cinemar2-client.json
  config/cinemar2-server.json
  cinema-licenses/
  PCL/Setup.ini
  PCL/config.json
```

`mpvLibraryPath` 为 `./cinema-native/windows-x86_64/libmpv-2.dll`，`cacheDirectory` 为 `cinema-cache`。PCL 导入 override 时开启版本隔离，并将工作目录设为隔离实例目录，因此移动整合包后不依赖原机器盘符。旧实例若已有自己的配置，应使用新实例导入，避免保留旧绝对路径或未知附加模组。

## 重现打包

在项目根目录执行，输入文件分别使用已验证的 Cinema JAR、Fabric API 和原生库：

```powershell
python tools/build_pcl_pack.py `
  --mod-jar build/libs/cinema-r2-0.3.0.jar `
  --fabric-api "你的路径/fabric-api-0.116.12+1.21.1.jar" `
  --mpv-library "你的路径/libmpv-2.dll" `
  --java-runtime "你的路径/Java21运行镜像"
```

脚本不会读取现有玩家账号或复制实例设置，配置从明确的白名单生成；检查模组版本、原生库 SHA256、PE x64 架构，并生成逐文件校验表和外部 ZIP SHA256。首次构建会读取上游许可文本，之后使用本地缓存。`--output` 可另行指定输出地址。

原生库是未修改的 shinchiro 20261009 构建，保持 mpv/FFmpeg 及依赖各自许可证，不改称 MIT。来源、构建脚本和固定源码版本入口位于包内 `cinema-licenses/SOURCES.json`；进一步分发时需遵守对应上游许可。

## 验证范围

已检查 PCL 源码的 `manifest.json` 识别、Fabric loader 解析、override 复制、版本隔离和工作目录规则。校验 ZIP CRC、逐文件 SHA256、配置示例及清洁目录。将 override 解压至含中文和空格的新目录，使用包内 DLL 实际解码并取得非黑画面、推进播放位置和建立 WASAPI 输出；探针静音运行。另在独立游戏目录用原有官方运行环境启动包内模组，检查图形/声音及标题资源初始化，不加入服务器，也不修改正在运行的游戏。

首版的独立启动测试使用测试脚本明确指定 Java 21，没有覆盖 PCL 自动误选 Java 25 的情况。修订时另用 PCL 界面设置并确认实际 Java 21，然后从 PCL 启动已导入的实例。新 ZIP 的全新界面导入与已导入实例的修复启动是不同检查，不混称。

首次安装仍依赖启动器能下载 Minecraft 和 Fabric；Java 21 已在包内。只有 Windows x64 原生库及运行环境已打包及验证。Java 21 与迁移验证见 `dist/pcl-java21-fix-validation-0.3.0.json`。修订 3 的实际 PCL 联机画面、音频输出、播放推进和原生库路径回归测试见 `dist/pcl-black-screen-fix-validation-0.3.0.json`。
