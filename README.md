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
