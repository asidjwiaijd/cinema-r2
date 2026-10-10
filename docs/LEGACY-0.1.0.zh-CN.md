> 历史版本 0.1.0 留档。当前 0.2 已停用 Worker，点播使用 R2 公共域名，直播本机直发；以根目录 README 为准。

# Cinema R2 · Minecraft 多人影院

独立 Fabric 模组首版，参考 DreamDisplays 的世界内屏幕与共享播放思路。目标使用场景：50–100 人观看 R2 中的 2–3 GB MP4。客户端各自下载并解码，MC 服务器只同步链接、时间轴和控制操作。

**当前状态：可编译的 0.1.0 原型，附私有 R2 网关与上传预算工具。尚未部署到真实 Cloudflare 账户，也未完成多人实机、画面/音频和 100 人负载验收。不能将编译与组件测试等同于生产可用。**

## 功能与安装

- Minecraft **1.21.1**，Java **21**，Fabric Loader **0.16.10+**，Fabric API **0.116.12+1.21.1**；服务器与所有观众客户端都安装 `cinema-r2-0.1.0.jar`。不依赖 DreamDisplays。
- 每个客户端安装 FFmpeg，支持 HTTPS、H.264 和 AAC；默认从 PATH 找 `ffmpeg`，也可在 `config/cinemar2-client.json` 中设置完整路径。服务器不需要 FFmpeg。
- 世界内 16:9 屏幕，可设宽度 4–32 格，默认 12 格；控制器是屏幕下沿的中心，屏幕向上延伸。
- 播放、暂停、按秒跳转、停止与清空；只有放置控制器的玩家或 OP 2 级以上管理员能操作，普通观众可以打开菜单及本地静音。
- 服务器每两秒给同维度附近观众发送当前进度；晚加入可追到当前时间，客户端通过往返时间校准服务器时钟。
- 默认 64 格观影范围；客户端同时只解码最近的一块已载入视频的屏幕，离开范围、换维度或断线会关闭解码进程。
- 为控制读取次数，自动纠偏阈值为 2 秒，间隔至少 15 秒，每次人工操作后最多自动纠偏三次。连接失败不会无限重试。首版不承诺逐帧同步；不同机器的缓冲和独立音视频解码可能产生可见延迟，需要实机调整。
- 显示采用 640×360、24 fps，本地音量按距离衰减；音频通过系统 Java Sound 输出，暂未接入 MC 的音量滑杆和 OpenAL 三维定位。
- 重启服务器或重新加载区块时，保存的进度会以暂停状态恢复，避免无人观影时自动拉流。首版暂不提供字幕、影片列表、时长进度条、全员准备倒计时与视频结束后的服务器自动停播。

首次启动服务器会生成 `config/cinemar2-server.json`，将域名替换为部署后的网关域名，重启服务器：

```json
{
  "allowedMediaHosts": ["cinema-media.YOUR-SUBDOMAIN.workers.dev"],
  "viewDistance": 64
}
```

客户端配置示例：

```json
{
  "ffmpegPath": "C:\\tools\\ffmpeg\\bin\\ffmpeg.exe",
  "volume": 0.7
}
```

管理员使用 `/give @s cinemar2:screen` 获取控制器；将其放在影院屏幕下沿中心，面向观众，再右键打开菜单。将下列链接填入菜单，点击“载入影片”，确认画面后点击“播放”：

```text
https://cinema-media.YOUR-SUBDOMAIN.workers.dev/media/film-01.mp4?token=YOUR_MEDIA_TOKEN
```

普通观众右键打开菜单不会改变其他人的播放状态。暂停、跳转和停止由服务器校验并广播。当前控制操作要求操作者距离控制器不超过 16 格。建议影院座位在 64 格范围内，且服务器 view-distance 能加载屏幕所在区块。

## R2 免费额度保护

