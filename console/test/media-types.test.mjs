import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
const dir=await mkdtemp(join(tmpdir(),'cinema-media-types-'));process.env.CINEMA_DATA=dir;
const {processUpload,run}=await import('../server/media.mjs');const {settings,state}=await import('../server/model.mjs');
settings.ffmpeg=process.env.CINEMA_FFMPEG||'ffmpeg';settings.ffprobe=process.env.CINEMA_FFPROBE||'ffprobe';settings.r2.accessKeyId='';settings.r2.secretAccessKey='';
after(()=>rm(dir,{recursive:true,force:true}));
test('audio upload becomes an AAC movie with a visible card, duration and MP4 index',async()=>{
 const input=join(dir,'tone.wav');await run(settings.ffmpeg,['-y','-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=440:duration=3',input]);
 const r={id:'audio-test',mediaType:'audio',format:'mp4',quality:720};state.resources.push(r);await processUpload(r,input,{});
 const probe=JSON.parse(await run(settings.ffprobe,['-v','error','-show_streams','-show_format','-of','json',join(dir,'resources',r.id,'original.mp4')]));
 assert.equal(r.status,'ready');assert.equal(r.hasAudio,true);assert.ok(r.duration>=2.9&&r.duration<3.2);assert.ok(probe.streams.some(s=>s.codec_type==='video'&&s.codec_name==='h264'));assert.ok(probe.streams.some(s=>s.codec_type==='audio'&&s.codec_name==='aac'));
});
test('still image becomes silent HLS with a bounded display duration and preserved aspect ratio',async()=>{
 const input=join(dir,'still.png');await run(settings.ffmpeg,['-y','-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=green:s=320x240','-frames:v','1',input]);
 const r={id:'image-test',mediaType:'image',imageSeconds:3,format:'hls',quality:720};state.resources.push(r);await processUpload(r,input,{});
 assert.equal(r.status,'ready');assert.equal(r.hasAudio,false);assert.ok(r.duration>=2.9&&r.duration<3.2);assert.equal(r.width,1280);assert.equal(r.height,720);
 const manifest=await readFile(join(dir,'resources',r.id,'hls/index.m3u8'),'utf8');assert.ok(manifest.includes('#EXT-X-ENDLIST'));assert.ok(manifest.includes('segment_00000.ts'));
});
test('an audio extension containing an image does not pass track validation',async()=>{
 const input=join(dir,'fake.wav');await run(settings.ffmpeg,['-y','-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=red:s=64x64','-frames:v','1','-f','image2','-c:v','png',input]);
 await assert.rejects(processUpload({id:'fake-audio',mediaType:'audio',format:'mp4',quality:720},input,{}),/音频轨道/);
});
