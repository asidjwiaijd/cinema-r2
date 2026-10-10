import { randomBytes, randomUUID } from "node:crypto";
import {
  mkdirSync,
  existsSync,
  readFileSync,
  writeFileSync,
  renameSync,
} from "node:fs";
import { join } from "node:path";
export const root =
  process.env.CINEMA_DATA ||
  new URL("../.data/", import.meta.url).pathname.replace(/^\/([A-Z]:)/, "$1");
mkdirSync(root, { recursive: true });
const file = join(root, "state.json");
export const state = existsSync(file)
  ? JSON.parse(readFileSync(file, "utf8"))
  : { resources: [], screens: [], commands: [], events: [] };
const settingsFile = join(root, "settings.json");
export const settings = existsSync(settingsFile)
  ? JSON.parse(readFileSync(settingsFile, "utf8"))
  : {
      bridgeToken: randomBytes(32).toString("hex"),
      ffmpeg: "ffmpeg",
      ffprobe: "ffprobe",
      publicBase: "https://video.mc.cxmlbt-tec.top",
      r2: {
        accountId: "2eb9419b63a6e3ce3d893ce44f4c2ded",
        bucket: "video",
        accessKeyId: "",
        secretAccessKey: "",
        storageLimitGB: 8,
      },
      live: {
        id: randomUUID(),
        streamKey: randomBytes(20).toString("hex"),
        port: 1935,
        host: "127.0.0.1",
        segmentSeconds: 4,
      },
    };
export function saveSettings() {
  writeFileSync(settingsFile, JSON.stringify(settings, null, 2));
}
export function save() {
  writeFileSync(file + ".tmp", JSON.stringify(state, null, 2));
  renameSync(file + ".tmp", file);
}
saveSettings();
export function event(message, level = "info") {
  state.events.unshift({ id: randomUUID(), time: Date.now(), message, level });
  state.events = state.events.slice(0, 80);
  save();
}
export function publicSettings() {
  return {
    ffmpeg: settings.ffmpeg,
    ffprobe: settings.ffprobe,
    publicBase: settings.publicBase,
    delivery: settings.delivery,
    r2: {
      accountId: settings.r2.accountId,
      bucket: settings.r2.bucket,
      storageLimitGB: settings.r2.storageLimitGB,
      tokenExpires: settings.r2.tokenExpires,
      configured: !!(settings.r2.accessKeyId && settings.r2.secretAccessKey),
    },
    live: { ...settings.live, streamKey: undefined },
  };
}
export function mediaURL(value) {
  let u;
  try {
    u = new URL(value);
  } catch {
    throw Error("请输入有效媒体 URL");
  }
  if (u.username || u.password || u.hash || value.length > 2048)
    throw Error("媒体链接格式无效");
  if (
    u.protocol !== "https:" &&
    !(u.protocol === "http:" && ["127.0.0.1", "localhost"].includes(u.hostname))
  )
    throw Error("使用 HTTPS 或本机 HTTP 媒体地址");
  if (!/\.(mp4|m3u8)$/i.test(u.pathname)) throw Error("支持 MP4 或 HLS m3u8");
  return u.href;
}
export function validatePolicy(p) {
  const behavior = ["hold", "loop", "next"].includes(p.onEnd)
    ? p.onEnd
    : "hold";
  return {
    onEnd: behavior,
    quality: Number(p.quality) === 1080 ? 1080 : 720,
    cacheSeconds: Math.max(30, Math.min(3600, Number(p.cacheSeconds) || 1200)),
    cacheStartSeconds: Math.max(1, Math.min(120, Number(p.cacheStartSeconds) || 20)),
    pauseWhenEmpty: !!p.pauseWhenEmpty,
    liveLatencySeconds: Math.max(
      4,
      Math.min(60, Number(p.liveLatencySeconds) || 12),
    ),
  };
}
export function queueCommand(screenId, action, data = {}) {
  if (state.commands.length >= 1000)
    throw Error("待执行指令已满，请先连接 MC 服务端");
  const command = { ...data, id: randomUUID(), screenId, action };
  state.commands.push(command);
  save();
  return command;
}
export function resourceSource(r) {
  return {
    url: r.url,
    live: r.kind === "live",
    durationMs: Math.round((r.duration || 0) * 1000),
    hasAudio: r.hasAudio !== false,
  };
}
