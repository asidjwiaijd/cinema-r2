import { spawn } from "node:child_process";
import { createReadStream } from "node:fs";
import {
  mkdir,
  readdir,
  readFile,
  stat,
  unlink,
  writeFile,
} from "node:fs/promises";
import { join } from "node:path";
import { randomUUID, createHash } from "node:crypto";
import {
  S3Client,
  PutObjectCommand,
  ListObjectsV2Command,
  CreateMultipartUploadCommand,
  UploadPartCommand,
  CompleteMultipartUploadCommand,
  AbortMultipartUploadCommand,
} from "@aws-sdk/client-s3";
import { root, state, settings, save, event } from "./model.mjs";
export const jobs = [];
export function run(bin, args) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, args, { windowsHide: true });
    let out = "",
      err = "";
    p.stdout.on("data", (b) => (out += b));
    p.stderr.on("data", (b) => (err = (err + b).slice(-4000)));
    p.on("error", () =>
      reject(Error("无法启动媒体工具，请检查 FFmpeg/FFprobe 路径")),
    );
    p.on("exit", (code) =>
      code === 0
        ? resolve(out)
        : reject(
            Error(
              "媒体处理失败：" + err.replace(/rtmp:\/\/\S+/g, "[推流地址]"),
            ),
          ),
    );
  });
}
export function s3() {
  if (!settings.r2.accessKeyId || !settings.r2.secretAccessKey)
    throw Error("尚未配置 R2 S3 对象读写凭据");
  return new S3Client({
    region: "auto",
    endpoint: "https://" + settings.r2.accountId + ".r2.cloudflarestorage.com",
    credentials: {
      accessKeyId: settings.r2.accessKeyId,
      secretAccessKey: settings.r2.secretAccessKey,
    },
    maxAttempts: 2,
  });
}
const types = {
  ".mp4": "video/mp4",
  ".ts": "video/mp2t",
  ".m3u8": "application/vnd.apple.mpegurl",
  ".jpg": "image/jpeg",
};
export async function putFile(
  client,
  file,
  key,
  cache = "public, max-age=31536000, immutable",
) {
  const size = (await stat(file)).size;
  const ContentType =
    types[Object.keys(types).find((x) => file.endsWith(x))] ||
    "application/octet-stream";
  const Bucket = settings.r2.bucket;
  const hash=createHash("sha256");for await(const chunk of createReadStream(file))hash.update(chunk);
  const Metadata={sha256:hash.digest("hex")};
  if (size < 64 * 1024 * 1024) {
    await client.send(
      new PutObjectCommand({
        Bucket,
        Key: key,
        Body: createReadStream(file),
        ContentLength: size,
        ContentType,
        CacheControl: cache,
        StorageClass: "STANDARD",
        Metadata,
      }),
    );
    return;
  }
  const upload = await client.send(
    new CreateMultipartUploadCommand({
      Bucket,
      Key: key,
      ContentType,
      CacheControl: cache,
      StorageClass: "STANDARD",
      Metadata,
    }),
  );
  const parts = [];
  try {
    for (
      let offset = 0, part = 1;
      offset < size;
      offset += 64 * 1024 * 1024, part++
    ) {
      const length = Math.min(64 * 1024 * 1024, size - offset);
      const result = await client.send(
        new UploadPartCommand({
          Bucket,
          Key: key,
          UploadId: upload.UploadId,
          PartNumber: part,
          Body: createReadStream(file, {
            start: offset,
            end: offset + length - 1,
          }),
          ContentLength: length,
        }),
      );
      parts.push({ PartNumber: part, ETag: result.ETag });
    }
    await client.send(
      new CompleteMultipartUploadCommand({
        Bucket,
        Key: key,
        UploadId: upload.UploadId,
        MultipartUpload: { Parts: parts },
      }),
    );
  } catch (e) {
    await client
      .send(
        new AbortMultipartUploadCommand({
          Bucket,
          Key: key,
          UploadId: upload.UploadId,
        }),
      )
      .catch(() => {});
    throw e;
  }
}
export async function bucketBytes(client) {
  let total = 0,
    token;
  do {
    const page = await client.send(
      new ListObjectsV2Command({
        Bucket: settings.r2.bucket,
        ContinuationToken: token,
      }),
    );
    total += (page.Contents || []).reduce((n, o) => n + (o.Size || 0), 0);
    token = page.IsTruncated ? page.NextContinuationToken : undefined;
  } while (token);
  return total;
}
export function job(name, action) {
  const j = {
    id: randomUUID(),
    name,
    status: "running",
    progress: 0,
    created: Date.now(),
  };
  jobs.unshift(j);
  if (jobs.length > 30) jobs.length = 30;
  Promise.resolve()
    .then(() => action(j))
    .then(() => {
      j.status = "done";
      j.progress = 100;
      event(name + "完成");
    })
    .catch((e) => {
      j.status = "error";
      j.error = e.message.replace(
        settings.r2.secretAccessKey || "__none__",
        "[隐藏]",
      );
      event(name + "失败", "error");
    });
  return j;
}
let publishTail = Promise.resolve();
export async function publishResource(resource, j = {}) {
  const task = publishTail.then(async () => {
    const client = s3();
    const dir = join(root, "resources", resource.id);
    const names =
      resource.format === "hls"
        ? (await readdir(join(dir, "hls")))
            .filter((x) => x.endsWith(".ts") || x.endsWith(".m3u8"))
            .sort(
              (a, b) =>
                Number(a.endsWith(".m3u8")) - Number(b.endsWith(".m3u8")),
            )
        : ["original.mp4"];
    let bytes = 0;
    for (const n of names)
      bytes += (
        await stat(join(dir, resource.format === "hls" ? "hls" : "", n))
      ).size;
    if ((await bucketBytes(client)) + bytes > settings.r2.storageLimitGB * 1e9)
      throw Error("上传后将超过 R2 存储预算，请清理或调整预算");
    for (let i = 0; i < names.length; i++) {
      await putFile(
        client,
        join(dir, resource.format === "hls" ? "hls" : "", names[i]),
        "media/" + resource.id + "/" + names[i],
      );
      j.progress = Math.round(((i + 1) / names.length) * 90);
    }
    resource.url =
      settings.publicBase.replace(/\/$/, "") +
      "/media/" +
      resource.id +
      "/" +
      (resource.format === "hls" ? "index.m3u8" : "original.mp4");
    resource.storage = "r2";
    resource.publishedBytes = bytes;
    resource.replicas={local:"ready",r2:"ready",verifiedAt:Date.now(),files:names.length,bytes};
    save();
  });
  publishTail = task.catch(() => {});
  return task;
}
export async function processUpload(resource, file, j) {
  const dir = join(root, "resources", resource.id);
  await mkdir(dir, { recursive: true });
  const original = join(dir, "original.mp4");
  const { rename } = await import("node:fs/promises");
  await rename(file, original);
  const probe = JSON.parse(
    await run(settings.ffprobe, [
      "-v",
      "error",
      "-show_format",
      "-show_streams",
      "-of",
      "json",
      original,
    ]),
  );
  if(resource.mediaType==='audio'||resource.mediaType==='image'){
    const audio=probe.streams?.find(s=>s.codec_type==='audio');
    const image=probe.streams?.find(s=>s.codec_type==='video');
    if(resource.mediaType==='audio'&&(!audio||!Number.isFinite(Number(probe.format?.duration))||Number(probe.format.duration)<=0))throw Error('音频轨道或时长无效');
    if(resource.mediaType==='image'&&(!image||image.width>8192||image.height>8192||image.width*image.height>40000000))throw Error('图片无效或超过 8192 像素/4000 万像素限制');
    const source=join(dir,resource.mediaType==='audio'?'source-audio':'source-image');await rename(original,source);
    const height=resource.quality===1080?1080:720,width=height*16/9;
    const duration=resource.mediaType==='image'?resource.imageSeconds:Number(probe.format.duration);
    if(duration>86400)throw Error('媒体时长不能超过 24 小时');
    const inputs=resource.mediaType==='audio'
      ?['-f','lavfi','-i',`color=c=0x162b26:s=${width}x${height}:r=24`,'-i',source,'-map','0:v:0','-map','1:a:0','-vf',`drawbox=x=iw*0.15:y=ih*0.3:w=iw*0.7:h=ih*0.4:color=0x315b4c:t=fill,drawbox=x=iw*0.46:y=ih*0.36:w=iw*0.014:h=ih*0.24:color=white:t=fill,drawbox=x=iw*0.57:y=ih*0.36:w=iw*0.014:h=ih*0.24:color=white:t=fill,drawbox=x=iw*0.46:y=ih*0.36:w=iw*0.124:h=ih*0.035:color=white:t=fill,drawbox=x=iw*0.41:y=ih*0.59:w=iw*0.064:h=ih*0.045:color=white:t=fill,drawbox=x=iw*0.52:y=ih*0.59:w=iw*0.064:h=ih*0.045:color=white:t=fill`]
      :['-loop','1','-framerate','24','-i',source,'-vf',`scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2:color=0x101818,setsar=1`];
    await run(settings.ffmpeg,['-y','-hide_banner','-loglevel','error',...inputs,'-t',String(duration),'-c:v','libx264','-preset','veryfast','-crf','23','-pix_fmt','yuv420p','-c:a','aac','-b:a','128k','-movflags','+faststart',original]);
    Object.assign(probe,JSON.parse(await run(settings.ffprobe,['-v','error','-show_format','-show_streams','-of','json',original])));
  }
  const v = probe.streams?.find((x) => x.codec_type === "video");
  if (!v) throw Error("上传的文件不包含视频轨道");
  resource.hasAudio = probe.streams.some((x) => x.codec_type === "audio");
  resource.duration = Number(probe.format.duration) || 0;
  resource.size = (await stat(original)).size;
  resource.width = v.width;
  resource.height = v.height;
  await run(settings.ffmpeg, [
    "-y",
    "-hide_banner",
    "-loglevel",
    "error",
    "-ss",
    "0",
    "-i",
    original,
    "-frames:v",
    "1",
    "-vf",
    "scale=480:-1",
    join(dir, "poster.jpg"),
  ]);
  j.progress = 15;
  if (resource.format === "mp4") {
    const compatible =
      v.codec_name === "h264" &&
      (!resource.hasAudio ||
        probe.streams.find((x) => x.codec_type === "audio").codec_name ===
          "aac");
    const normalized = join(dir, "normalized.mp4");
    await run(settings.ffmpeg, [
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      original,
      "-map",
      "0:v:0",
      "-map",
      "0:a:0?",
      ...(compatible
        ? ["-c", "copy"]
        : [
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "23",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
          ]),
      "-movflags",
      "+faststart",
      normalized,
    ]);
    await unlink(original);
    await rename(normalized, original);
    resource.size = (await stat(original)).size;
  }
  if (resource.format === "hls") {
    await mkdir(join(dir, "hls"), { recursive: true });
    await run(settings.ffmpeg, [
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      original,
      "-map",
      "0:v:0",
      "-map",
      "0:a:0?",
      "-c:v",
      "libx264",
      "-preset",
      "veryfast",
      "-crf",
      "23",
      "-vf",
      resource.quality === 1080
        ? "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
        : "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
      "-pix_fmt",
      "yuv420p",
      "-r",
      "24",
      "-g",
      "288",
      "-keyint_min",
      "288",
      "-sc_threshold",
      "0",
      "-c:a",
      "aac",
      "-b:a",
      "128k",
      "-f",
      "hls",
      "-hls_time",
      "12",
      "-hls_playlist_type",
      "vod",
      "-hls_flags",
      "independent_segments+temp_file",
      "-hls_segment_filename",
      join(dir, "hls", "segment_%05d.ts"),
      join(dir, "hls", "index.m3u8"),
    ]);
  }
  resource.url =
    "http://127.0.0.1:3210/media/local/" +
    resource.id +
    "/" +
    (resource.format === "hls" ? "hls/index.m3u8" : "original.mp4");
  resource.storage = "local";
  resource.status = "ready";
  resource.replicas = {local:"ready",r2:"pending"};
  if(settings.delivery?.cdnBase) resource.url=settings.publicBase.replace(/\/$/,"")+"/media/"+resource.id+"/"+(resource.format==="hls"?"index.m3u8":"original.mp4");
  save();
  j.progress = 65;
  if (settings.r2.accessKeyId && settings.r2.secretAccessKey)
    try { await publishResource(resource, j); }
    catch(error){resource.replicas={local:"ready",r2:"error",syncError:error.message};save();throw error;}
}
