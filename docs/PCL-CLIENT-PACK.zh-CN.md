# PCL 客户端整合包 · 0.4.7 · Minecraft 26.1.2 · Windows x64

构建产物：`dist/Cinema-R2-0.4.7-MC26.1.2-PCL-Windows-x64-Java25-Slim.zip`，包版本 `0.4.7-pcl.2`，包含 Java 25 和已验证的精简 libmpv。支持 100 格宽屏幕、默认 256 格观看和远处声音；距离修复需要服务端也更新至 0.4.3。0.4.7 包含 Iris 光影兼容修复；已有实例可直接替换客户端 JAR，无需重新导入整合包。

DLL 约 25.7 MiB，原完整版约 115.3 MiB。保留常见格式与硬件解码，移除编码器、脚本、光盘与多余输出。原生源码与构建方式见 [精简构建](../tools/native/README.zh-CN.md)，再次分发时同时提供 `cinema-libmpv-minimal-20261022-sources.zip`。

## 导入与使用

1. PCL → 版本选择 → 导入整合包，选择 ZIP，安装为独立实例并保留版本隔离。
2. 等待启动器安装 Minecraft 26.1.2 与 Fabric Loader 0.19.5。Java 25 已随包携带，选择「使用版本文件夹中的 Java」。
3. 多人游戏连接服主提供的游戏地址；整合包不预置游戏服务器地址。
4. 其他启动器选择实例 `jre25/bin/java.exe`。libmpv 已在 `cinema-native/windows-x86_64/libmpv-2.dll`，无需另装播放器或 FFmpeg。

包含 Cinema 0.4.7、Fabric API 0.155.3+26.1.2、内嵌 JNA、自行构建的 Windows x64 精简 libmpv、Temurin 25.0.4.1+1 运行环境和第三方许可。Java 保留完整模块与 legal、NOTICE；未使用 jlink 裁剪。Minecraft 和 Fabric Loader 由启动器下载。Iris 和 Sodium 不属于必装依赖，也不由构建器自动添加。

配置中的 DLL 和缓存使用相对路径，运行时将 DLL 转为规范绝对路径后交给 Windows/JNA 加载。程序使用 Minecraft 新版图形接口批量上传视频帧，仍保留 libmpv 大缓存与 6 路 R2 预取。

不含玩家账号、网站密码、MC 桥接令牌、R2 凭据、服务器 OP 名单、世界、影片、缓存或日志。客户端本地服务器配置关闭网页桥和影院保护，保护由远端服务器决定。只打包并验证 Windows x64。

## 重现构建

```powershell
python tools/build_pcl_pack.py `
  --mod-jar build/libs/cinema-r2-0.4.7.jar `
  --fabric-api "你的路径/fabric-api-0.155.3+26.1.2.jar" `
  --mpv-library "你的路径/libmpv-2.dll" `
  --native-manifest "你的路径/native-build.json" `
  --java-runtime "你的路径/Java25运行环境"
```

构建器检查 Java 25、模组版本、Fabric API、DLL SHA256 和 x64 架构，并生成逐文件校验表和外部 ZIP SHA256。上游 native、Java 与 Fabric 保留各自许可，来源记录位于 `cinema-licenses/SOURCES.json`。

0.3.0 整合包的历史说明见 [旧版文档](PCL-CLIENT-PACK-0.3.0.zh-CN.md)。玩家升级时也需使用 26.1.2 客户端。
