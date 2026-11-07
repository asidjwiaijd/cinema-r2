# 客户端调试彩条屏（Iris 渲染路径验证）

## 用途

在没有 MC 服务端、管理后台、libmpv 原生库和真实视频的情况下，单独验证屏幕的**渲染路径**，尤其是 Iris 光影下的 pass 选择、遮挡和亮度表现。

彩条纹理由客户端代码生成（256×144、全不透明），不经过 `MediaPlayer`，所以不会因为“没解码帧/没收到 State/没连后台”而黑屏——黑屏就只剩渲染原因。

## 启用

JVM 参数（PCL：版本设置 → 高级 → JVM 参数；或 `runClient` 的 VM options）：

```
-Dcinemar2.debugPattern=true
```

未设置该参数时，调试代码完全不注册按键、不挂渲染回调，对正常游戏没有影响。

## 操作

| 按键 | 作用 |
| --- | --- |
| `F7` | 在玩家面朝方向 8 格处放置一块 8 格宽（16:9）的彩条屏；再按一次会重新放置 |
| `F9` | 截图，保存到 `.minecraft/screenshots/` |

## 图案说明

- 上半部分：白、黄、青、绿、品红、红、蓝 七条标准彩条；
- 下半部分：8 级灰度阶梯（用于观察曝光/色调是否被光影包改动）；
- 四周 2px 白边（用于观察边缘是否被裁剪、alpha test 是否误伤）。

## 日志

客户端日志中关注（logger 名 `cinemar2`）：

| 日志 | 含义 |
| --- | --- |
| `Enabled optional Iris screen compatibility (API revision N)` | 找到了 Iris API，并记录了版本 |
| `Iris not installed; using the unlit screen pipeline` | 没有 Iris（或类不在），走自定义管线 |
| `Screen render mode: iris-entity-cutout` | 当前用原版实体 cutout 路径（光影开启） |
| `Screen render mode: unlit-custom` | 当前用自建无光照管线（无光影） |
| `Created debug pattern texture cinemar2:debug/pattern` | 彩条纹理已生成 |

## 建议的验证项

1. **无光影**：彩条可见、灰度阶梯连续、白边完整、前景方块能遮挡；
2. **开光影**（Complementary / Photon / BSL）：彩条仍可见，黑色区域不透天空，前景方块仍遮挡；
3. **播放中开关光影**：彩条不消失，日志在两种 `Screen render mode` 之间切换；
4. **距离**：把屏幕放在 32 / 96 / 180 / 240 格外看，记录雾/亮度变化（这是当前“已知会受光影包影响”的部分）；
5. **室内/夜晚**：放进有顶房间或调到夜晚，观察是否明显变暗（阴影图遮蔽）；
6. **F3+T 资源重载**：彩条应保持可见（动态纹理不受原版资源重载影响）。

## 说明与限制

- 这是**调试工具**，正式环境不要开启（会额外渲染一块屏幕）。
- 彩条屏验证的是“渲染路径 + 光影接管”，**不验证**：媒体解码、时间轴同步、远端屏幕的 Fabric 事件数据（虽然调试屏也走 `COLLECT_SUBMITS`，但它不依赖服务端下发的几何列表）、音频。
- 若要验证远端屏幕路径，仍需真实服务端 + 至少一个下发的屏幕几何。
