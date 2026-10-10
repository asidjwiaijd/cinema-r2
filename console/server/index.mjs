import express from "express";
import multer from "multer";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, copyFile, statfs, stat, unlink } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import {
  root,
  state,
  settings,
  save,
  saveSettings,
  event,
  publicSettings,
  mediaURL,
  validatePolicy,
  queueCommand,
  resourceSource,
} from "./model.mjs";
import { jobs, job, processUpload, publishResource } from "./media.mjs";
import { live, startLive, stopLive, liveURL, liveResource } from "./live.mjs";
import { installAuth } from "./auth.mjs";
import { agentBase, agentCall, remote, watchAgent, previewURL, installPreview } from "./remote.mjs";
import { installDelivery, deliveryStatus, updateDelivery } from "./delivery.mjs";
import {installPlayers,ingestPlayers} from "./players.mjs";
import {issuePlayerClaims} from "./delivery.mjs";
import { syncAll, scheduleSync } from "./replicas.mjs";
const app = express();
const port = Number(process.env.PORT) || 3210;
const host = process.env.HOST || "127.0.0.1";
if (host !== "127.0.0.1" && !process.env.CINEMA_AUTH_FILE) throw Error("远程监听必须配置管理登录");
let lastHeartbeat = 0;
let serverName = "";
let activeInstance = "";
let lastBridgeSave=0;
const commandResults=new Map();
await mkdir(join(root, "incoming"), { recursive: true });
app.disable("x-powered-by");
app.use(express.json({ limit: "1mb" }));
app.use("/api", (req, res, next) => {
  res.set("Cache-Control", "no-store");
  const origin = req.get("origin");
  if (
    origin &&
    ![
      "http://127.0.0.1:" + port,
      "http://localhost:" + port,
      "http://127.0.0.1:3211",
      process.env.CINEMA_PUBLIC_ORIGIN,
      ...(process.env.CINEMA_ALLOWED_ORIGINS || "").split(",").map(value => value.trim()).filter(Boolean),
    ].includes(origin)
  )
    return res.status(403).json({ error: "请求来源不允许" });
  if (req.get("sec-fetch-site") === "cross-site")
    return res.status(403).json({ error: "跨站请求不允许" });
  next();
});
const authEnabled = installAuth(app);
watchAgent();
if (agentBase) liveResource();
if(!agentBase && process.env.CINEMA_LIVE_AUTOSTART==="1")startLive().catch(()=>{});
installPreview(app);
installDelivery(app);
installPlayers(app,()=>state.screens);
if (remote) scheduleSync();
app.post("/api/resources/sync",(req,res)=>res.status(202).json(syncAll()));
app.post("/api/bridge/poll", (req, res) => {
  const token = Buffer.from(
    req.get("authorization")?.replace(/^Bearer /, "") || "",
  );
  const expected = Buffer.from(settings.bridgeToken);
  if (token.length !== expected.length || !timingSafeEqual(token, expected))
    return res.status(401).json({ error: "Bridge 未授权" });
  const { screens = [], acks = [], serverId } = req.body;
  if (!Array.isArray(screens) || screens.length > 200 || !Array.isArray(acks))
    return res.status(400).json({ error: "心跳格式无效" });
  const instance = String(req.body.instanceId || serverId || "legacy");
  if (
    activeInstance &&
    instance !== activeInstance &&
    Date.now() - lastHeartbeat < 6000
  )
    return res.status(409).json({ error: "控制台已连接另一个 MC 服务端" });
  ingestPlayers(req.body);
  activeInstance = instance;
  lastHeartbeat = Date.now();
  serverName = serverId || "Minecraft";
  for (const ack of acks) {
    const cmd = state.commands.find((c) => c.id === ack.id);
    if (cmd) {
      if (cmd.action === "remove" && !ack.error)
        state.screens = state.screens.filter((s) => s.id !== cmd.screenId);
      if (cmd.action === "create" && ack.error)
        state.screens = state.screens.filter(
          (s) => s.id !== cmd.screenId || s.online,
        );
      commandResults.set(cmd.id,{id:cmd.id,action:cmd.action,screenId:cmd.screenId,error:ack.error||"",completedAt:Date.now()});
      if(commandResults.size>200)commandResults.delete(commandResults.keys().next().value);
      event(
        ack.error
          ? "MC 执行失败：" + String(ack.error).slice(0, 160)
          : "MC 已执行 " + cmd.action,
        ack.error ? "error" : "info",
      );
      state.commands = state.commands.filter((c) => c.id !== ack.id);
    }
  }
  const seen = new Set();
  for (const incoming of screens) {
    if (typeof incoming.id !== "string") continue;
    seen.add(incoming.id);
    let old = state.screens.find((s) => s.id === incoming.id);
    if (!old) {
      old = {
        id: incoming.id,
        name: "屏幕 " + (state.screens.length + 1),
        policy: validatePolicy({}),
      };
      state.screens.push(old);
    }
    Object.assign(old, incoming, { online: true, lastSeen: Date.now() });
  }
  for (const s of state.screens) if (!seen.has(s.id)) s.online = false;
  if(Date.now()-lastBridgeSave>1000){save();lastBridgeSave=Date.now();}
  res.json({
    time: Date.now(),
    playerClaims:issuePlayerClaims(req.body.players||[]),
    commands: state.commands.slice(0, 40),
    mediaHosts: [
      new URL(settings.publicBase).hostname,
      new URL(settings.delivery.cdnBase).hostname,
      new URL(settings.delivery.backupBase).hostname,
      ...new Set(
        state.resources
          .filter((r) => r.url)
          .map((r) => new URL(r.url).hostname)
          .filter((h) => h !== "127.0.0.1"),
      ),
    ],
  });
});
app.get("/api/state", (req, res) =>
  res.json({
    authEnabled,
    delivery:deliveryStatus(),
    deployment: remote ? {origin:process.env.CINEMA_PUBLIC_ORIGIN,liveAgent:!!agentBase} : null,
    resources: state.resources.filter((r) => !r.archived).map(r => ({...r,previewUrl:previewURL(r)})),
    screens: state.screens.map((s) => ({
      ...s,
      online: !!s.online && Date.now() - lastHeartbeat < 6000,
    })),
    jobs,
    pendingCommands:state.commands.map(({id,action,screenId})=>({id,action,screenId})),
    commandResults:[...commandResults.values()],
    events: state.events,
    settings: publicSettings(),
    bridge: {
      online: Date.now() - lastHeartbeat < 6000,
      lastSeen: lastHeartbeat,
      serverName,
    },
    live: {
      ...live,
      url: liveURL(),
      previewUrl: previewURL({id:settings.live.id, storage:"direct", url:liveURL()}),
      rtmpUrl: settings.live.ingestUrl || ("rtmp://127.0.0.1:" + settings.live.port + "/live"),
      ingestHost:agentBase?"本机 Windows":remote?"192.168.8.244":"本机",
      streamKeySet: true,
    },
  }),
);
app.post("/api/settings", async (req, res) => {
  const b = req.body;
  if (b.delivery) updateDelivery(b.delivery);
  if (agentBase && (b.liveBase !== undefined || b.liveQuality !== undefined)) {
    await agentCall("/settings", {liveBase:b.liveBase,liveQuality:b.liveQuality});
  }
  if (b.publicBase) {
    const u = new URL(b.publicBase);
    if (u.protocol !== "https:" || u.username || u.password)
      throw Error("R2 公共地址必须为 HTTPS");
    settings.publicBase = u.origin;
  }
  if (b.liveBase) {
    const u = new URL(b.liveBase);
    if (
      u.protocol !== "https:" &&
      !(
        u.protocol === "http:" &&
        u.hostname === "127.0.0.1" &&
        u.port === "3210"
      )
    )
      throw Error("直播分发地址使用 HTTPS，或本机 http://127.0.0.1:3210");
    if (u.username || u.password || u.search || u.hash)
      throw Error("直播分发地址格式无效");
    settings.live.publicBase = u.origin;
    liveResource();
  }
  if (b.ffmpeg) settings.ffmpeg = String(b.ffmpeg);
  if (b.ffprobe) settings.ffprobe = String(b.ffprobe);
  if (b.liveQuality !== undefined) settings.live.quality = Number(b.liveQuality) === 1080 ? 1080 : 720;
  if (b.r2) {
    for (const key of ["accountId", "bucket", "accessKeyId", "secretAccessKey"])
      if (b.r2[key]) settings.r2[key] = String(b.r2[key]);
    if (b.r2.storageLimitGB)
      settings.r2.storageLimitGB = Math.max(
        1,
        Math.min(1000, Number(b.r2.storageLimitGB)),
      );
  }
  saveSettings();
  event("系统配置已保存");
  res.json({ ok: true });
});
const upload = multer({
  dest: join(root, "incoming"),
  limits: { fileSize: 4 * 1024 * 1024 * 1024, files: 1 },
  fileFilter: (req, file, cb) =>
    cb(null, /\.(mp4|mkv|mov|webm|mp3|wav|flac|ogg|m4a|aac|png|jpe?g|webp)$/i.test(file.originalname)),
});
async function reserveDisk(bytes) {
  if (!remote) return;
  if (receiving || jobs.some(j => j.status === "running")) throw Error("当前有媒体处理任务，请完成后再上传或生成副本");
  const fs = await statfs(root);
  if (receiving || jobs.some(j => j.status === "running")) throw Error("当前有媒体处理任务，请完成后再上传或生成副本");
  if (fs.bavail * fs.bsize < bytes * 3 + 1024**3) throw Error("影院磁盘剩余空间不足，需保留原片、转码文件及 1GB 余量");
}
let receiving = false;
app.post("/api/resources/upload", async (req, res, next) => {
  if (remote && receiving) return res.status(409).json({error:"另一个文件正在上传，请稍后再试"});
  try {
    await reserveDisk(Math.min(4*1024**3, Number(req.get("content-length")) || 4*1024**3));
    if (remote && receiving) return res.status(409).json({error:"另一个文件正在上传，请稍后再试"});
    if (remote) {receiving=true;res.on("close",()=>{receiving=false;});}
    next();
  } catch(error) {next(error);}
}, upload.single("file"), (req, res) => {
  if (!req.file) throw Error("请选择支持的视频、音频或图片文件");
  const mediaType=/\.(mp3|wav|flac|ogg|m4a|aac)$/i.test(req.file.originalname)?'audio':/\.(png|jpe?g|webp)$/i.test(req.file.originalname)?'image':'video';
  const imageSeconds=Number(req.body.imageSeconds??15);
  if(mediaType==='image'&&(!Number.isInteger(imageSeconds)||imageSeconds<1||imageSeconds>3600)){unlink(req.file.path).catch(()=>{});throw Error('图片展示时长须为 1–3600 秒的整数');}
  const r = {
    id: randomUUID(),
    name: String(req.body.name || req.file.originalname).slice(0, 120),
    kind: "vod",
    mediaType,imageSeconds:mediaType==='image'?imageSeconds:undefined,
    format: req.body.format === "hls" ? "hls" : "mp4",
    quality: Number(req.body.quality) === 1080 ? 1080 : 720,
    status: "processing",
    storage: "local",
    created: Date.now(),
    duration: 0,
    url: "",
  };
  state.resources.unshift(r);
  save();
  const j = job("处理 " + r.name, async (j) => {
    try {
      await processUpload(r, req.file.path, j);
    } catch (e) {
      if(r.replicas?.local!=="ready")r.status = "error";
      r.error = e.message;
      save();
      throw e;
    }
  });
  res.status(202).json({ resource: r, job: j });
});
app.post("/api/resources/import", (req, res) => {
  const url = mediaURL(req.body.url);
  const kind = req.body.kind === "live" ? "live" : "vod";
  const r = {
    id: randomUUID(),
    name: String(req.body.name || "媒体资源").slice(0, 120),
    url,
    kind,
    format: url.includes(".m3u8") ? "hls" : "mp4",
    storage: "external",
    status: "ready",
    duration: Math.max(0, Number(req.body.duration) || 0),
    created: Date.now(),
  };
  state.resources.unshift(r);
  save();
  event("已导入 " + r.name);
  res.json(r);
});
app.post("/api/resources/:id/publish", (req, res) => {
  const r = state.resources.find((r) => r.id === req.params.id);
  if (!r || r.storage === "external" || r.kind === "live")
    throw Error("该资源不能发布");
  res
    .status(202)
    .json(job("发布到 R2：" + r.name, (j) => publishResource(r, j)));
});
app.post("/api/resources/:id/variant", async (req, res) => {
  const original = state.resources.find((r) => r.id === req.params.id);
  if (!original || original.kind !== "vod" || original.storage === "external" || original.status !== "ready")
    throw Error("仅支持有本地原文件的已就绪影片");
  const source = join(root, "resources", original.id, "original.mp4");
  if (!existsSync(source)) throw Error("本地原文件不存在，请重新上传原片");
  await reserveDisk((await stat(source)).size);
  const quality = Number(req.body.quality) === 1080 ? 1080 : 720;
  const resource = { id: randomUUID(), name: original.name.slice(0, 105) + " · " + quality + "p", kind: "vod", format: "hls", quality,
    status: "processing", storage: "local", created: Date.now(), duration: 0, url: "" };
  state.resources.unshift(resource);save();
  const task = job("生成 " + quality + "p：" + original.name, async (j) => {
    try {
      const file = join(root, "incoming", resource.id);
      await copyFile(source, file);
      await processUpload(resource, file, j);
    } catch (e) { if(resource.replicas?.local!=="ready")resource.status = "error";resource.error = e.message;save();throw e; }
  });
  res.status(202).json({ resource, job: task });
});
const updateResource = (req, res) => {
  const r = state.resources.find((r) => r.id === req.params.id);
  if (!r) throw Error("资源不存在");
  if (req.body.name) r.name = String(req.body.name).slice(0, 120);
  if ("archived" in req.body) r.archived = !!req.body.archived;
  save();
  res.json({ ok: true });
};
app.patch("/api/resources/:id",updateResource);
app.post("/api/resources/:id/update",updateResource);
app.post("/api/screens", (req, res) => {
  if (Date.now() - lastHeartbeat > 6000)
    throw Error("请先启动并连接 MC 服务端");
  const b = req.body;
  const coords = ["x", "y", "z"].map((k) => Number(b[k]));
  if (
    coords.some((n) => !Number.isInteger(n)) ||
    Math.abs(coords[0]) > 29999984 ||
    Math.abs(coords[2]) > 29999984 ||
    coords[1] < -64 ||
    coords[1] > 319
  )
    throw Error("屏幕坐标无效");
  const dimension = b.dimension || "minecraft:overworld";
  if (
    ![
      "minecraft:overworld",
      "minecraft:the_nether",
      "minecraft:the_end",
    ].includes(dimension)
  )
    throw Error("维度无效");
  const id = dimension + "@" + coords.join(",");
  const pending = {
    id,
    name: String(b.name || "新屏幕").slice(0, 80),
    dimension,
    x: coords[0],
    y: coords[1],
    z: coords[2],
    width: Math.max(4, Math.min(32, Number(b.width) || 12)),
    facing: ["north", "south", "east", "west"].includes(b.facing)
      ? b.facing
      : "south",
    policy: validatePolicy({}),
    online: false,
  };
  if (state.screens.some((s) => s.id === id)) throw Error("该位置已经登记屏幕");
  state.screens.push(pending);
  queueCommand(id, "create", pending);
  res.status(202).json(pending);
});
const updateScreen = (req, res) => {
  const s = state.screens.find((s) => s.id === req.params.id);
  if (!s) throw Error("屏幕不存在");
  if (req.body.name) s.name = String(req.body.name).slice(0, 80);
  const data = {
    width: Math.max(4, Math.min(32, Number(req.body.width) || s.width)),
    facing: ["north", "south", "east", "west"].includes(req.body.facing)
      ? req.body.facing
      : s.facing,
    policy: validatePolicy(req.body.policy || s.policy),
  };
  Object.assign(s, data);
  queueCommand(s.id, "configure", data);
  res.json({ ok: true });
};
app.patch("/api/screens/:id",updateScreen);
app.post("/api/screens/:id/update",updateScreen);
const removeScreen = (req, res) => {
  const screen = state.screens.find((s) => s.id === req.params.id);
  if (!screen) throw Error("屏幕不存在");
  if (screen.online && Date.now() - lastHeartbeat < 6000)
    queueCommand(screen.id, "remove");
  else {
    state.screens = state.screens.filter((s) => s.id !== screen.id);
    state.commands = state.commands.filter((c) => c.screenId !== screen.id);
    save();
  }
  res.json({ ok: true });
};
app.delete("/api/screens/:id",removeScreen);
app.post("/api/screens/:id/remove",removeScreen);
app.post("/api/control", (req, res) => {
  if (Date.now() - lastHeartbeat > 6000)
    throw Error("MC 服务端未连接，指令没有发送");
  const { screenIds, action, resourceId, position, queue = [] } = req.body;
  if (!Array.isArray(screenIds) || !screenIds.length)
    throw Error("请至少选择一块屏幕");
  if (
    !["load", "take", "play", "pause", "seek", "stop", "playlist", "remove"].includes(
      action,
    )
  )
    throw Error("控制动作无效");
  let data = {};
  if (action === "load" || action === "take") {
    const r = state.resources.find(
      (r) => r.id === resourceId && r.status === "ready" && !r.archived,
    );
    if (!r) throw Error("请选择处理完成的资源");
    if(r.storage==="direct" && r.kind==="live" && !live.ready)throw Error("直播信号尚未就绪，请先在 OBS 开始推流");
    data.source = resourceSource(r);
  }
  if (action === "seek") {
    if (!Number.isFinite(Number(position)) || position < 0 || position > 86400)
      throw Error("跳转时间无效");
    data.positionMs = Math.round(Number(position) * 1000);
  }
  if (action === "playlist") {
    if (!Array.isArray(queue) || queue.length > 100)
      throw Error("队列最多 100 项");
    data.queue = queue.map((id) => {
      const r = state.resources.find(
        (r) => r.id === id && r.status === "ready" && !r.archived,
      );
      if (!r) throw Error("队列含不可播放资源");
      return resourceSource(r);
    });
  }
  const targets = screenIds.map((id) =>
    state.screens.find((s) => s.id === id && s.online),
  );
  if (targets.some((s) => !s)) throw Error("目标屏幕未加载或离线");
  const commandIds=[];
  for (const s of targets) {
    if (action === "load" || action === "take") s.resourceId = resourceId;
    if (action === "playlist") s.queue = queue;
    commandIds.push(queueCommand(s.id, action, data).id);
  }
  event("已发送 " + action + " 至 " + targets.length + " 块屏幕");
  res.status(202).json({ ok: true, commandIds });
});
app.post("/api/live/start", async (req, res) => {
  if (agentBase) return res.json(await agentCall("/live/start", {}));
  await startLive();
  const resource = liveResource();
  res.json({ ok: true, resource });
});
app.post("/api/live/stop", async (req, res) => {
  if (agentBase) return res.json(await agentCall("/live/stop", {}));
  stopLive();
  res.json({ ok: true });
});
app.get("/api/live/key", async (req, res) => res.json(agentBase ? await agentCall("/live/key") : { key: settings.live.streamKey }));
app.use(
  "/media/local",
  express.static(join(root, "resources"), {
    dotfiles: "deny",
    setHeaders: (r) => r.setHeader("Access-Control-Allow-Origin", "*"),
  }),
);
app.use(
  "/live",
  (req,res,next)=>deliveryStatus().allowed?next():res.status(503).set("Cache-Control","no-store").end(),
  express.static(join(root, "live"), {
    dotfiles: "deny",
    setHeaders: (r, p) => {
      r.setHeader(
        "Cache-Control",
        p.endsWith(".m3u8") ? "public, max-age=1, must-revalidate" : "public, max-age=86400, immutable",
      );
      r.setHeader("Access-Control-Allow-Origin", "*");
    },
  }),
);
app.use(
  "/posters",
  express.static(join(root, "resources"), { dotfiles: "deny" }),
);
app.use(
  express.static(
    new URL("../dist/", import.meta.url).pathname.replace(/^\/([A-Z]:)/, "$1"),
  ),
);
app.get("/", (req, res) =>
  res.sendFile(
    new URL("../dist/index.html", import.meta.url).pathname.replace(
      /^\/([A-Z]:)/,
      "$1",
    ),
  ),
);
app.use((e, req, res, next) =>
  res.status(400).json({ error: e.message || "请求失败" }),
);
export const listener = app.listen(port, host, () =>
  console.log("Cinema Console: http://" + host + ":" + port),
);
// Large uploads may take longer than Node's default five-minute request timeout.
listener.requestTimeout = 0;
export { app };
process.on("SIGINT", () => {
  if (!agentBase) stopLive();
  listener.close(() => process.exit());
});
process.on("SIGTERM", () => {
  if (!agentBase) stopLive();
  listener.close(() => process.exit());
});
