import {test,after} from "node:test";
import assert from "node:assert/strict";
import {mkdtemp,readFile,mkdir,writeFile,open,rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join,dirname,basename} from "node:path";
import {hlsPlan,checkHlsSegments} from "../server/media-plan.mjs";

const dir=await mkdtemp(join(tmpdir(),"cinema-fast-hls-"));process.env.CINEMA_DATA=dir;
const {settings,state}=await import("../server/model.mjs");
const {processUpload,run,progressReader}=await import("../server/media.mjs");
settings.ffmpeg=process.env.CINEMA_FFMPEG||"ffmpeg";settings.ffprobe=process.env.CINEMA_FFPROBE||"ffprobe";
settings.r2.accessKeyId="";settings.r2.secretAccessKey="";
after(async()=>{assert.equal(dirname(dir),tmpdir());assert.ok(basename(dir).startsWith("cinema-fast-hls-"));await rm(dir,{recursive:true,force:true});});
const execute=args=>run(settings.ffmpeg,["-y","-hide_banner","-loglevel","error",...args]);
const probe=file=>run(settings.ffprobe,["-v","error","-show_streams","-show_format","-of","json",file]).then(JSON.parse);
const hashFrames=async file=>(await execute(["-i",file,"-map","0:v:0","-frames:v","5","-f","framemd5","-"])).split(/\r?\n/).filter(l=>l&&!l.startsWith("#")).map(l=>l.split(",").at(-1).trim());
async function processMedia(id,input,quality=720,preserveHevc=false){
 const r={id,mediaType:"video",format:"hls",quality,preserveHevc};state.resources.push(r);
 const changes=[],j=new Proxy({}, {set(object,key,value){if(key==="progress")changes.push(value);object[key]=value;return true;}});
 await processUpload(r,input,j);
 assert.equal(r.status,"ready");assert.equal(j.progress,82);
 assert.ok(changes.every((n,i)=>!i||n>=changes[i-1]),changes.join(","));
 return {r,j,playlist:join(dir,"resources",id,"hls/index.m3u8"),original:join(dir,"resources",id,"original.mp4")};
}
test("H264 AAC preserves frames and fractional frame rate without video or audio encoding",async()=>{
 const input=join(dir,"compatible.mp4");
 await execute(["-f","lavfi","-i","testsrc2=s=320x180:r=24000/1001","-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t","4","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-c:a","aac",input]);
 const {r,playlist,original}=await processMedia("copy",input);
 assert.equal(r.processing.mode,"copy");assert.equal(r.processing.videoCopied,true);assert.equal(r.processing.audioCopied,true);
 assert.deepEqual(await hashFrames(original),await hashFrames(playlist));
 const data=await probe(playlist);assert.equal(data.streams.find(s=>s.codec_type==="video").r_frame_rate,"24000/1001");assert.equal(data.streams.find(s=>s.codec_type==="audio").codec_name,"aac");
});
test("non-AAC audio is converted while the H264 video stays unchanged",async()=>{
 const input=join(dir,"pcm.mkv");
 await execute(["-f","lavfi","-i","testsrc2=s=160x90:r=24","-f","lavfi","-i","sine=frequency=440","-t","3","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-c:a","pcm_s16le",input]);
 const {r,playlist,original}=await processMedia("audio-only",input);
 assert.equal(r.processing.mode,"audio-transcode");assert.equal(r.processing.videoCopied,true);assert.equal(r.processing.audioCopied,false);
 assert.deepEqual(await hashFrames(original),await hashFrames(playlist));assert.equal((await probe(playlist)).streams.find(s=>s.codec_type==="audio").codec_name,"aac");
});
test("720p selection still scales a larger source instead of copying 1080p",async()=>{
 const input=join(dir,"large.mp4");await execute(["-f","lavfi","-i","color=c=blue:s=1920x1080:r=24","-t","2","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p",input]);
 const {r,playlist}=await processMedia("scaled",input,720);assert.equal(r.processing.videoCopied,false);
 const video=(await probe(playlist)).streams.find(s=>s.codec_type==="video");assert.equal(video.width,1280);assert.equal(video.height,720);
});
test("a long GOP automatically falls back to independently playable short segments",async()=>{
 const input=join(dir,"long-gop.mp4");await execute(["-f","lavfi","-i","testsrc2=s=160x90:r=24","-t","40","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-g","960","-keyint_min","960","-sc_threshold","0",input]);
 const {r,j,playlist}=await processMedia("long-gop",input);assert.equal(r.processing.videoCopied,false);assert.match(j.detail,/兼容编码/);
 assert.ok(r.processing.maxSegmentSeconds<=12.1);assert.ok(r.processing.segments>=4);
 assert.match(await readFile(playlist,"utf8"),/#EXT-X-INDEPENDENT-SEGMENTS/);
});
test("HEVC, high-bit-depth H264 and rotated video do not enter the copy path",()=>{
 const base={codec_type:"video",codec_name:"h264",pix_fmt:"yuv420p",width:1920,height:1080};
 for(const patch of [{codec_name:"hevc"},{pix_fmt:"yuv420p10le"},{side_data_list:[{rotation:90}]}])assert.equal(hlsPlan({streams:[{...base,...patch}]},1080).videoCopy,false);
});
test("explicit HEVC preservation keeps 10-bit frames and AAC unchanged for MC",async()=>{
 const input=join(dir,"hevc10.mp4");
 await execute(["-f","lavfi","-i","testsrc2=s=160x90:r=24000/1001","-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t","3","-c:v","libx265","-preset","ultrafast","-x265-params","pools=2:frame-threads=2:log-level=error","-pix_fmt","yuv420p10le","-c:a","aac",input]);
 const {r,playlist,original}=await processMedia("hevc-copy",input,1080,true);
 assert.equal(r.videoCodec,"hevc");assert.equal(r.processing.videoCopied,true);assert.equal(r.processing.audioCopied,true);
 const video=(await probe(playlist)).streams.find(s=>s.codec_type==="video");assert.equal(video.codec_name,"hevc");assert.equal(video.pix_fmt,"yuv420p10le");assert.equal(video.r_frame_rate,"24000/1001");
 assert.deepEqual(await hashFrames(original),await hashFrames(playlist));
});
test("preserving HEVC refuses an incompatible resize and never silently re-encodes",()=>{
 const stream={codec_type:"video",codec_name:"hevc",pix_fmt:"yuv420p",width:1920,height:1080};
 assert.throws(()=>hlsPlan({streams:[stream]},720,{preserveHevc:true}),/原 H.265/);
 assert.equal(hlsPlan({streams:[stream]},1080,{preserveHevc:true}).videoCopy,true);
 assert.equal(hlsPlan({streams:[stream]},1080).videoCopy,false);
});
test("long HEVC GOP is reported instead of silently changing the chosen codec",async()=>{
 const input=join(dir,"hevc-long-gop.mp4");
 await execute(["-f","lavfi","-i","testsrc2=s=160x90:r=24","-t","40","-c:v","libx265","-preset","ultrafast","-x265-params","pools=2:frame-threads=2:log-level=error:keyint=960:min-keyint=960:scenecut=0","-pix_fmt","yuv420p",input]);
 await assert.rejects(processMedia("hevc-long-gop",input,1080,true),/未自动重编码/);
});
test("oversized TS are rejected before publication",async()=>{
 const directory=join(dir,"oversized");await mkdir(directory);
 await writeFile(join(directory,"index.m3u8"),"#EXTM3U\n#EXTINF:12,\nsegment_00000.ts\n#EXT-X-ENDLIST\n");
 const file=await open(join(directory,"segment_00000.ts"),"w");await file.truncate(31*1024*1024);await file.close();
 await assert.rejects(checkHlsSegments(directory,{copy:true}),/分片过大/);
});
test("FFmpeg progress survives fragmented lines and uses microseconds",()=>{
 const updates=[],read=progressReader(x=>updates.push(x));read("out_time_us=12");read("500000\nspeed=2.5x\nprogress=continue\nout_time_us=25000000\nspeed=N/A\nprogress=end\n");
 assert.equal(updates[0].seconds,12.5);assert.equal(updates[0].speed,2.5);assert.equal(updates[1].ended,true);assert.equal(updates[1].speed,null);
});
test("real FFmpeg emits ongoing progress before completing",async()=>{
 const updates=[];await run(settings.ffmpeg,["-hide_banner","-loglevel","error","-re","-f","lavfi","-i","color=c=black:s=64x64:r=10","-t","2","-progress","pipe:1","-nostats","-f","null","-"],{onProgress:x=>updates.push(x)});
 assert.ok(updates.some(x=>x.seconds>0&&!x.ended));assert.equal(updates.at(-1).ended,true);assert.ok(updates.at(-1).seconds>=1.9);
});
