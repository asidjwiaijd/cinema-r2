import {existsSync,readFileSync,writeFileSync,renameSync} from 'node:fs';
import {join} from 'node:path';
import {root,queueCommand,event} from './model.mjs';
const file=join(root,'players.json');
const records=existsSync(file)?JSON.parse(readFileSync(file,'utf8')):{};
let lastHeartbeat=0,lastSave=0,rosterSupported=false,bans=[],server={};
export const validUuid=value=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value||'');
export function ingestPlayers(body){
 if(body.players===undefined)return;
 if(!Array.isArray(body.players)||body.players.length>1000||!Array.isArray(body.bans)||body.bans.length>1000)throw Error('玩家心跳格式无效');
 const now=Date.now();rosterSupported=true;lastHeartbeat=now;
 const seen=new Set();
 for(const p of body.players){
  if(!validUuid(p.uuid)||typeof p.name!=='string'||p.name.length>32)continue;
  seen.add(p.uuid);const old=records[p.uuid]||{uuid:p.uuid,firstSeen:now,joins:0};
  if(!old.online||old.connectionId!==p.connectionId)old.joins++;
  records[p.uuid]={...old,name:p.name,online:true,connectionId:String(p.connectionId||''),lastSeen:now,ping:Math.max(0,Math.min(60000,Number(p.ping)||0)),dimension:String(p.dimension||'').slice(0,80),x:Math.round(Number(p.x)||0),y:Math.round(Number(p.y)||0),z:Math.round(Number(p.z)||0),gameMode:String(p.gameMode||'').slice(0,30),joinedAt:Number(p.joinedAt)||now};
  records[p.uuid].cinemaAdmin=!!p.cinemaAdmin;records[p.uuid].adminActive=!!p.adminActive;
 }
 for(const p of Object.values(records))if(!seen.has(p.uuid))p.online=false;
 if(Array.isArray(body.cinemaAdmins))for(const p of Object.values(records))p.cinemaAdmin=body.cinemaAdmins.includes(p.uuid);
 bans=body.bans.filter(b=>validUuid(b.uuid)).map(b=>({uuid:b.uuid,name:String(b.name||'').slice(0,32),reason:String(b.reason||'').slice(0,200)}));
 server={maxPlayers:Number(body.maxPlayers)||0,online:seen.size,arena:body.arena};
 const sorted=Object.values(records).sort((a,b)=>b.lastSeen-a.lastSeen);
 for(const p of sorted.slice(2000))delete records[p.uuid];
 if(now-lastSave>5000){writeFileSync(file+'.tmp',JSON.stringify(records));renameSync(file+'.tmp',file);lastSave=now;}
}
export function playerStatus(screens=[]){
 const connected=Date.now()-lastHeartbeat<6000;
 const players=Object.values(records).map(p=>{
  const watching=connected&&p.online?screens.flatMap(s=>(s.playback||[]).filter(r=>r.uuid===p.uuid&&Date.now()-r.receivedAt<4000).map(r=>({screenId:s.id,screenName:s.name,...r}))):[];
  return {...p,online:connected&&p.online,adminActive:connected&&p.online&&p.adminActive,connectionId:undefined,watching,banned:bans.some(b=>b.uuid===p.uuid)};
 }).sort((a,b)=>Number(b.online)-Number(a.online)||b.lastSeen-a.lastSeen);
 return {supported:rosterSupported,connected,players,bans,server};
}
export function installPlayers(app,getScreens){
 app.post('/api/arena/build',(req,res)=>{if(Date.now()-lastHeartbeat>=6000)throw Error('MC 服务端未连接');if(req.body.confirm!==true)throw Error('请确认在主影院周围搭建林地场景');if(server.arena?.building)throw Error('影院正在搭建');const c=queueCommand('','arena:build',{expiresAt:Date.now()+30000});res.status(202).json({commandIds:[c.id]});});
 app.get('/api/players',(req,res)=>res.json(playerStatus(getScreens())));
 app.post('/api/players/:uuid/action',(req,res)=>{
  if(Date.now()-lastHeartbeat>=6000)throw Error('MC 服务端未连接');
  const uuid=req.params.uuid;if(!validUuid(uuid))throw Error('玩家 UUID 无效');
  const action=req.body.action;if(!['kick','ban','unban','teleport','admin-grant','admin-revoke'].includes(action))throw Error('玩家操作无效');
  const player=records[uuid],ban=bans.find(b=>b.uuid===uuid);
  if(!player&&!ban)throw Error('玩家不存在');
  if(['kick','teleport','admin-grant'].includes(action)&&!player?.online)throw Error('玩家已经离线');
  const data={playerUuid:uuid,playerName:player?.name||ban.name,reason:String(req.body.reason||'影院管理员操作').slice(0,200),expiresAt:Date.now()+30000};
  if(action==='admin-grant')data.connectionId=player.connectionId;
  if(action==='teleport'){
   const screen=getScreens().find(s=>s.id===req.body.screenId&&s.online);
   if(!screen)throw Error('请选择在线影院屏幕');
   Object.assign(data,{dimension:screen.dimension,x:screen.x,y:screen.y,z:screen.z,screenId:screen.id});
  }
  const command=queueCommand('', 'player:'+action,data);
  event('玩家操作已排队：'+data.playerName+' · '+action);
  res.status(202).json({commandIds:[command.id]});
 });
}
