# Cinema 0.4.0：Minecraft 26.1.2 与网页 OP 管理

客户端和服务端使用 Minecraft 26.1.2、Fabric Loader 0.19.5、Fabric API 0.155.3+26.1.2、Java 25。构建工具为 Loom 1.15.5 / Gradle 9.4.0；代码改用 Minecraft 官方名称及新版图形接口，移除旧的 OpenGL 纹理上传。

## 网页设置 OP

在「玩家管理」找到玩家，点击「设置 OP」，选择等级并确认，收到 MC 成功回执后生效。

| 等级 | 权限范围 |
| --- | --- |
| 0 | 撤销 OP |
| 1 | 绕过出生点保护 |
| 2 | 游戏管理，含 /gamemode、/tp、/give；默认推荐等级 |
| 3 | 增加玩家管理指令 |
| 4 | 增加服务器管理指令 |

授予 OP 要求玩家在线，操作绑定该玩家 UUID 和本次连接；断线后发出的旧授权请求会被拒绝。已有 OP 可在离线时撤销。名单采用 Minecraft 原生持久存储；重连和重启后保留。2 级以上 OP 可以建造、自由切换模式，普通观众仍受影院保护。独立的「影院管理员」角色继续只授权本次连接的建造权限。

## 已部署位置

- 网站、媒体后台与 OBS 转码继续运行在 `192.168.8.244` 的 `/mnt/cinema` 独立磁盘上；管理地址及登录方式沿用原有配置。
- 新 MC 服务端：`E:\mc\cinema-server-26.1.2`，启动 `start-cinema.bat`。
- 原启动入口 `E:\mc\cinema-server\start-cinema.bat` 已转向新服务端。
- PCL 新实例：`Cinema-R2-26.1.2-Fabric`。其他玩家安装新的 26.1.2 整合包。
- 游戏公网入口继续是 `216.23.76.33:25565`；FRP 转发目标仍为本机 `127.0.0.1:25565`。

`server.properties` 设置 `pause-when-empty-seconds=-1`，确保无人在线时网页控制桥也持续工作。新部署亦应保留这项设置。游戏服务端与 FRP 客户端均需保持运行。

## 地图备份与回退

升级前正常停止 1.21.1 服务并等待所有维度保存，然后将地图、角色配置和原生名单复制到新目录。原目录的 1.21.1 存档保留；另有 `.tools/backup-before-0.4.0/server/world-1.21.1.zip` 和原配置备份。网站代码备份位于远端 `/mnt/cinema/cinema/deploy/backup-before-0.4.0.tar`。

升级后的地图在新目录中写入。需要回退时，先正常停止 26.1.2，恢复备份的原启动脚本并使用原目录的 1.21.1 地图；不要将升级后的地图交给旧版打开。备份含管理桥配置，应留在本机。

## 播放与验证

libmpv、720p/1080p、大容量缓存、6 路 R2 预取及 EO/阿里云分片兜底继续保留。直播继续通过 CDN 直发。初始媒体尚未载入时收到的跳转会延后到 `FILE_LOADED` 后执行，避免慢网络或短片循环导致播放器启动失败。

验证记录：`dist/migration-26.1.2-validation.json`；实际游戏画面：`dist/minecraft-26.1.2-screen-validation.png`；空服桥接验证：`dist/minecraft-26.1.2-idle-bridge-validation.json`。新包包含 Java 25 与独立 libmpv DLL，无玩家账号、桥接令牌、R2 密钥或世界数据。
