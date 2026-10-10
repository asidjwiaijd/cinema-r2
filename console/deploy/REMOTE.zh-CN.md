# 192.168.8.244 部署说明

管理网站：https://live.mc.acgzjd.cn；备用：https://live.al.mc.acgzjd.cn（局域网维护入口 http://192.168.8.244:3210）。登录密码保存在本机 `E:\mc\cinema-r2\.tools\remote-deploy\管理密码.txt`，远端只保存加盐密码摘要。两个公网入口共用后台、资源与密码，浏览器需在各入口分别登录。登录有效期 12 小时，网站重启后需重新登录。

CDN 管理入口：https://live.mc.acgzjd.cn/ 。部署环境 `CINEMA_PUBLIC_ORIGIN=https://live.mc.acgzjd.cn`，`CINEMA_ALLOWED_ORIGINS` 包含两个公网 HTTPS 入口和 http://192.168.8.244:3210。多个受信任来源用逗号分隔，不使用通配符。已验证经 CDN 登录、状态 API 和 MP4 Range 预览，匿名状态请求仍返回 401；API 响应包含 `Cache-Control: no-store`。

独立 32 GiB 磁盘 `/dev/sdd` 挂载到 `/mnt/cinema`，UUID 写入 `/etc/fstab`。网站、Node/FFmpeg、上传原片、HLS、副本和持久配置都在 `/mnt/cinema/cinema`。服务器缺失挂载时，网站服务无法启动，避免写入系统盘。初始化前的 fstab 备份保存在 `/etc/fstab.cinema-backup.*`。

`cinema-console.service` 是 cxmlbt 的 systemd 用户服务，已开启启动与故障重启；该账户已启用 linger。常用操作：

```sh
systemctl --user status cinema-console.service
systemctl --user restart cinema-console.service
journalctl --user -u cinema-console.service -n 50 --no-pager
df -h /mnt/cinema
```

上传单文件最多 4 GiB。媒体处理串行执行，上传前按文件体积的 3 倍及额外 1 GiB 检查剩余空间。R2 的 8 GB 桶存储预算继续生效；归档资源保留文件，不释放本地和 R2 空间。

当前版本 0.3.0：只有 MC 服务端仍在 Windows。OBS 接收和 FFmpeg 已迁到 192.168.8.244，HLS 直接落在独立盘。双击 `E:\mc\cinema-r2\start-console.bat` 只打开网站；双击 `E:\mc\cinema-server\start-cinema.bat` 启动 MC，不再启动直播后台或 SSH 隧道。旧 `tools/Start-CinemaTunnel.ps1` 仅留档，不应再运行。

连接路径：

```text
MC → https://live.mc.acgzjd.cn/api/bridge/poll → EO → 192.168.8.244:3210
OBS → rtmp://192.168.8.244:1935/live → Linux FFmpeg → /mnt/cinema/cinema/data/live
浏览器预览 → 受登录保护的 /api/preview → 服务器 HLS
游戏点播 → 私有播放器适配器 → 6 路 R2 并行预取未来 600 秒 TS → 完整磁盘分片 → 顺序解码；缺片 <40 秒 EO、<20 秒阿里云兜底
游戏直播 → 私有播放器适配器 → EO live.mc.acgzjd.cn/live/* → 阿里云 live.al.mc.acgzjd.cn/live/* → 源站 → 服务器 HLS
```

网页点播预览优先读取源站本地副本，通过受登录保护的 API；MC 点播通过 R2 并行 TS 预取和逐片截止时间策略读取。媒体 `/media/:资源ID/:文件名` 和 `/live/:直播ID/:文件名` 是公开播放路径，管理 API 保持登录保护，桥接仅接受原有令牌。`/api/delivery/policy` 与签名会话用量上报为公开的非管理接口。直播不上传 R2，观众使用公网域名。MC 管理/直播源站均不依赖 Windows SSH 隧道。下列 0.2.4/0.2.5 记录为历史，当前配置以 0.3.0 为准。

0.2.4 验证：四项点播资源双副本校验通过；清单、TS、MP4 Range 从 R2 与 CDN 读取的样本 SHA256 相同。真实 libmpv 点播只产生 R2 媒体下载；实时 RTMP→HLS→公网 CDN→libmpv 画面和声音连续，R2 字节为零。公网 MC configure 执行回执约 579 ms。Node 12 项测试通过，分片/MP4 故障回退和关闭策略测试通过；原生故障回退不更换解码器或清空缓存。

资源库可手动同步，后台启动/每小时自动同步；发现冲突不覆盖。CDN 用量为 MC 客户端辅助上报，非账单或硬封顶；EO 免费计划官方支持范围排除视频分发，见主 README。此次仅切换管理及媒体 URL，MC 游戏服务仍为本机回环地址，未进行两台真实玩家或 50–100 人负载测试。

