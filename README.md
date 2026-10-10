# Cinema R2

独立的 Minecraft 多人影院模组及 React 管理台，当前版本 **0.3.0**，适用于 Minecraft **1.21.1 / Fabric / Java 21**。

网页管理节目、资源、屏幕、播放器策略、影院管理员和下载用量；游戏内屏幕负责播放。客户端使用 libmpv，支持 720p/1080p 视频、带静态画面的音频、图片轮播和 OBS RTMP 转 HLS 直播。

点播在源站与 Cloudflare R2 保存副本；客户端最多 6 路 R2 并行预取未来 600 秒 TS，完整分片暂存磁盘。缺片距离播放不足 40 秒才从 EO 补片，不足 20 秒且仍缺失时使用阿里云兜底。直播通过 CDN 获取 HLS，不上传 R2。

0.3.0 包含 50 座林地露天影院、固定晴朗傍晚、创造观众模式及服务端防破坏限制。影院管理员通过网页授予本次连接的建造权限，不授予 OP；重新连接后需重新授权。

- [中文安装与使用说明](README.zh-CN.md)
- [0.3.0 新功能与场景说明](docs/FEATURES-0.3.0.zh-CN.md)
- [缓存及分发策略](docs/DELIVERY-0.2.8.zh-CN.md)
- [验证记录及实际限制](VALIDATION.md)
- [第三方运行库与许可证](THIRD_PARTY.md)

## MC 服务端接入现有网站后台

如果希望自己的 MC 服务端由项目维护者现有的网站控制，先联系维护者申请接入，取得**私下提供的桥接令牌**并确认切换安排。当前后台只接受一个活动中的 MC 服务端；已有服务端在线时，另一个实例会收到 `409 控制台已连接另一个 MC 服务端`。设置不同的 `serverId` 不能实现多服务器隔离。

1. 安装 Minecraft 1.21.1 Fabric 服务端、匹配的 Fabric API 和 `cinema-r2-0.3.0.jar`。MC 服务端不需要 libmpv、R2 密钥或网站登录密码。
2. 启动一次生成配置，正常停止服务端，然后编辑其工作目录下的 `config/cinemar2-server.json`：

```json
{
  "allowedMediaHosts": [
    "video.mc.cxmlbt-tec.top",
    "live.mc.acgzjd.cn",
    "live.al.mc.acgzjd.cn"
  ],
  "viewDistance": 64,
  "managementUrl": "https://live.mc.acgzjd.cn",
  "managementFallbackUrl": "https://live.al.mc.acgzjd.cn",
  "managementToken": "替换为维护者私下提供的桥接令牌",
  "serverId": "my-cinema",
  "allowLocalMedia": false,
  "protectCinema": true
}
```

3. 将 `managementToken` 替换为真实令牌，`serverId` 改成便于识别的服务端名称。管理地址填写域名根地址，不加 `/api` 或 `/api/bridge/poll`；模组会自动调用该接口。放行服务端到两个管理域名的出站 HTTPS（443），无须向网站开放 MC 的 HTTP 控制端口。
4. 由维护者停止原来连接的 MC 服务端；后台超过约 6 秒没有收到原实例心跳后允许新实例连接。启动自己的服务端，由维护者确认网站显示「Minecraft 已连接」和正确的服务端名称，再在网页创建/管理该世界的屏幕、选择资源并播放。重启服务端可读取修改后的配置。
5. 观众连接的是**你的 MC 游戏地址**，不是网站域名；他们需要同版本模组、Fabric API、Java 21 和单独安装的 libmpv。玩家客户端默认已经配置上述 R2/EO/阿里云媒体域名，旧客户端配置也应与之保持一致。影片和直播由各客户端下载，MC 服务端只同步控制与状态。

`managementToken` 对应网站后台 `settings.json` 的 `bridgeToken`，两者必须一致；空值、未替换的示例或错误令牌无法接入。网页控制另需网站管理密码，是否提供由维护者决定。**桥接令牌具有整个后台的 MC 接入权限，不是每个服务器独立的有限权限；不要公开令牌、带令牌的配置或发给普通观众。** 同时接入多个独立 MC 服务器需要先扩展后台，当前版本不支持。

`protectCinema=true` 会启用专用影院服务器的创造模式、固定时间天气和整个服务端的建造/实体/爆炸保护；接入前请确认适合自己的世界。「搭建/重建场景」会替换主屏周围地形，应先备份世界。接入后台本身不会自动搭建 50 座场景。

如需自行搭建网站，按 [自建后台步骤](README.zh-CN.md#其他机器开发与部署) 创建自己的后台及 `bridgeToken`，并将管理地址、媒体域名和客户端配置一起替换为自己的，不使用维护者后台的令牌。

## 构建

需要 Java 21、Node.js 24 和 FFmpeg/FFprobe。Windows：

```powershell
.\gradlew.bat build
cd console
npm ci
npm run build
npm test
```

Fabric 模组输出到 `build/libs`；网页静态文件输出到 `console/dist`。libmpv 原生库单独安装，不在模组或源码包中分发。媒体集成测试需要真实原生库及测试输入；请按验证文档选择测试，不要将公网播放验证当作压测。

运行时需要自行配置管理密码、桥接令牌、媒体域名和 R2 凭据。仓库不包含这些凭据、视频素材、世界存档或本地部署数据。现有部署说明中的域名、磁盘路径和 IP 是当前环境记录，其他部署需自行替换。

本项目为独立实现，参考 DreamDisplays 的游戏屏幕播放思路及 HiRender P1 的管理工作流。项目代码采用 MIT 许可证，第三方库和播放器依各自许可证使用。
