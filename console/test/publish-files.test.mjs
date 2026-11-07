import {test} from 'node:test';
import assert from 'node:assert/strict';
import {publishFiles,retryUpload} from '../server/publish-files.mjs';

test('bounded parallel payload writes finish before any manifest',async()=>{
 let active=0,peak=0;const done=[];
 const names=['index.m3u8',...Array.from({length:9},(_,i)=>`segment_${i}.ts`)];
 await publishFiles(names,async name=>{
  if(name.endsWith('.m3u8'))assert.equal(done.length,9);
  active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,5));done.push(name);active--;
 });
 assert.equal(peak,4);assert.equal(active,0);assert.equal(done.at(-1),'index.m3u8');assert.equal(done.length,names.length);
});
test('failed writes drain active work, stop new work and never expose a manifest',async()=>{
 let active=0;const requested=[];
 await assert.rejects(publishFiles(['bad.ts','slow.ts','later.ts','index.m3u8'],async name=>{
  requested.push(name);active++;
  try{await new Promise(r=>setTimeout(r,name==='bad.ts'?5:20));if(name==='bad.ts')throw Error('upload failed');}
  finally{active--;}
 },2),/upload failed/);
 assert.equal(active,0);assert.deepEqual(requested,['bad.ts','slow.ts']);
});
test('transient R2 failures retry within a fixed bound; authentication and conflicts do not',async()=>{
 let count=0;const waits=[];
 assert.equal(await retryUpload(async()=>{if(++count<3)throw Error('socket reset');return 'ok';},{delay:async ms=>waits.push(ms)}),'ok');
 assert.equal(count,3);assert.deepEqual(waits,[1000,2000]);
 for(const error of [Object.assign(Error('denied'),{$metadata:{httpStatusCode:403}}),Object.assign(Error('conflict'),{code:'R2_OBJECT_CONFLICT'})]){
  count=0;await assert.rejects(retryUpload(async()=>{count++;throw error;},{delay:async()=>{}}));assert.equal(count,1);
 }
 count=0;await assert.rejects(retryUpload(async()=>{count++;throw Error('offline');},{delay:async()=>{}}));assert.equal(count,3);
});
