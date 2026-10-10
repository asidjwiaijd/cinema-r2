import React,{useState,useEffect,useCallback} from 'react';
import {Users,BarChart3,Search,Download,ShieldAlert,MapPin,LogOut} from 'lucide-react';
const bytes=n=>{n=Number(n)||0;return n>=1e9?(n/1e9).toFixed(2)+' GB':n>=1e6?(n/1e6).toFixed(1)+' MB':n>=1000?(n/1000).toFixed(1)+' KB':n+' B';};
const time=n=>n?new Date(n).toLocaleString('zh-CN',{hour12:false}):'—';
const sum=r=>(r?.r2||0)+(r?.eo||0)+(r?.backup||0)+(r?.unknown||0);
const routes=[['r2','R2'],['eo','EO'],['backup','阿里云'],['unknown','未分类 CDN']];
function usePoll(api,path){
 const [data,setData]=useState(null),[error,setError]=useState('');
 const refresh=useCallback(async()=>{try{setData(await api(path,undefined,'GET'));setError('');}catch(e){setError(e.message);}},[api,path]);
 useEffect(()=>{let busy=false,closed=false;const run=async()=>{if(busy||closed)return;busy=true;await refresh();busy=false;};run();const timer=setInterval(run,2000);return()=>{closed=true;clearInterval(timer);};},[refresh]);
 return {data,error,refresh};
}
function Stats({items}){return <div className="ops-stats">{items.map(([label,value,detail])=><article className="panel" key={label}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>)}</div>;}
export function Players({api,screens}){
 const {data,error,refresh}=usePoll(api,'/players'),[query,setQuery]=useState(''),[filter,setFilter]=useState('online'),[operation,setOperation]=useState(null),[pending,setPending]=useState(false),[message,setMessage]=useState('');
 const {data:traffic}=usePoll(api,'/traffic');
 if(!data)return <section className="panel ops-empty">{error||'正在读取玩家状态…'}</section>;
 const online=data.players.filter(p=>p.online),watching=online.filter(p=>p.watching.length),list=(filter==='banned'?data.bans:data.players.filter(p=>filter==='all'||p.online)).filter(p=>(p.name+' '+p.uuid).toLowerCase().includes(query.toLowerCase()));
 const actionNames={kick:'踢出',ban:'封禁',unban:'解除封禁',teleport:'传送到影院','admin-grant':'授权影院管理员','admin-revoke':'撤销影院管理员','arena-build':'搭建林地影院'};
 const perform=async e=>{
  e.preventDefault();setPending(true);setMessage('');
  try{const body={...Object.fromEntries(new FormData(e.currentTarget)),action:operation.action};const result=operation.action==='arena-build'?await api('/arena/build',{confirm:true}):await api('/players/'+operation.player.uuid+'/action',body);
   const deadline=Date.now()+10000;let completed=false;
   while(Date.now()<deadline){const state=await api('/state',undefined,'GET'),ack=state.commandResults.find(r=>r.id===result.commandIds[0]);if(ack){if(ack.error)throw Error(ack.error);completed=true;break;}await new Promise(r=>setTimeout(r,250));}
   if(!completed)throw Error('等待 MC 回执超时；请刷新玩家状态，避免重复操作');
   setMessage('MC 已执行：'+actionNames[operation.action]+' '+operation.player.name);setOperation(null);await refresh();
  }catch(e){setMessage(e.message);}finally{setPending(false);}
 };
 return <div className="ops-page">
  <Stats items={[["在线玩家",online.length+' / '+(data.server.maxPlayers||'—'),'服务端实际连接数'],['正在观影',watching.length,'近 4 秒有播放回报'],['历史玩家',data.players.length,'最多保存最近 2000 位'],['封禁玩家',data.bans.length,'Minecraft 原生封禁名单']]}/>
  <section className="panel"><div className="panel-title"><h2>林地露天影院 · 50 座</h2><button disabled={!data.connected||pending||data.server.arena?.building} onClick={()=>setOperation({action:'arena-build',player:{name:'主影院周围场地'}})}>{data.server.arena?.built?'重建场景':'搭建场景'}</button></div><p className="ops-note">{data.server.arena?.protected?'保护开启 · 固定晴朗傍晚 · 创造模式 · 禁止爆炸、火蔓延、掉落物及观众建造':'等待影院保护配置'}{data.server.arena?.building?' · 搭建进度 '+data.server.arena.progress+'%':data.server.arena?.built?' · 场景已就绪':''}{data.server.arena?.error&&' · '+data.server.arena.error}</p><p className="ops-note">管理员仅具有建造权限；本次连接授权断线失效。重建会替换主影院周围的场地方块，保留屏幕控制器和节目。</p></section>
  {!data.supported&&<p className="ops-warning">等待支持玩家回报的 MC 服务端连接。</p>}
  {(!data.connected||error)&&<p className="ops-warning">{error||'MC 服务端未连接，以下为最后记录，操作不可用。'}</p>}
  <section className="panel"><div className="panel-title"><h2><Users size={17}/>玩家管理</h2><span>按 UUID 管理 · 离线模式 UUID 不等于正版身份</span></div>
   <div className="ops-toolbar"><div className="ops-tabs">{[['online','在线'],['all','历史'],['banned','封禁']].map(([k,n])=><button className={filter===k?'primary':''} key={k} onClick={()=>setFilter(k)}>{n}</button>)}</div><label className="search"><Search size={16}/><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="搜索玩家名或 UUID"/></label></div>
   <div className="ops-scroll"><table className="ops-table"><thead><tr><th>玩家</th><th>状态 / 延迟</th><th>位置 / 模式</th><th>观影 / 缓冲</th><th>下载量</th><th>最近在线</th><th>操作</th></tr></thead><tbody>{list.map(p=>{const usage=traffic?.players.find(t=>t.uuid===p.uuid);return <tr key={p.uuid}><td><strong>{p.name}</strong>{p.cinemaAdmin&&<small>{p.adminActive?"影院管理员 · 本次连接已授权":"影院管理员 · 等待本次授权"}</small>}<small className="ops-uuid">{p.uuid}</small></td><td>{p.online?'在线 · '+p.ping+' ms':filter==='banned'?'已封禁':'离线'}{p.joins&&<small>累计连接 {p.joins} 次</small>}</td><td>{p.dimension?.replace('minecraft:','')||'—'}<small>{p.x===undefined?'—':`${p.x}, ${p.y}, ${p.z} · ${p.gameMode}`}</small></td><td>{p.watching?.length?p.watching.map(w=><div key={w.screenId}>{w.screenName}<small>{w.error|| (w.buffering?'缓冲中':'播放回报正常')} · 预读 {Math.round(w.cacheSeconds)} 秒</small></div>):p.reason||'无播放回报'}</td><td>{usage?bytes(sum(usage)):'—'}</td><td>{time(p.lastSeen)}</td><td><div className="ops-actions">{filter==='banned'?<button disabled={!data.connected||pending||!!error} onClick={()=>setOperation({player:p,action:'unban'})}>解封</button>:<><button disabled={!p.online||pending||!!error} onClick={()=>setOperation({player:p,action:'teleport'})}><MapPin size={13}/>传送</button><button disabled={!p.online||pending||!!error} onClick={()=>setOperation({player:p,action:'kick'})}><LogOut size={13}/>踢出</button><button disabled={!p.online||pending||!!error||p.adminActive} onClick={()=>setOperation({player:p,action:"admin-grant"})}>{p.cinemaAdmin?"授权本次连接":"设为管理员"}</button>{p.cinemaAdmin&&<button disabled={!data.connected||pending||!!error} onClick={()=>setOperation({player:p,action:"admin-revoke"})}>撤销管理员</button>}<button className="danger" disabled={!data.connected||pending||!!error||p.banned} onClick={()=>setOperation({player:p,action:'ban'})}><ShieldAlert size={13}/>封禁</button></>}</div></td></tr>;})}</tbody></table>{!list.length&&<div className="ops-empty">{filter==='online'?'当前没有在线玩家':filter==='banned'?'封禁名单为空':'没有匹配的玩家'}</div>}</div>
  </section>{message&&<p className="ops-message" role="status">{message}</p>}
  {operation&&<div className="modal-backdrop"><form className="modal" onSubmit={perform}><h2>{actionNames[operation.action]} · {operation.player.name}</h2><p>确认后发送至 Minecraft，等待执行回执。封禁会写入 MC 原生名单。影院管理员可在保护区建造，不获得 OP；离线模式下，每次重新连接必须由网页重新授权，防止冒用玩家名自动获得建造权限。</p>{operation.action==='teleport'?<label className="field">目标影院<select name="screenId" required>{screens.filter(s=>s.online).map(s=><option key={s.id} value={s.id}>{s.name}</option>)}</select><small>传送到屏幕附近的安全落脚点。</small></label>:!['unban','arena-build'].includes(operation.action)&&<label className="field">原因<input name="reason" maxLength={200} defaultValue="影院管理员操作" required/></label>}<div className="ops-actions"><button type="button" disabled={pending} onClick={()=>setOperation(null)}>取消</button><button className="primary" disabled={pending||operation.action==='teleport'&&!screens.some(s=>s.online)}>{pending?'等待 MC 执行…':'确认'+actionNames[operation.action]}</button></div></form></div>}
 </div>;
}
export function Traffic({api}){
 const {data,error}=usePoll(api,'/traffic'),[period,setPeriod]=useState('days');
 if(!data)return <section className="panel ops-empty">{error||'正在读取流量统计…'}</section>;
 const rows=(period==='days'?data.days:data.hours).slice(-30),max=Math.max(1,...rows.map(sum));
 const cdn=data.today.eo+data.today.backup+data.today.unknown,monthCdn=data.thisMonth.eo+data.thisMonth.backup+data.thisMonth.unknown;
 const budget=(n,limit)=>limit?Math.min(100,100*n/(limit*1e9)).toFixed(1)+'% / '+limit+' GB':'未设置预算';
 return <div className="ops-page">{error&&<p className="ops-warning">{error}，当前显示最后一次统计。</p>}
  <Stats items={[["今日 R2",bytes(data.today.r2),'优先线路下载'],['今日 CDN',bytes(cdn),budget(cdn,data.limits.dayLimitGB)],['本月 CDN',bytes(monthCdn),budget(monthCdn,data.limits.monthLimitGB)],['下载速率',bytes(sum(data.rate))+'/s','最近 30 秒上报均值']]}/>
  <p className="ops-note">{data.measurement} 详细统计始于 {time(data.since)}，在线观影不代表每个客户端都已上报。</p>
  <section className="panel"><div className="panel-title"><h2><BarChart3 size={17}/>流量趋势</h2><div className="ops-actions"><button onClick={()=>setPeriod(period==='days'?'hours':'days')}>{period==='days'?'切换小时':'切换按日'}</button><a className="ops-download" href="/api/traffic/export"><Download size={14}/>导出每日 CSV</a></div></div>
   <div className="ops-legend">{routes.map(([k,n])=><span key={k}><i className={'route-'+k}/>{n}</span>)}</div>
   <div className="traffic-chart" aria-label="各线路流量趋势">{rows.map(r=><div className="traffic-column" key={r.date} title={r.date+' UTC\n'+routes.map(([k,n])=>n+': '+bytes(r[k])).join('\n')}><div className="traffic-stack">{routes.map(([k])=><div key={k} className={'route-'+k} style={{height:((r[k]||0)/max*160)+'px'}}/>)}</div><small>{period==='days'?r.date.slice(5):r.date.slice(11)+':00'}</small></div>)}{!rows.length&&<div className="ops-empty">播放后将在这里记录流量趋势</div>}</div>
  </section>
  <section className="panel"><div className="panel-title"><h2>线路用量</h2><span>字节数按客户端累计差值去重</span></div><div className="ops-scroll"><table className="ops-table"><thead><tr><th>线路</th><th>今日</th><th>本月</th><th>详细统计累计</th><th>最近速率</th></tr></thead><tbody>{routes.map(([k,n])=><tr key={k}><td><span className="ops-route"><i className={'route-'+k}/>{n}</span></td><td>{bytes(data.today[k])}</td><td>{bytes(data.thisMonth[k])}</td><td>{bytes(data.totals[k])}</td><td>{bytes(data.rate[k])}/s</td></tr>)}</tbody></table></div></section>
  <section className="panel"><div className="panel-title"><h2>玩家下载排行</h2><span>{data.sessions} 个近期上报会话</span></div><div className="ops-scroll"><table className="ops-table"><thead><tr><th>玩家</th><th>R2</th><th>EO</th><th>阿里云</th><th>总计</th><th>最近上报</th></tr></thead><tbody>{data.players.slice(0,100).map(p=><tr key={p.uuid}><td><strong>{p.name}</strong><small className="ops-uuid">{p.uuid}</small></td><td>{bytes(p.r2)}</td><td>{bytes(p.eo)}</td><td>{bytes(p.backup)}</td><td>{bytes(sum(p))}</td><td>{time(p.lastSeen)}</td></tr>)}</tbody></table>{!data.players.length&&<div className="ops-empty">等待新版 MC 客户端接收玩家标识并上报；匿名和旧版用量仅计入线路总量。</div>}</div></section>
 </div>;
}
