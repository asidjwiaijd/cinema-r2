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
  HeadObjectCommand,
  ListObjectsV2Command,
  CreateMultipartUploadCommand,
  UploadPartCommand,
  CompleteMultipartUploadCommand,
  AbortMultipartUploadCommand,
} from "@aws-sdk/client-s3";
import { root, state, settings, save, event } from "./model.mjs";
import {hlsPlan,hlsCodecArgs,checkHlsSegments,clearGeneratedHls,SEGMENT_SECONDS} from "./media-plan.mjs";
import {publishFiles,retryUpload} from "./publish-files.mjs";
export const jobs = [];
export function progressReader(onProgress){
  let buffer="",values={};
  return chunk=>{
    buffer+=chunk;
    let at;
    while((at=buffer.indexOf("\n"))>=0){
      const line=buffer.slice(0,at).trim();buffer=buffer.slice(at+1);
      const separator=line.indexOf("=");if(separator<0)continue;
      values[line.slice(0,separator)]=line.slice(separator+1);
      if(line.startsWith("progress=")){
        const time=Number(values.out_time_us??values.out_time_ms)/1e6;
        const speed=parseFloat(values.speed);
        onProgress({seconds:Number.isFinite(time)?Math.max(0,time):0,speed:Number.isFinite(speed)&&speed>0?speed:null,ended:values.progress==="end"});
        values={};
      }
    }
    if(buffer.length>65536)buffer=buffer.slice(-65536);
  };
}
export function run(bin, args, {onProgress}={}) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, args, { windowsHide: true });
    let out = "",
      err = "";
    const progress=onProgress?progressReader(onProgress):null;
    p.stdout.setEncoding("utf8");
    p.stdout.on("data", (b) => progress?progress(b):(out += b));
    p.stderr.on("data", (b) => (err = (err + b).slice(-4000)));
    p.on("error", () =>
      reject(Error("无法启动媒体工具，请检查 FFmpeg/FFprobe 路径")),
    );
    p.on("close", (code) =>
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
function phase(j,label,progress){
  j.phase=label;j.updatedAt=Date.now();j.progress=Math.max(j.progress||0,progress);
  delete j.speed;delete j.etaSeconds;delete j.processedSeconds;delete j.durationSeconds;
}
async function encode(args,j,duration,start,end,label){
  phase(j,label,start);j.durationSeconds=duration;
  await run(settings.ffmpeg,["-progress","pipe:1","-nostats",...args],{onProgress:({seconds,speed})=>{
    j.processedSeconds=duration>0?Math.min(seconds,duration):seconds;
    j.speed=speed;j.updatedAt=Date.now();
    j.etaSeconds=duration>seconds&&speed?(duration-seconds)/speed:null;
    if(duration>0)j.progress=Math.max(j.progress,Math.min(end,Math.floor(start+(end-start)*seconds/duration)));
  }});
  j.progress=end;j.processedSeconds=duration;delete j.etaSeconds;
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
  {onProgress,skipExisting=false} = {},
) {
  const size = (await stat(file)).size;
  const ContentType =
    types[Object.keys(types).find((x) => file.endsWith(x))] ||
    "application/octet-stream";
  const Bucket = settings.r2.bucket;
  const hash=createHash("sha256");for await(const chunk of createReadStream(file))hash.update(chunk);
  const Metadata={sha256:hash.digest("hex")};
  if(skipExisting){
    let existing;
    try{existing=await client.send(new HeadObjectCommand({Bucket,Key:key}));}
    catch(error){if(error.$metadata?.httpStatusCode!==404&&!['NotFound','NoSuchKey'].includes(error.name))throw error;}
    if(existing){
      if(existing.ContentLength!==size||existing.Metadata?.sha256!==Metadata.sha256){const error=Error('R2 对象内容冲突，未覆盖：'+key);error.code='R2_OBJECT_CONFLICT';throw error;}
      onProgress?.(size,size);return;
    }
  }
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
    onProgress?.(size,size);
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
      onProgress?.(offset+length,size);
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
    phase: "等待处理",
  };
  jobs.unshift(j);
  if (jobs.length > 30) jobs.length = 30;
  Promise.resolve()
    .then(() => action(j))
    .then(() => {
      j.status = "done";
      j.progress = 100;
      j.phase="已完成";j.finishedAt=j.updatedAt=Date.now();delete j.etaSeconds;delete j.speed;
      event(name + "完成");
    })
    .catch((e) => {
      j.status = "error";
      j.phase="处理失败";j.finishedAt=j.updatedAt=Date.now();delete j.etaSeconds;delete j.speed;
      j.error = (e.message||[e.name,e.code,e.errors?.[0]?.code].filter(Boolean).join(': ')||"网络或媒体处理失败").replace(
        settings.r2.secretAccessKey || "__none__",
        "[隐藏]",
      );
      event(name + "失败", "error");
    });
  return j;
}
let publishTail = Promise.resolve();
export async function publishResource(resource, j = {}) {
  const start=Math.min(95,j.progress||0);
  phase(j,"等待 R2 同步",start);
  const task = publishTail.then(async () => {
    phase(j,"检查 R2 存储预算",start);
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
    const existing=new Map();let continuation;
    do{
      const page=await retryUpload(()=>client.send(new ListObjectsV2Command({Bucket:settings.r2.bucket,Prefix:'media/'+resource.id+'/',ContinuationToken:continuation})));
      for(const item of page.Contents||[])existing.set(item.Key,item.Size||0);
      continuation=page.IsTruncated?page.NextContinuationToken:undefined;
    }while(continuation);
    let missing=0;
    for(const name of names){
      const key='media/'+resource.id+'/'+name,size=(await stat(join(dir,resource.format==='hls'?'hls':'',name))).size;
      if(!existing.has(key))missing+=size;
      else if(existing.get(key)!==size){const error=Error('R2 对象长度冲突，未覆盖：'+key);error.code='R2_OBJECT_CONFLICT';throw error;}
    }
    if ((await retryUpload(()=>bucketBytes(client))) + missing > settings.r2.storageLimitGB * 1e9)
      throw Error("上传后将超过 R2 存储预算，请清理或调整预算");
    phase(j,"同步到 R2",start);j.totalFiles=names.length;j.filesDone=0;j.totalBytes=bytes;j.uploadedBytes=0;
    const confirmedByFile=new Map();
    await publishFiles(names,async name=>{
      await retryUpload(()=>putFile(
        client,
        join(dir, resource.format === "hls" ? "hls" : "", name),
        "media/" + resource.id + "/" + name,
        undefined,
        {skipExisting:true,onProgress:confirmed=>{
          confirmedByFile.set(name,confirmed);
          j.uploadedBytes=[...confirmedByFile.values()].reduce((sum,n)=>sum+n,0);
          j.progress=Math.max(j.progress||0,Math.min(99,Math.round(start+(bytes?j.uploadedBytes/bytes:0)*(99-start))));
          j.updatedAt=Date.now();
        }},
      ));
      j.filesDone++;j.updatedAt=Date.now();
    });
    resource.url =
      settings.publicBase.replace(/\/$/, "") +
      "/media/" +
      resource.id +
      "/" +
      (resource.format === "hls" ? "index.m3u8" : "original.mp4");
    resource.storage = "r2";
    resource.publishedBytes = bytes;
    resource.replicas={local:"ready",r2:"ready",verifiedAt:Date.now(),files:names.length,bytes};
    delete resource.error;
    save();
  });
  publishTail = task.catch(() => {});
  try{return await task;}
  catch(error){resource.replicas={...resource.replicas,r2:'error',syncError:error.message||error.name||'R2 同步失败'};save();throw error;}
}
export async function processUpload(resource, file, j) {
  phase(j,"读取媒体信息",2);
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
    await encode(['-y','-hide_banner','-loglevel','error',...inputs,'-t',String(duration),'-c:v','libx264','-preset','veryfast','-crf','23','-pix_fmt','yuv420p','-c:a','aac','-b:a','128k','-movflags','+faststart',original],j,duration,3,30,resource.mediaType==='audio'?'生成音频播放画面':'生成图片播放画面');
    Object.assign(probe,JSON.parse(await run(settings.ffprobe,['-v','error','-show_format','-show_streams','-of','json',original])));
  }
  const v = probe.streams?.find((x) => x.codec_type === "video");
  if (!v) throw Error("上传的文件不包含视频轨道");
  resource.hasAudio = probe.streams.some((x) => x.codec_type === "audio");
  resource.duration = Number(probe.format.duration) || 0;
  resource.size = (await stat(original)).size;
  resource.width = v.width;
  resource.height = v.height;
  const processingStart=resource.mediaType==='audio'||resource.mediaType==='image'?35:15;
  phase(j,"生成封面",processingStart-2);
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
  j.progress = processingStart;
  if (resource.format === "mp4") {
    const compatible =
      v.codec_name === "h264" &&
      (!resource.hasAudio ||
        probe.streams.find((x) => x.codec_type === "audio").codec_name ===
          "aac");
    const normalized = join(dir, "normalized.mp4");
    await encode([
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
    ],j,resource.duration,processingStart,80,compatible?'整理 MP4（保留原画质）':'转换为兼容 MP4');
    await unlink(original);
    await rename(normalized, original);
    resource.size = (await stat(original)).size;
  }
  if (resource.format === "hls") {
    const directory=join(dir,"hls");await mkdir(directory,{recursive:true});
    let plan=hlsPlan(probe,resource.quality,{preserveHevc:resource.preserveHevc===true}),verified;
    const segment=async(start,end)=>{
      j.processingLabel=plan.label;j.mode=plan.mode;
      await encode([
        "-y","-hide_banner","-loglevel","error","-i",original,"-map","0:v:0","-map","0:a:0?",
        ...hlsCodecArgs(plan,resource.quality),"-f","hls","-hls_time",String(SEGMENT_SECONDS),
        "-hls_playlist_type","vod","-hls_flags","independent_segments+temp_file",
        "-hls_segment_filename",join(directory,"segment_%05d.ts"),join(directory,"index.m3u8")
      ],j,resource.duration,start,end,plan.label);
      return checkHlsSegments(directory,{copy:plan.videoCopy});
    };
    if(plan.videoCopy){
      try{verified=await segment(processingStart,55);}
      catch(error){
        await clearGeneratedHls(directory);
        if(resource.preserveHevc&&plan.videoCodec==="hevc")throw Error("保留 H.265 分片校验失败，未自动重编码："+error.message);
        j.detail="快速分片不满足播放要求，已转为兼容编码";
        plan={...plan,videoCopy:false,mode:"transcode",label:"兼容转码并分片"};
        verified=await segment(55,80);
      }
    }else verified=await segment(processingStart,80);
    j.progress=80;
    resource.videoCodec=plan.videoCopy?plan.videoCodec:"h264";
    resource.processing={mode:plan.mode,videoCodec:resource.videoCodec,videoCopied:plan.videoCopy,audioCopied:plan.audioCopy,segments:verified.files,maxSegmentSeconds:verified.maxSeconds};
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
  phase(j,"本地媒体已就绪",82);
  if (settings.r2.accessKeyId && settings.r2.secretAccessKey)
    try { await publishResource(resource, j); }
    catch(error){resource.replicas={local:"ready",r2:"error",syncError:error.message};save();throw error;}
}
