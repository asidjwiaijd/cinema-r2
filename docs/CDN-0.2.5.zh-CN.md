# 阿里云 CDN 配置与维护 · 2026-10-10

管理备用入口：https://live.al.mc.acgzjd.cn 。与 EO https://live.mc.acgzjd.cn 共用 192.168.8.244:3210 后台和 /mnt/cinema 独立盘内的数据。

## 源站与 TLS

- CNAME：live.al.mc.acgzjd.cn.w.kunlunaq.com。
- HTTPS 回源：home.cxmlbt-tec.top，端口 39994。该源域名只有 AAAA；IPv6 回源优先已开启。
- 默认回源 Host 与 SNI：home.cxmlbt-tec.top。
- 边缘证书：cinema-live-al-20261010，Let's Encrypt YR2 签发，RSA 2048，单域名 live.al.mc.acgzjd.cn。
- 有效期：2026-10-10 10:51:36 至 **2027-01-08 10:51:35（UTC+8）**。
- HTTP 301 转 HTTPS；HTTP/2、OCSP 开启；仅启用 TLS 1.2/1.3。
- HTTPS 请求计费已按用户授权开启。请求计费与流量包不同，具体以阿里云账单为准。

## 缓存

|匹配规则|TTL|优先级|
|---|---:|---:|
|目录 /api/|0 秒|99|
|目录 /media/|86400 秒|95|
|后缀 m3u8,mpd|1 秒|90|
|后缀 ts,mp4,m4s,m4a|86400 秒|80|
|目录 /|0 秒|1|

保留源站 no-store；Range 回源为 512KB。直播使用唯一微秒片名。不要把 API、管理 HTML 或实时直播清单缓存一天。媒体变更使用新的资源目录；同名覆盖需主动刷新旧缓存。

## 路由和控制

点播：R2 → EO → 阿里云；直播：EO → 阿里云，R2 字节为零。R2/EO 超时默认各 6 秒，阿里云 15 秒；失败源冷却 30 秒。每段/每个 MP4 Range 块独立尝试，切源保持同一个 libmpv 解码器及缓存。策略接口也按 EO → 阿里云读取，两条都失败、策略过期则停止 CDN 下载。两家 CDN 用量合并统计，预算为辅助保护，不能代替云账单硬封顶。

MC 服务端 managementUrl 为 EO，managementFallbackUrl 为阿里云；客户端 backupMediaBase 为阿里云。更改后台域名时也要同步客户端配置和服务端 allowedMediaHosts。两家 CDN 回同一源站，不能解决源站整体离线问题。

阿里云 CDN 不支持 PATCH/DELETE，网页使用已登录保护的 POST /api/resources/:id/update、/api/screens/:id/update、/api/screens/:id/remove；原 PATCH/DELETE 接口仍保留供直接维护访问。登录来源名单同时包含两个 HTTPS 域名，未使用通配符。

## 证书续期（尚未自动化）

请在到期前操作，建议提前 30 天。以下文件位于本机 **E:/mc/cinema-r2/.tools/cdn-tls**，均未加入源码包。

1. 在 E:/mc/cinema-r2 执行 `python .tools/cdn-tls/acme-certificate.py prepare`，创建新订单；账户及域名专用密钥继续保留。查看脚本产生的 DNS 验证文件中的新 TXT 值。
2. 在 acgzjd.cn DNS 修改 `_acme-challenge.live.al.mc.acgzjd.cn` TXT 为新值，等待公共 DNS 生效。不要继续使用旧订单 TXT。
3. 执行 `python .tools/cdn-tls/acme-certificate.py finish`，完成 DNS-01、签发和保存 fullchain.pem。若校验未通过，确认 TXT 后再运行，勿绕过 TLS 校验。
4. 阿里云 CDN → live.al.mc.acgzjd.cn → HTTPS 配置，替换证书为新的 fullchain.pem 和该域名 key.pem；检查证书域名、有效期、保存后的实际边缘 TLS。
5. 更新本说明中的到期时间。避免打印、提交或发布私钥、ACME 账户、后台设置和凭据文件。

私钥路径：live.al.mc.acgzjd.cn.key.pem；完整链：fullchain.pem。只把这个域名专用密钥交给阿里云，勿上传 ACME 账户私钥。

## 验收

公网可信 TLS、登录、R2/EO/阿里云 MP4 Range 样本一致、阿里云缓存命中验证通过。模拟 MC 主控制地址不可达后，备用控制指令约 1906 ms 返回成功。真实公网 libmpv 四项测试通过，包括双主线路不可达的阿里云点播及 EO 不可达的阿里云直播；画面、声音和直播帧推进正常。直播清单实测 TTL=1，6 秒后序号推进；分片 Range 返回 206，TTL=86400。

证据：dist/paid-cdn-validation-0.2.5.json、dist/cdn-edge-certificate.png、dist/cdn-cache-configured.png。未进行 50–100 人负载测试。