截至 2026-10-09，R2 Standard 每月免费额度为 **10 GB-month** 存储、**100 万 Class A** 操作、**1,000 万 Class B** 操作，出站流量免费。免费额度不适用于 Infrequent Access，超额按量计费，R2 不能按“免费套餐”理解为超额自动停服。

本项目采用以下约束：

1. 视频桶保持私有，关闭 `r2.dev` 与公共桶自定义域名。只有网关使用只读访问；MC 客户端不持有 R2/S3 密钥。
2. 网关只接受带随机读取令牌的 GET/HEAD，每个请求最多一次 R2 get/head，无重试、轮询、列表、上传或同步状态写入。
3. **Cloudflare 账户保持 Workers Free**，每天 100,000 次 HTTP 请求，超额返回 1027。使用 workers.dev 的 Worker 地址；如果改用自定义路由必须选择 fail closed。按此上限推导，该网关在 31 天账期（含跨 UTC 日边界）内最多贡献约 320 万次读操作。这个推导依赖免费计划、单次一读和没有绕过网关；升级 Workers Paid 后上限会失效。
4. 上传工具把这个桶内的已完成对象加新文件限制为 **8,000,000,000 字节（十进制 8 GB）**，拒绝未完成的分片上传、重复对象名与非 Standard 对象。另用本地持久账本将上传工具任意连续 32 天内的每类操作限在 1,000 次，禁用 SDK 自动重试。
5. 网关默认 `ENABLED=false`，可随时关闭以停止后续媒体请求。令牌仅是媒体读取权限，不能控制 MC 播放。

**这些保护限定本项目产生的使用量，不能保证整个 Cloudflare 账户绝不计费。** R2 免费额度由账户内所有桶/访问入口共享；其他桶、之前已发生的用量、手工上传、其他 Worker/S3 客户端以及未完成分片均须计入。建议使用无其他 R2 用量的账户/专用桶，维持总存储不超过 8 GB，上传后撤销写令牌，并检查账单用量。不要删除上传账本或在多台机器同时上传；桶内 8 GB 检查无法阻止其他入口继续写入。

50–100 人完整观看一部 3 GB 视频，媒体下载量约 150–300 GB，R2 出站不收费；但观众本地网络、服务器其他网络服务与第三方产品仍按各自规则计算。实际读请求数量受 FFmpeg 分段、探测与跳转影响，不按下载 GB 计费。2–3 GB 的影片通常能在 8 GB 预算内放 2–3 部；三部各 3 GB 会被上传工具拒绝。