敏感文件在 `data/settings.json`、`data/auth.json`，目录 700、文件 600。不要公开该目录或将包含凭据的部署归档发布到源码仓库。MC URL 迁移前配置备份在 `E:\mc\cinema-server\config\cinemar2-server.before-remote.json`；启动脚本备份在 `.tools`。

## 0.2.5 阿里云备用接入（历史）

远端网站及本机客户端/服务端已经升级 0.2.5。CINEMA_ALLOWED_ORIGINS 在原有 EO/局域网来源基础上增加 https://live.al.mc.acgzjd.cn；管理 POST 接口仍要求登录和来源校验。MC managementFallbackUrl 配置为该域名，主控制链路故障后自动回退。同步副本和直播隧道保持原有方式，不向 R2 发布直播。

阿里云 HTTPS 回源 home.cxmlbt-tec.top:39994（只有 AAAA，IPv6 优先），Host/SNI 同源域名。边缘证书到 2027-01-08 10:51:35（UTC+8），未自动续期；缓存与续期见 ../../docs/CDN-0.2.5.zh-CN.md。API/管理页不缓存，直播清单 1 秒，点播及视频片段 1 天。

真实公网备用控制约 1906 ms 返回确认；三源 MP4 Range 样本一致，阿里云缓存命中。真实原生点播/直播及 EO 故障回退四项全部通过，直播 R2 字节为零。验证文件 dist/paid-cdn-validation-0.2.5.json。升级前应用、settings.json 与环境备份位于 /mnt/cinema/cinema/deploy/backup-0.2.5-*，其中配置备份含敏感凭据，请只留在私有磁盘。

## 0.2.6 服务器直播、玩家与流量管理

`deploy/cinema.env` 移除 CINEMA_LIVE_AGENT_URL，设置 CINEMA_LIVE_AUTOSTART=1；服务启动时自动监听。`data/settings.json` 的 live.host 为 `::`，live.port 为 1935，live.ingestUrl 为 `rtmp://192.168.8.244:1935/live`，publicBase 为 EO 公网域名。Node 双栈监听已实测 Windows 局域网连接成功。FFmpeg/FFprobe 使用服务器 runtime/bin 包装器。原 OBS 推流密钥已从旧接收器迁移，无需更换密钥。

公网 RTMP 未设置路由器/TCP 映射，仅完成用户指定的局域网迁移验证。网站 HTTPS CDN 负责 HLS 分发，不是 RTMP 接收代理。媒体双副本和公网回源设置保留原配置，直播不进入 R2。

新增 /api/players、玩家指定操作、/api/traffic 与 CSV 均受管理员登录保护。玩家历史保存在 data/players.json，流量在 data/delivery-usage.json，升级保留旧预算账目，旧 CDN 用量显示未分类。只有新客户端带有玩家用量归属；用量不是云账单或硬封顶。操作支持安全传送、踢出、原生封禁/解封，不提供任意命令或 OP 权限。

升级前备份 /mnt/cinema/cinema/deploy/backup-0.2.6-1791606501 包含旧应用、settings 和环境，均含私有配置；不要对外分发。回滚必须停服务后恢复相匹配的应用、环境与 settings，旧直播架构还需恢复 Windows 接收器及隧道，不能只恢复旧应用文件。证据见 dist/server-live-validation-0.2.6.json、operations-validation-0.2.6.json；使用说明见 ../../docs/OPERATIONS-0.2.6.zh-CN.md。

## 0.2.7 缓存驱动点播

网页、客户端与服务端已升级。新增 delivery.vodStartupCdnSeconds=60 和 vodEmergencyCdnSeconds=30；预算、原 OBS 密钥、双副本及直播架构保持原配置。安装后的客户端公网验收见 dist/installed-client-validation-0.2.7.json，完整策略见 ../../docs/DELIVERY-0.2.7.zh-CN.md。升级备份 /mnt/cinema/cinema/deploy/backup-0.2.7-1791614425 含私有配置，不可发布。

## 0.2.8 并行预取部署

网站运行代码与本机构建一致，默认 TS 策略 600/40/20 已生效；MP4/旧客户端 60/30 兼容参数保留。本次部署备份：`/mnt/cinema/cinema/deploy/backup-0.2.8-1791617039`。RTMP 1935 继续等待推流，直播不进入 R2。客户端、服务端同步安装 0.2.8；详见 ../../docs/DELIVERY-0.2.8.zh-CN.md。

## 0.3.0 影院场景及新资源类型

React 后台已更新 0.3.0。视频、音频、图片统一由 Linux FFmpeg 生成 MP4/HLS，再按原有预算同步 R2；原始音频/图片不通过导入链接处理。MC 网页桥新增绑定连接的影院管理员授予/撤销和固定场景搭建命令，不授予 OP。主世界 50 座林地影院已搭建；整个专用服务器启用保护、创造模式及固定晴朗傍晚。管理员重连后需重新授权。详见 ../../docs/FEATURES-0.3.0.zh-CN.md。
