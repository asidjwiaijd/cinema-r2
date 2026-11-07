import {readFile,readdir,stat,unlink} from "node:fs/promises";
import {join,resolve,dirname} from "node:path";

export const SEGMENT_SECONDS=12;
const MAX_COPY_SEGMENT_SECONDS=30,MAX_SEGMENT_BYTES=30*1024*1024;

export function hlsPlan(probe,quality,{preserveHevc=false}={}){
  const video=probe.streams?.find(s=>s.codec_type==="video");
  const audio=probe.streams?.find(s=>s.codec_type==="audio");
  const height=quality===1080?1080:720,width=height*16/9;
  const rotations=[video?.tags?.rotate,...(video?.side_data_list||[]).map(s=>s.rotation)].filter(v=>v!==undefined);
  const rotated=rotations.some(v=>!Number.isFinite(Number(v))||Number(v)%360!==0);
  const hevc=preserveHevc&&video?.codec_name==="hevc";
  const copyCodec=video?.codec_name==="h264"&&video.pix_fmt==="yuv420p"||hevc&&["yuv420p","yuv420p10le"].includes(video.pix_fmt);
  const videoCopy=copyCodec&&video.width>0&&video.height>0&&video.width<=width&&video.height<=height&&!rotated;
  if(hevc&&!videoCopy)throw Error("原 H.265 的尺寸、像素格式或旋转不符合直接分片要求，请选择匹配清晰度或兼容 H.264 模式");
  const audioCopy=!audio||audio.codec_name==="aac";
  return {videoCopy,audioCopy,videoCodec:video?.codec_name,mode:videoCopy?(audioCopy?"copy":"audio-transcode"):"transcode",
    label:videoCopy?(hevc?"HEVC 原码流分片（MC 优先）":audioCopy?"快速分片（保留原画质）":"快速分片 · 转换音频"):"转码并分片（"+height+"p）"};
}

export function hlsCodecArgs(plan,quality){
  return [
    ...(plan.videoCopy?["-c:v","copy","-bsf:v",plan.videoCodec==="hevc"?"hevc_mp4toannexb":"h264_mp4toannexb"]:[
      "-c:v","libx264","-preset","veryfast","-crf","23","-vf",
      quality===1080?"scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2":"scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
      "-pix_fmt","yuv420p","-r","24","-g","288","-keyint_min","288","-sc_threshold","0",
      // Keep 12-second TS below the client's 32 MiB response ceiling, including bursts.
      "-maxrate",quality===1080?"16M":"10M","-bufsize",quality===1080?"32M":"20M"
    ]),
    ...(plan.audioCopy?["-c:a","copy"]:["-c:a","aac","-b:a","128k"])
  ];
}

export async function checkHlsSegments(directory,{copy=false}={}){
  const text=await readFile(join(directory,"index.m3u8"),"utf8");
  if(!text.startsWith("#EXTM3U")||!text.includes("#EXT-X-ENDLIST"))throw Error("点播分片清单尚未完整生成");
  const names=text.split(/\r?\n/).filter(s=>s&&!s.startsWith("#"));
  const durations=[...text.matchAll(/^#EXTINF:([\d.]+)/gm)].map(m=>Number(m[1]));
  if(!names.length||names.length!==durations.length||names.some(n=>!/^segment_\d+\.ts$/.test(n)))throw Error("分片清单格式无效");
  if(durations.some(n=>!Number.isFinite(n)||n<=0||copy&&n>MAX_COPY_SEGMENT_SECONDS))throw Error("原片关键帧间隔过长，需要兼容转码");
  for(const name of names){const size=(await stat(join(directory,name))).size;if(size<=0||size>MAX_SEGMENT_BYTES)throw Error("分片过大或为空，需要兼容转码");}
  return {files:names.length,maxSeconds:Math.max(...durations)};
}

export async function clearGeneratedHls(directory){
  const parent=resolve(directory);
  for(const name of await readdir(parent)){
    const file=resolve(parent,name);
    if(dirname(file)!==parent||!/^(?:segment_\d+\.ts|index\.m3u8)(?:\.tmp)?$/.test(name))throw Error("分片目录包含非生成文件，未清理");
    await unlink(file);
  }
}