来源：[R2 定价](https://developers.cloudflare.com/r2/pricing/)、[Workers 限额](https://developers.cloudflare.com/workers/platform/limits/)、[R2 公共桶](https://developers.cloudflare.com/r2/buckets/public-buckets/)。

## 部署媒体网关

以下是通用部署流程。本机已于 2026-10-09 按用户授权部署 `cinema-media` 网关并绑定现有 `video` 桶；本机配置保存在被 Git 忽略的 `gateway/wrangler.local.toml`，令牌不包含在发布包中。具体启动步骤见 `E:\mc\影院启动说明.md`。其他环境部署前仍需核对账户、免费计划和当前用量。

1. 在 R2 中创建 **Standard** 私有桶 `cinema-media`，禁用公共访问；在控制台确认 Workers 账户计划为 Free。
2. 在 `gateway` 目录执行 `npm ci`，然后 `npx wrangler login`，核对登录账户；如果桶名不同修改 `wrangler.toml` 的 `bucket_name`。
3. 生成媒体读取令牌：`node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`。用 `npx wrangler secret put MEDIA_TOKEN` 保存它。保留令牌以便构造游戏内视频链接，别将它提交到仓库。
4. 先 `npx wrangler deploy --no-x-provision` 发布默认关闭的网关，检查私有桶、账号免费计划和上传预算；然后把 `wrangler.toml` 的 `ENABLED` 改为 `"true"` 并再次部署。已明确指定现有桶时禁用自动资源创建，可避免 Wrangler 对 R2 管理 API 的额外权限要求。
5. 设置服务器的 `allowedMediaHosts`，用 `curl -I 'HTTPS视频链接'` 检查 200，再用 `curl -H 'Range: bytes=0-1023' 'HTTPS视频链接'` 检查 206 和 Content-Range，最后两名玩家联测。

为了零额外服务成本，不使用 Cloudflare Stream、Durable Objects、KV、D1 或视频转码服务。网关直接流式返回 R2 对象，不将 2–3 GB 视频读进 Worker 内存或存入 CDN 缓存。播放控制数据只走 MC 的 Fabric 网络包。

读取令牌会跟随链接发给观众，因此应把它视为观影群组共享凭据；它并非逐玩家身份验证。链接泄露可导致免费请求额度耗尽而停播，此时轮换令牌并更新影片链接。不要把媒体链接放到公开聊天/日志。

## 上传影片

客户端解码支持 MP4，建议使用 H.264/AAC 且把 moov 元数据放到文件开头。如原视频已兼容，可在本地快速重封装：

```powershell
ffmpeg -i input.mp4 -c copy -movflags +faststart film-01.mp4
```

如果需要转码，按时长核算码率和最终文件大小后在本地运行 FFmpeg。上传工具不检查内容授权或实际编码，不会进行云端转码。

在 Cloudflare 创建仅限该桶的对象读写 API 令牌，在本地 PowerShell 设置环境变量（不要粘贴密钥到聊天）：

```powershell
python -m pip install -r tools/requirements.txt
$env:R2_ACCOUNT_ID = '你的账户ID'
$env:R2_BUCKET = 'cinema-media'
$env:AWS_ACCESS_KEY_ID = '本地设置的S3访问ID'
$env:AWS_SECRET_ACCESS_KEY = '本地设置的S3密钥'
python tools/upload_media.py 'D:\films\film-01.mp4' --key media/film-01.mp4
python tools/upload_media.py 'D:\films\film-01.mp4' --key media/film-01.mp4 --upload
```

第一条只预检，但列表操作也消耗额度；第二条才上传。64 MiB 顺序分片，3 GB 文件约需 47 个分片加创建/完成操作。失败尝试也保留预算；异常会尝试免费 AbortMultipartUpload。进程被强行关闭可能留下未完成上传，此时先在控制台中止，再检查并删除本地锁文件。保留 `.tools/upload-state/` 的 SQLite 账本，上传期间不要另开写入口。新片名用新 key，避免正在观影时替换文件。

## 本地构建与验证

在安装了 JDK 21 的电脑上：

```powershell
$env:JAVA_HOME = '你的JDK21目录'
.\gradlew.bat build --console=plain
node --test gateway/test/worker.test.mjs
python -m unittest discover -s tools -p 'test_*.py'
```

产物在 `build/libs/cinema-r2-0.1.0.jar`；同一 JAR 用于客户端与 Fabric 专用服务器。`-sources.jar` 是源码包，不放进 mods 目录。

组件验证包括服务器时间轴暂停/恢复/跳转、真实 FFmpeg 经本地 HTTPS 读取并定位帧、网关读取次数/Range/鉴权/失败关闭、上传存储上限/本地操作预算/写锁/失败分片中止。真实 FFmpeg 集成测试需要先生成 `.tools/test-pattern.mp4` 和 `.tools/test-tls.p12` 测试文件，再运行 `gradlew.bat test '-PintegrationTest=true'`，见 `tools/prepare_decode_test.ps1`。

建议多人验收：两台客户端加载同片，测试房主播放/暂停/跳转、观众禁止控制、播放中加入、退出范围与重连、服务器重启恢复暂停；再逐步扩到 10/50/100 人并观察客户端 CPU、音画延迟、MC tick 和 Cloudflare 用量。首版只有静态影片链接，后续可增加片库与准备倒计时。
