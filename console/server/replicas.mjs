import {createHash,randomUUID} from "node:crypto";
import {createReadStream,createWriteStream} from "node:fs";
import {mkdir,readFile,rename,stat,statfs,unlink} from "node:fs/promises";
import {pipeline} from "node:stream/promises";
import {join,dirname} from "node:path";
import {HeadObjectCommand,GetObjectCommand} from "@aws-sdk/client-s3";
import {root,settings,state,save} from "./model.mjs";
import {s3,putFile,bucketBytes,job,jobs} from "./media.mjs";
let active;
const safeName=name=>/^[a-zA-Z0-9_-]+\.(ts|m3u8|mp4)$/.test(name);
export function manifestFiles(text){
  if(!text.startsWith("#EXTM3U")||!text.includes("#EXT-X-ENDLIST"))throw Error("只同步完整的点播 HLS 清单");
  const names=text.split(/\r?\n/).filter(n=>n&&!n.startsWith("#"));
  if(!names.length||names.length>10000||names.some(n=>!safeName(n)||!n.endsWith(".ts"))||text.includes("URI="))throw Error("同步清单包含不支持的分片路径");
  return [...new Set(names),"index.m3u8"];
}
async function digest(file){const hash=createHash("md5"),sha=createHash("sha256");for await(const chunk of createReadStream(file)){hash.update(chunk);sha.update(chunk);}return {md5:hash.digest("hex"),sha:sha.digest("hex")};}
async function head(client,key){
  try{return await client.send(new HeadObjectCommand({Bucket:settings.r2.bucket,Key:key}));}
  catch(error){if(error.$metadata?.httpStatusCode===404||error.name==="NotFound"||error.name==="NoSuchKey")return null;throw error;}
}
async function download(client,key,file,size){
  const fs=await statfs(root);if(fs.bavail*fs.bsize<size+1024**3)throw Error("本地副本同步空间不足，保留 1GB 余量");
  await mkdir(dirname(file),{recursive:true});const temp=file+"."+randomUUID()+".part";
  try{const object=await client.send(new GetObjectCommand({Bucket:settings.r2.bucket,Key:key}));await pipeline(object.Body,createWriteStream(temp));if((await stat(temp)).size!==size)throw Error("R2 副本下载长度不一致");await rename(temp,file);}
  finally{await unlink(temp).catch(()=>{});}
}
async function localSize(file){try{return(await stat(file)).size;}catch(error){if(error.code==="ENOENT")return null;throw error;}}
export async function syncResource(r,client=s3()){
  if(r.kind!=="vod"||!r.url||r.archived||r.status!=="ready")return;
  const expected=new URL("/media/"+r.id+"/"+(r.format==="hls"?"index.m3u8":"original.mp4"),settings.publicBase);
  if(new URL(r.url).origin!==expected.origin&&r.storage==="external")return;
  const dir=join(root,"resources",r.id,r.format==="hls"?"hls":"");await mkdir(dir,{recursive:true});
  const entry=r.format==="hls"?"index.m3u8":"original.mp4",prefix="media/"+r.id+"/";
  const rootHead=await head(client,prefix+entry);
  if(await localSize(join(dir,entry))===null){
    if(!rootHead)throw Error("R2 和本地都没有可用入口");
    if(r.format==="hls"&&rootHead.ContentLength>1024*1024)throw Error("HLS 清单过大");
    await download(client,prefix+entry,join(dir,entry),rootHead.ContentLength);
  }
  const names=r.format==="hls"?manifestFiles(await readFile(join(dir,entry),"utf8")):[entry];
  let used=await bucketBytes(client),total=0;
  for(const name of names){
    const key=prefix+name,file=join(dir,name),remote=name===entry?rootHead:await head(client,key);
    let size=await localSize(file);
    if(size===null){if(!remote)throw Error("两端都缺少分片 "+name);await download(client,key,file,remote.ContentLength);size=remote.ContentLength;}
    if(size<=0)throw Error("副本为空："+name);
    const hashes=await digest(file),etag=remote?.ETag?.replaceAll('"',"");
    if(remote&&remote.ContentLength===size&&((/^[a-f0-9]{32}$/.test(etag)&&etag!==hashes.md5)||(remote.Metadata?.sha256&&remote.Metadata.sha256!==hashes.sha)))throw Error("R2 与本地内容冲突，未覆盖："+name);
    if(remote&&remote.ContentLength!==size)throw Error("R2 与本地长度冲突，未覆盖："+name);
    if(!remote){
      const next=used-(remote?.ContentLength||0)+size;
      if(next>settings.r2.storageLimitGB*1e9)throw Error("同步将超过 R2 存储预算");
      await putFile(client,file,key);used=next;
      const verify=await head(client,key);if(!verify||verify.ContentLength!==size)throw Error("R2 同步长度校验失败："+name);
    }
    total+=size;
  }
  r.url=expected.href;r.storage="r2";r.publishedBytes=total;
  r.replicas={local:"ready",r2:"ready",verifiedAt:Date.now(),files:names.length,bytes:total};delete r.error;save();
}
export function syncAll(){
  if(active)return active;
  if(jobs.some(j=>j.status==="running"))throw Error("请等待正在进行的媒体任务完成后再同步");
  const task=job("校验并同步 R2 / 源站副本",async j=>{
    const resources=state.resources.filter(r=>!r.archived&&r.kind==="vod"&&r.status==="ready"&&r.storage!=="external");
    const client=s3();let failures=0;
    for(let i=0;i<resources.length;i++){
      const r=resources[i];try{await syncResource(r,client);}catch(error){failures++;r.replicas={...r.replicas,...(/冲突|两端都|长度不一致|为空/.test(error.message)?{local:"error"}:{}),syncError:error.message,lastAttempt:Date.now()};save();}
      j.progress=Math.round((i+1)/Math.max(1,resources.length)*95);
    }
    if(failures)throw Error(failures+" 项副本未同步，已保留现有文件，可重试");
  });
  active=task;const timer=setInterval(()=>{if(task.status!=="running"){clearInterval(timer);active=null;}},250);timer.unref();return task;
}
export function scheduleSync(){
  if(!settings.r2.accessKeyId)return;
  const attempt=()=>{if(!jobs.some(j=>j.status==="running"))syncAll();};
  const first=setTimeout(attempt,3000);first.unref();
  const timer=setInterval(()=>{if(!state.resources.some(r=>r.status==="processing"))attempt();},3600000);timer.unref();
}
