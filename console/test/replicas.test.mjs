import {test,after} from "node:test";
import assert from "node:assert/strict";
import {mkdtemp,mkdir,writeFile,readFile,rm} from "node:fs/promises";
import {join,dirname,basename} from "node:path";
import {tmpdir} from "node:os";
import {createHash} from "node:crypto";
import {Readable} from "node:stream";
const dir=await mkdtemp(join(tmpdir(),"cinema-replicas-"));process.env.CINEMA_DATA=dir;
const {syncResource,manifestFiles}=await import("../server/replicas.mjs");
const {settings}=await import("../server/model.mjs");
const manifest=Buffer.from("#EXTM3U\n#EXTINF:4,\nsegment_001.ts\n#EXT-X-ENDLIST\n");
function mock(objects){return {uploads:0,downloads:0,async send(command){const key=command.input.Key,type=command.constructor.name;
 if(type==="HeadObjectCommand"){if(!objects.has(key))throw Object.assign(Error("missing"),{name:"NotFound"});const bytes=objects.get(key);return {ContentLength:bytes.length,ETag:'"'+createHash("md5").update(bytes).digest("hex")+'"'};}
 if(type==="ListObjectsV2Command")return {Contents:[...objects.values()].map(bytes=>({Size:bytes.length}))};
 if(type==="GetObjectCommand"){this.downloads++;return {Body:Readable.from(objects.get(key))};}
 if(type==="PutObjectCommand"){this.uploads++;const chunks=[];for await(const chunk of command.input.Body)chunks.push(chunk);objects.set(key,Buffer.concat(chunks));return {};}
 throw Error(type);
}};}
after(async()=>{assert.equal(dirname(dir),tmpdir());assert.ok(basename(dir).startsWith("cinema-replicas-"));await rm(dir,{recursive:true,force:true});});
test("R2 fills missing local segments; verification avoids uploading identical files",async()=>{
 const id="fixture-a",folder=join(dir,"resources",id,"hls");await mkdir(folder,{recursive:true});await writeFile(join(folder,"index.m3u8"),manifest);
 const objects=new Map([["media/"+id+"/index.m3u8",manifest],["media/"+id+"/segment_001.ts",Buffer.from([1,2,3])]]),client=mock(objects);
 const r={id,kind:"vod",status:"ready",format:"hls",storage:"r2",url:settings.publicBase+"/media/"+id+"/index.m3u8"};
 await syncResource(r,client);assert.equal(client.uploads,0);assert.equal(client.downloads,1);assert.deepEqual(await readFile(join(folder,"segment_001.ts")),Buffer.from([1,2,3]));assert.equal(r.replicas.local,"ready");assert.equal(r.replicas.r2,"ready");
 await syncResource(r,client);assert.equal(client.downloads,1);assert.equal(client.uploads,0);
});
test("local fills missing R2 segments and rejects conflicting copies without overwrite",async()=>{
 const id="fixture-b",folder=join(dir,"resources",id,"hls");await mkdir(folder,{recursive:true});await writeFile(join(folder,"index.m3u8"),manifest);await writeFile(join(folder,"segment_001.ts"),Buffer.from([4,5,6]));
 const client=mock(new Map([["media/"+id+"/index.m3u8",manifest]]));
 const r={id,kind:"vod",status:"ready",format:"hls",storage:"local",url:settings.publicBase+"/media/"+id+"/index.m3u8"};
 await syncResource(r,client);assert.equal(client.uploads,1);
 await writeFile(join(folder,"segment_001.ts"),Buffer.from([7,8,9]));await assert.rejects(syncResource(r,client),/内容冲突/);assert.equal(client.uploads,1);
});
test("unsafe manifest paths and live manifests cannot enter the mirror",()=>{
 assert.throws(()=>manifestFiles("#EXTM3U\n../secret.ts\n#EXT-X-ENDLIST\n"),/不支持/);
 assert.throws(()=>manifestFiles("#EXTM3U\nhttps://untrusted.example/secret.ts\n#EXT-X-ENDLIST\n"),/不支持/);
 assert.throws(()=>manifestFiles("#EXTM3U\nsegment_001.ts\n"),/点播/);
});
