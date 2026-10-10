import { createRequire } from "node:module";
import { spawn } from "node:child_process";
import { mkdir, readdir, readFile, stat, unlink } from "node:fs/promises";
import { join } from "node:path";
import { settings, root, event, state, save } from "./model.mjs";
const require = createRequire(import.meta.url);
const NodeRtmpServer = require("node-media-server/src/node_rtmp_server.js");
const context = require("node-media-server/src/node_core_ctx.js");
require("node-media-server/src/node_core_logger.js").setLogType(0);
export const live = {
  listening: false,
  publishing: false,
  ready: false,
  error: "",
  startedAt: 0,
  delivery: "direct",
};
let rtmp, decoder, timer, starting;
const streamPath = () => "/live/" + settings.live.streamKey;
const directory = () => join(root, "live", settings.live.id);
export function liveURL() {
  return (
    (settings.live.publicBase || "http://127.0.0.1:3210").replace(/\/$/, "") +
    "/live/" +
    settings.live.id +
    "/index.m3u8"
  );
}
export function liveResource() {
  let resource = state.resources.find((r) => r.id === settings.live.id);
  if (!resource) {
    resource = {
      id: settings.live.id,
      name: "OBS 直播",
      kind: "live",
      format: "hls",
      duration: 0,
      status: "ready",
      created: Date.now(),
    };
    state.resources.unshift(resource);
  }
  Object.assign(resource, { storage: "direct", url: liveURL() });
  save();
  return resource;
}
async function inspectManifest() {
  if (!live.publishing) return;
  try {
    const manifest = await readFile(join(directory(), "index.m3u8"), "utf8");
    const names = manifest
      .split(/\r?\n/)
      .filter((n) => n && !n.startsWith("#"));
    if (!names.length) return;
    for (const name of names) {
      if (!/^segment_\d+\.ts$/.test(name)) throw Error("片段名称无效");
      await stat(join(directory(), name));
    }
    live.ready = true;
    // Only our disposable local live segments are cleaned; VOD resources stay untouched.
    for (const name of await readdir(directory())) {
      if (/^segment_\d+\.ts$/.test(name) && !names.includes(name)) {
        const file = join(directory(), name);
        if (Date.now() - (await stat(file)).mtimeMs > 120000)
          await unlink(file).catch(() => {});
      }
    }
  } catch (e) {
    if (e.code !== "ENOENT") live.error = "直播分片生成失败";
  }
}
async function transcode() {
  const previous = decoder;
  if (previous && previous.exitCode === null) previous.kill();
  live.publishing = true;
  live.ready = false;
  live.error = "";
  live.startedAt = Date.now();
  await mkdir(directory(), { recursive: true });
  await unlink(join(directory(), "index.m3u8")).catch(() => {});
  liveResource();
  const process = spawn(
    settings.ffmpeg,
    [
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      "rtmp://127.0.0.1:" + settings.live.port + streamPath(),
      "-map",
      "0:v:0",
      "-map",
      "0:a:0?",
      "-c:v",
      "libx264",
      "-threads",
      "2",
      "-preset",
      "veryfast",
      "-tune",
      "zerolatency",
      "-vf",
      settings.live.quality === 720 ? "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2" : "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
      "-pix_fmt",
      "yuv420p",
      "-r",
      "24",
      "-g",
      String(settings.live.segmentSeconds * 24),
      "-keyint_min",
      String(settings.live.segmentSeconds * 24),
      "-sc_threshold",
      "0",
      "-maxrate",
      settings.live.quality === 720 ? "4M" : "8M",
      "-bufsize",
      settings.live.quality === 720 ? "8M" : "16M",
      "-c:a",
      "aac",
      "-b:a",
      "128k",
      "-f",
      "hls",
      "-hls_time",
      String(settings.live.segmentSeconds),
      "-hls_list_size",
      "8",
      "-hls_delete_threshold",
      "16",
      "-hls_flags",
      "delete_segments+independent_segments+program_date_time+temp_file",
      "-hls_start_number_source",
      "epoch_us",
      "-hls_segment_filename",
      join(directory(), "segment_%020d.ts"),
      join(directory(), "index.m3u8"),
    ],
    { windowsHide: true, stdio: ["pipe", "ignore", "pipe"] },
  );
  decoder = process;
  process.stderr.on("data", () => {});
  process.stdin.on("error", () => {});
  process.on("error", () => {
    if (decoder === process) {
      live.error = "FFmpeg 直播转码无法启动";
      live.publishing = false;
    }
  });
  process.on("exit", (code) => {
    if (decoder === process) {
      live.publishing = false;
      live.ready = false;
      if (code && live.listening) live.error = "直播推流中断或转码退出";
      event("OBS 推流结束");
    }
  });
  clearInterval(timer);
  timer = setInterval(inspectManifest, 1000);
  event("OBS 已连接，直播在接收服务器生成 HLS 并直接分发");
}
context.nodeEvent.on("prePublish", (id, path) => {
  if (!rtmp || path !== streamPath() || live.publishing) context.sessions.get(id)?.reject();
});
context.nodeEvent.on("prePlay", (id, path) => {
  if (!rtmp || path !== streamPath()) context.sessions.get(id)?.reject();
});
context.nodeEvent.on("postPublish", (id, path) => {
  if (path === streamPath())
    transcode().catch(() => {
      live.error = "无法启动直播转码";
      live.publishing = false;
    });
});
context.nodeEvent.on("donePublish", (id, path) => {
  if (path === streamPath()) live.publishing = false;
});
export function startLive() {
  if (rtmp) return Promise.resolve();
  if (starting) return starting;
  starting = new Promise((resolve, reject) => {
    const server = new NodeRtmpServer({
      rtmp: {
        port: settings.live.port,
        chunk_size: 60000,
        gop_cache: true,
        ping: 30,
        ping_timeout: 60,
      },
    });
    rtmp = server;
    server.tcpServer.once("error", () => {
      rtmp = undefined;
      live.listening = false;
      live.error = "RTMP 端口不可用";
      reject(Error(live.error));
    });
    server.tcpServer.listen(settings.live.port, settings.live.host, () => {
      live.listening = true;
      event("直播接收端已启动");
      resolve();
    });
  }).finally(() => {
    starting = undefined;
  });
  return starting;
}
export function stopLive() {
  clearInterval(timer);
  if (decoder?.exitCode === null) {
    const process = decoder;
    process.stdin.write("q\n");
    setTimeout(() => {
      if (process.exitCode === null) process.kill();
    }, 5000).unref();
  }
  if (rtmp) {
    rtmp.stop();
    rtmp = undefined;
  }
  live.listening = false;
  live.publishing = false;
  live.ready = false;
  live.error = "";
  event("直播接收端已停止");
}
