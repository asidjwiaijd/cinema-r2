import {createHmac, randomUUID, timingSafeEqual} from "node:crypto";
import {existsSync, readFileSync, writeFileSync, renameSync} from "node:fs";
import {stat} from "node:fs/promises";
import {join} from "node:path";
import {validUuid} from "./players.mjs";
import {root, settings, state, saveSettings} from "./model.mjs";

settings.delivery ||= {};
Object.assign(settings.delivery, {
  cdnBase:settings.delivery.cdnBase || "https://live.mc.acgzjd.cn",
  backupBase:settings.delivery.backupBase || "https://live.al.mc.acgzjd.cn",
  backupEnabled:settings.delivery.backupEnabled ?? true,
  enabled:settings.delivery.enabled ?? true,
  vodFallback:settings.delivery.vodFallback ?? true,
  dayLimitGB:settings.delivery.dayLimitGB || 0,
  monthLimitGB:settings.delivery.monthLimitGB || 0,
  sessionLimitMB:settings.delivery.sessionLimitMB || 0,
  vodStartupCdnSeconds:settings.delivery.vodStartupCdnSeconds ?? 60,
  vodEmergencyCdnSeconds:settings.delivery.vodEmergencyCdnSeconds ?? 30,
  vodPrefetchSeconds:settings.delivery.vodPrefetchSeconds ?? 600,
  vodEoDeadlineSeconds:settings.delivery.vodEoDeadlineSeconds ?? 40,
  vodBackupDeadlineSeconds:settings.delivery.vodBackupDeadlineSeconds ?? 20,
});
saveSettings();
const ledgerFile=join(root,"delivery-usage.json");
let ledger=existsSync(ledgerFile)?JSON.parse(readFileSync(ledgerFile,"utf8")):{day:"",month:"",dayBytes:0,monthBytes:0,r2Bytes:0,sessions:{}};
ledger.totals ||= {r2:0,eo:0,backup:0,unknown:0};
ledger.dayRoutes ||= {r2:0,eo:0,backup:0,unknown:ledger.dayBytes};
ledger.monthRoutes ||= {r2:0,eo:0,backup:0,unknown:ledger.monthBytes};
ledger.history ||= {};ledger.hours ||= {};ledger.players ||= {};
ledger.since ||= Date.now();
let dirty=false;
const empty=()=>({r2:0,eo:0,backup:0,unknown:0});
const sum=(a,b)=>{for(const k of ['r2','eo','backup','unknown'])a[k]=(a[k]||0)+(b[k]||0);};
const rates=[];
const timer=setInterval(()=>{if(dirty){writeFileSync(ledgerFile+".tmp",JSON.stringify(ledger));renameSync(ledgerFile+".tmp",ledgerFile);dirty=false;}},1000);timer.unref();
function rollover(){
  const date=new Date().toISOString();
  if(ledger.day!==date.slice(0,10)){ledger.day=date.slice(0,10);ledger.dayBytes=0;ledger.dayRoutes=empty();dirty=true;}
  if(ledger.month!==date.slice(0,7)){ledger.month=date.slice(0,7);ledger.monthBytes=0;ledger.monthRoutes=empty();dirty=true;}
  for(const key of Object.keys(ledger.history).sort().slice(0,-31))delete ledger.history[key];
  for(const key of Object.keys(ledger.hours).sort().slice(0,-168))delete ledger.hours[key];
  for(const [id,s] of Object.entries(ledger.sessions))if(Date.now()-s.at>9*3600000){delete ledger.sessions[id];dirty=true;}
}
export function deliveryStatus(){
  rollover();const d=settings.delivery;
  const within=(!d.dayLimitGB||ledger.dayBytes<d.dayLimitGB*1e9)&&(!d.monthLimitGB||ledger.monthBytes<d.monthLimitGB*1e9);
  return {...d,allowed:d.enabled&&within,usage:{day:ledger.day,month:ledger.month,dayBytes:ledger.dayBytes,monthBytes:ledger.monthBytes,r2Bytes:ledger.r2Bytes},measurement:"MC 客户端上报下载量，含 CDN 命中；不含网页、其他请求及未上报流量，非云账单或硬封顶"};
}
export function updateDelivery(input){
  const d={...settings.delivery};
  for(const key of ["cdnBase","backupBase"])if(input[key]!==undefined){const u=new URL(input[key]);if(u.protocol!=="https:"||u.username||u.password||u.search||u.hash||u.pathname!=="/")throw Error("CDN 地址必须为 HTTPS 域名根地址");d[key]=u.origin;}
  for(const key of ["enabled","vodFallback","backupEnabled"])if(input[key]!==undefined)d[key]=!!input[key];
  for(const key of ["dayLimitGB","monthLimitGB","sessionLimitMB"])if(input[key]!==undefined){const n=Number(input[key]);if(!Number.isFinite(n)||n<0||n>100000)throw Error("流量上限无效");d[key]=n;}
  for(const [key,min] of [["vodStartupCdnSeconds",0],["vodEmergencyCdnSeconds",30]])if(input[key]!==undefined){const n=Number(input[key]);if(!Number.isInteger(n)||n<min||n>300)throw Error("缓存回退秒数无效");d[key]=n;}
  for(const [key,min,max] of [["vodPrefetchSeconds",60,3600],["vodEoDeadlineSeconds",10,300],["vodBackupDeadlineSeconds",5,300]])if(input[key]!==undefined){const n=Number(input[key]);if(!Number.isInteger(n)||n<min||n>max)throw Error("片段预取或兜底秒数无效");d[key]=n;}
  if(d.vodBackupDeadlineSeconds>=d.vodEoDeadlineSeconds||d.vodEoDeadlineSeconds>d.vodPrefetchSeconds)throw Error("阿里云窗口必须小于 EO 窗口，EO 窗口不能大于预取范围");
  settings.delivery=d;saveSettings();
}
const sign=(value,purpose='delivery')=>createHmac("sha256",settings.bridgeToken).update(purpose+":"+value).digest("base64url");
function encode(data,purpose='delivery'){const value=Buffer.from(JSON.stringify(data)).toString('base64url');return value+'.'+sign(value,purpose);}
function token(identity={}){return encode({id:randomUUID(),exp:Date.now()+8*3600000,...identity});}
function decode(value,purpose='delivery'){
 if(typeof value!=='string'||value.length>2048)throw Error('用量会话无效');
 const parts=value.split('.');if(parts.length!==2)throw Error('用量会话无效');
 const [data,signature]=parts,a=Buffer.from(signature),b=Buffer.from(sign(data,purpose));
 if(a.length!==b.length||!timingSafeEqual(a,b))throw Error('用量会话无效');
 const parsed=JSON.parse(Buffer.from(data,'base64url'));if(!Number.isFinite(parsed.exp)||parsed.exp<Date.now())throw Error('用量会话已过期');return parsed;
}
const claims=new Map();
export function issuePlayerClaims(players){
 const now=Date.now(),seen=new Set();
 const result=[];
 for(const p of players){if(!validUuid(p.uuid))continue;seen.add(p.uuid);let c=claims.get(p.uuid);
  if(!c||c.refreshAt<now||c.connection!==p.connectionId){c={refreshAt:now+60000,connection:p.connectionId,token:encode({uuid:p.uuid,name:String(p.name||'').slice(0,32),exp:now+120000},'player')};claims.set(p.uuid,c);}
  result.push({uuid:p.uuid,token:c.token});
 }
 for(const id of claims.keys())if(!seen.has(id))claims.delete(id);
 return result;
}
function identity(value){if(!value)return {};const p=decode(value,'player');if(!validUuid(p.uuid))throw Error('玩家标识无效');return {uuid:p.uuid,name:p.name};}
export function trafficStatus(){
 rollover();const now=Date.now();while(rates.length&&rates[0].at<now-30000)rates.shift();
 const rate=empty();for(const row of rates)sum(rate,row.delta);
 const span=30;
 for(const k of Object.keys(rate))rate[k]=Math.round(rate[k]/span);
 return {since:ledger.since,day:ledger.day,month:ledger.month,today:ledger.dayRoutes,thisMonth:ledger.monthRoutes,totals:ledger.totals,rate,days:Object.entries(ledger.history).sort().map(([date,value])=>({date,...value})),hours:Object.entries(ledger.hours).sort().map(([date,value])=>({date,...value})),players:Object.entries(ledger.players).map(([uuid,p])=>({uuid,...p})).sort((a,b)=>(b.r2+b.eo+b.backup+b.unknown)-(a.r2+a.eo+a.backup+a.unknown)),sessions:Object.values(ledger.sessions).filter(s=>now-s.at<30000).length,limits:deliveryStatus(),measurement:'MC 客户端自报下载量，含缓存命中和失败响应字节；不含网页、其他客户端及未上报量。旧 CDN 用量未分类，分线路 R2 从升级后开始；不是账单。时间趋势按 UTC。'};
}
export function installDelivery(app){
 const policy=(req,res)=>{
  const d=deliveryStatus(),player=identity(req.body?.identityToken);
  let reportToken=token(player);try{const old=decode(req.body?.token);if(old.exp>Date.now()+60000&&(!player.uuid||old.uuid===player.uuid))reportToken=req.body.token;}catch{}
  res.json({primaryBase:settings.publicBase,cdnBase:d.cdnBase,backupBase:d.backupBase,backupEnabled:d.allowed&&d.backupEnabled,cdnEnabled:d.allowed,vodFallback:d.vodFallback,sessionLimitMB:d.sessionLimitMB,vodStartupCdnSeconds:d.vodStartupCdnSeconds,vodEmergencyCdnSeconds:d.vodEmergencyCdnSeconds,vodPrefetchSeconds:d.vodPrefetchSeconds,vodEoDeadlineSeconds:d.vodEoDeadlineSeconds,vodBackupDeadlineSeconds:d.vodBackupDeadlineSeconds,reportToken,expiresAt:Date.now()+15000});
 };
 app.get('/api/traffic',(req,res)=>res.json(trafficStatus()));
 app.get('/api/traffic/export',(req,res)=>{
  const rows=[['UTC 日期','R2 字节','EO 字节','阿里云字节','未分类 CDN 字节'],...trafficStatus().days.map(d=>[d.date,d.r2,d.eo,d.backup,d.unknown])];
  res.set('Content-Disposition','attachment; filename="cinema-traffic.csv"').type('text/csv').send('\ufeff'+rows.map(r=>r.join(',')).join('\r\n'));
 });
 app.get('/api/delivery/policy',policy);app.post('/api/delivery/policy',policy);
 app.post('/api/delivery/report',(req,res)=>{
  const session=decode(req.body.token);rollover();
  const cdn=Number(req.body.cdnBytes),r2=Number(req.body.r2Bytes),split=req.body.eoBytes!==undefined||req.body.backupBytes!==undefined;
  const eo=split?Number(req.body.eoBytes):0,backup=split?Number(req.body.backupBytes):0;
  if([cdn,r2,eo,backup].some(n=>!Number.isSafeInteger(n)||n<0||n>1e12)||split&&cdn!==eo+backup)throw Error('用量计数无效');
  const old=ledger.sessions[session.id]||{cdn:0,r2:0,eo:0,backup:0,at:0};
  if(old.at&&(old.split??false)!==split)throw Error('同一会话不能切换计量格式');
  if(cdn<old.cdn||r2<old.r2||eo<(old.eo||0)||backup<(old.backup||0))throw Error('用量计数不能回退');
  if(!ledger.sessions[session.id]&&Object.keys(ledger.sessions).length>=10000)throw Error('用量会话过多');
  const delta={r2:r2-old.r2,eo:eo-(old.eo||0),backup:backup-(old.backup||0),unknown:split?0:cdn-old.cdn};
  ledger.dayBytes+=cdn-old.cdn;ledger.monthBytes+=cdn-old.cdn;ledger.r2Bytes+=delta.r2;
  sum(ledger.totals,delta);sum(ledger.dayRoutes,delta);sum(ledger.monthRoutes,delta);
  const date=new Date().toISOString();sum(ledger.history[ledger.day] ||= empty(),delta);sum(ledger.hours[date.slice(0,13)] ||= empty(),delta);
  if(session.uuid){const player=ledger.players[session.uuid] ||= {...empty(),name:session.name};player.name=session.name;player.lastSeen=Date.now();sum(player,delta);}
  ledger.sessions[session.id]={cdn,r2,eo,backup,split,uuid:session.uuid,at:Date.now()};
  if(Object.values(delta).some(n=>n>0)){rates.push({at:Date.now(),delta});while(rates.length>10000)rates.shift();}
  dirty=true;const d=deliveryStatus();res.json({ok:true,cdnEnabled:d.allowed,vodFallback:d.vodFallback,sessionLimitMB:d.sessionLimitMB});
 });
  app.get("/media/:id/:file",async(req,res,next)=>{
    const r=state.resources.find(r=>r.id===req.params.id&&!r.archived&&r.kind==="vod"&&r.status==="ready");
    if(!r||!/^[a-zA-Z0-9_-]+\.(mp4|m3u8|ts|jpg)$/.test(req.params.file))return res.status(404).end();
    if(r.replicas?.local==="error")return res.status(503).set("Cache-Control","no-store").json({error:"副本校验失败，请先修复并重新同步"});
    if(!deliveryStatus().allowed)return res.status(503).set("Cache-Control","no-store").json({error:"CDN 已关闭或客户端上报流量达到配置上限"});
    const dir=join(root,"resources",r.id,r.format==="hls"&&req.params.file!=="poster.jpg"?"hls":"");
    try {
      await stat(join(dir,req.params.file));
      res.set("Access-Control-Allow-Origin","*");
      res.sendFile(req.params.file,{root:dir,dotfiles:"deny",maxAge:"365d",immutable:true},error=>{if(error)next(error);});
    }catch{res.status(404).set("Cache-Control","no-store").end();}
  });
}
