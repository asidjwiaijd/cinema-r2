/** All payloads finish before a manifest is exposed. Drain in-flight writes on error. */
export async function retryUpload(send,{attempts=3,delay=ms=>new Promise(r=>setTimeout(r,ms))}={}){
  for(let i=0;;i++)try{return await send();}catch(error){
    const status=error?.$metadata?.httpStatusCode;
    if(i+1>=attempts||error?.code==='R2_OBJECT_CONFLICT'||status>=400&&status<500&&![408,429].includes(status))throw error;
    await delay(1000*2**i);
  }
}
export async function publishFiles(names,send,concurrency=4){
  const limit=Math.max(1,Math.min(4,Number(concurrency)||4));
  async function group(items){
    let next=0,error;
    await Promise.all(Array.from({length:Math.min(limit,items.length)},async()=>{
      while(!error&&next<items.length){
        const name=items[next++];
        try{await send(name);}catch(e){error??=e;}
      }
    }));
    if(error)throw error;
  }
  await group(names.filter(n=>!n.endsWith('.m3u8')));
  await group(names.filter(n=>n.endsWith('.m3u8')));
}
