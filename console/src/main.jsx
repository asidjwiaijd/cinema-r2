import React, { useState, useEffect, useRef } from "react";
import { createRoot } from "react-dom/client";

import {
  Clapperboard,
  Monitor,
  Radio,
  FolderOpen,
  Settings,
  Play,
  Pause,
  Square,
  Upload,
  Plus,
  Search,
  ChevronDown,
  Volume2,
  Maximize,
  Trash2,
  Link2,
  GripVertical,
  Copy,
  X,
  SlidersHorizontal,
  RefreshCw,
  LoaderCircle,
  CheckCircle2,
  CircleAlert,
  ArrowRight,
  Server,
  Eye,
  ShieldCheck,
  HardDrive,
  Users,
  BarChart3,
  Check,
} from "lucide-react";
import "./style.css";
import {Players,Traffic} from "./Operations.jsx";
const nav = [
  ["control", "播控台", Clapperboard],
  ["resources", "资源库", FolderOpen],
  ["screens", "屏幕管理", Monitor],
  ["live", "直播中心", Radio],
  ["players", "玩家管理", Users],
  ["traffic", "流量统计", BarChart3],
  ["settings", "系统设置", Settings],
];
const clock = (n) => {
  n = Math.max(0, Math.floor(n || 0));
  return (
    String(Math.floor(n / 3600)).padStart(2, "0") +
    ":" +
    String(Math.floor(n / 60) % 60).padStart(2, "0") +
    ":" +
    String(n % 60).padStart(2, "0")
  );
};
const bytes = (n) =>
  n > 1e9
    ? (n / 1e9).toFixed(2) + " GB"
    : n > 1e6
      ? (n / 1e6).toFixed(1) + " MB"
      : (n / 1000).toFixed(0) + " KB";
async function api(path, body, method = "POST") {
  if(method === "PATCH"){path += "/update";method="POST";}
  else if(method === "DELETE"){path += "/remove";method="POST";}
  const r = await fetch("/api" + path, {
    method,
    headers:
      body instanceof FormData ? {} : { "Content-Type": "application/json" },
    body:
      body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  const data = await r.json();
  if (!r.ok) throw Error(data.error || "请求失败");
  return data;
}
function Badge({ children, tone = "" }) {
  return <span className={"badge " + tone}>{children}</span>;
}
function Empty({ icon: Icon = FolderOpen, title, detail, action }) {
  return (
    <div className="empty">
      <Icon size={28} />
      <strong>{title}</strong>
      <p>{detail}</p>
      {action}
    </div>
  );
}
function MediaPreview({ url, live, playing, position = 0, independent = false, seekRevision = 0, targetPosition = position }) {
  const ref = useRef(),lastCorrection=useRef(0);
  const appliedSeek=useRef(seekRevision);
  const [failure,setFailure]=useState(""),[loading,setLoading]=useState(true);
  useEffect(() => {
    const video = ref.current;
    if (!url) return;
    setFailure("");setLoading(true);
    let retries=0;
    let hls,
      cancelled = false;
    const load = async () => {
      if (new URL(url,window.location.origin).pathname.endsWith(".m3u8")) {
        const { default: Hls } = await import("hls.js");
        if (cancelled) return;
        if (Hls.isSupported()) {
          hls = new Hls({ maxBufferLength: 120, maxMaxBufferLength: 240, liveSyncDurationCount: 3, startPosition:live?-1:position });
          hls.loadSource(url);
          hls.attachMedia(video);
          hls.on(Hls.Events.ERROR,(_,details)=>{if(!details.fatal)return;if(retries++<3){if(details.type===Hls.ErrorTypes.MEDIA_ERROR)hls.recoverMediaError();else hls.startLoad();}else setFailure("浏览器预览加载失败，请检查媒体地址或网络后重新打开预览");});
          hls.on(Hls.Events.MANIFEST_PARSED, () => {
            if (playing && !independent) video.play().catch(() => {});
          });
        } else video.src = url;
      } else video.src = url;
    };
    load().catch((e) => setFailure(e.message));
    return () => {
      cancelled = true;
      hls?.destroy();
      video.removeAttribute("src");
      video.load();
    };
  }, [url]);
  useEffect(() => {
    const v = ref.current;
    if (!url) return;
    if (independent) return;
    if (
      !live &&
      Math.abs(v.currentTime - position) > 2 &&
      Number.isFinite(position) &&
      v.readyState >= 3 && !v.seeking && Date.now()-lastCorrection.current > 8000
    ){
      const cached=Array.from({length:v.buffered.length},(_,i)=>[v.buffered.start(i),v.buffered.end(i)]).some(([start,end])=>position>=start&&position<end);
      if(cached || Date.now()-lastCorrection.current>20000){v.currentTime=position;lastCorrection.current=Date.now();}
    }
    if (playing) v.play().catch(() => {});
    else v.pause();
  }, [playing, position, url, live, independent]);
  useEffect(()=>{
    const video=ref.current;
    if(appliedSeek.current!==seekRevision){
      appliedSeek.current=seekRevision;
      if(!independent&&!live&&video.readyState>0&&Number.isFinite(targetPosition)){
        video.currentTime=Math.max(0,targetPosition);lastCorrection.current=Date.now();
      }
    }
  },[seekRevision,url,live,independent,targetPosition]);
  return (
    <>
    <video
      ref={ref}
      muted
      playsInline
      controls={independent}
      preload="metadata"
      onLoadedMetadata={() => {
        const video = ref.current;
        if (independent) return;
        if (!live && Number.isFinite(position)){video.currentTime = Math.max(0, position);lastCorrection.current=Date.now();}
        if (playing) video.play().catch(() => {});
        else video.pause();
      }}
      onWaiting={()=>setLoading(true)}
      onLoadedData={()=>setLoading(false)}
      onCanPlay={()=>setLoading(false)}
      onSeeked={()=>setLoading(ref.current.readyState<2)}
      onError={() => setFailure("浏览器无法播放此媒体，请检查网络、编码和跨域设置")}
      aria-label={independent ? "素材独立预览" : "当前节目浏览器参考画面"}
    />
    {loading && !failure && <span className="preview-loading"><LoaderCircle size={14}/>正在加载浏览器预览…</span>}
    {failure && <div role="alert" className="preview-failure">{failure}</div>}
    </>
  );
}
function App() {
  const [authRequired, setAuthRequired] = useState(false), [loginError, setLoginError] = useState("");
  const [data, setData] = useState(null),
    [page, setPage] = useState("control"),
    [query, setQuery] = useState(""),
    [selected, setSelected] = useState(""),
    [screenIds, setScreenIds] = useState([]),
    [touched, setTouched] = useState(false),
    [queue, setQueue] = useState([]),
    [modal, setModal] = useState(null),
    [notice, setNotice] = useState(null),
    [busy, setBusy] = useState(false),
    [seek, setSeek] = useState(null),
    [key, setKey] = useState(""),
    [uploading, setUploading] = useState(null);
  const uploadRef = useRef();
  const refresh = async () => {
    try {
      const r = await fetch("/api/state");
      if (r.status === 401) {setAuthRequired(true);setData(null);return;}
      if (!r.ok) throw Error();
      setAuthRequired(false);
      setData(await r.json());
    } catch {
      setNotice({ error: true, text: "管理服务未连接，请运行启动脚本" });
    }
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 400);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (
      data &&
      !touched &&
      !screenIds.length &&
      data.screens.some((s) => s.online)
    )
      setScreenIds([data.screens.find((s) => s.online).id]);
  }, [data, touched]);
  useEffect(() => {
    if (notice) {
      const t = setTimeout(() => setNotice(null), 5000);
      return () => clearTimeout(t);
    }
  }, [notice]);
  const act = async (fn, message) => {
    setBusy(true);
    try {
      await fn();
      if (message) setNotice({ text: message });
      await refresh();
      return true;
    } catch (e) {
      setNotice({ error: true, text: e.message });
      return false;
    } finally {
      setBusy(false);
    }
  };
  if (authRequired) return <div className="boot"><Clapperboard size={44}/><h1>Cinema 播控中心</h1><p>输入管理密码以控制游戏屏幕</p><form className="login-form" onSubmit={async e => {
    e.preventDefault();const password=new FormData(e.currentTarget).get("password");setBusy(true);
    try {await api("/auth/login",{password});setLoginError("");await refresh();}
    catch(error){setLoginError(error.message);}finally{setBusy(false);}
  }}><label className="field">管理密码<input type="password" name="password" autoComplete="current-password" required maxLength={256}/></label>{loginError && <p role="alert">{loginError}</p>}<button className="primary full" disabled={busy} type="submit">{busy?"正在登录…":"登录管理台"}</button></form></div>;
  if (!data)
    return (
      <div className="boot">
        <Clapperboard size={44} />
        <h1>Cinema</h1>
        <p>正在连接播控中心…</p>
        {notice && <p>{notice.text}</p>}
      </div>
    );
  const assets = data.resources.filter((r) =>
    r.name.toLowerCase().includes(query.toLowerCase()),
  );
  const screen = data.screens.find((s) => screenIds.includes(s.id));
  const staged = data.resources.find((r) => r.id === selected);
  const current = data.resources.find((r) => r.url === screen?.url);
  const preview = current || (screen?.url ? {url:screen.url,name:"外部节目",kind:screen.live?"live":"vod"} : null);
  const duration = preview?.duration || screen?.durationMs / 1000 || 0;
  const position =
    screen && preview && screen.url === preview.url
      ? (screen.positionMs + (screen.online && screen.playing ? Math.max(0,Date.now()-screen.sampleTime) : 0)) / 1000
      : 0;
  const playing =
    !!screen?.online &&
    !!preview &&
    screen.url === preview.url &&
    screen.playing;
  const reports = (screen?.playback || []).filter(r => Date.now()-r.receivedAt < 4000);
  const reference = reports[0];
  const actualPosition = reference ? (reference.positionMs + (playing && !reference.buffering ? Math.max(0,Date.now()-reference.receivedAt) : 0))/1000 : null;
  const drift = actualPosition === null ? null : position-actualPosition;
  const transportDisabled = busy || !data.bridge.online || !screenIds.length || !screen?.online || !screen?.url;
  const sendControl = async (action,extra={}) => {
    const {commandIds=[]}=await api("/control",{screenIds,action,...extra});
    const deadline=Date.now()+8000;
    while(commandIds.length&&Date.now()<deadline){
      const state=await api("/state",undefined,"GET");
      setData(state);
      const completed=commandIds.map(id=>state.commandResults?.find(c=>c.id===id));
      if(completed.every(Boolean)){
        const errors=completed.filter(c=>c.error);
        if(errors.length)throw Error(errors.map(c=>c.error).join("；"));
        return;
      }
      await new Promise(resolve=>setTimeout(resolve,150));
    }
    if(commandIds.length)throw Error("等待 MC 回执超时，请检查屏幕连接状态；不要重复点击");
  };
  const control=(action,extra={})=>act(()=>sendControl(action,extra),"MC 已执行指令；游戏端状态见下方回报");
  const startSelected=()=>control("take",{resourceId:staged?.id});
  const toggleScreen = (id) => {
    setTouched(true);
    setScreenIds((ids) =>
      ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id],
    );
  };
  const uploadFiles = async (files, format = "hls", quality = 1080, imageSeconds = 15) => {
    if (uploading) {
      setNotice({ error: true, text: "已有上传任务，请等待完成" });
      return;
    }
    for (const file of files) {
      const form = new FormData();
      form.append("file", file);
      form.append("format", format);
      form.append("quality", quality);
      form.append("imageSeconds",imageSeconds);
      setUploading({ name: file.name, progress: 0 });
      try {
        await new Promise((resolve, reject) => {
          const xhr = new XMLHttpRequest();
          xhr.open("POST", "/api/resources/upload");
          xhr.upload.onprogress = (e) => {
            if (e.lengthComputable)
              setUploading({
                name: file.name,
                progress: Math.round((e.loaded / e.total) * 100),
              });
          };
          xhr.onload = () => {
            try {
              const result = JSON.parse(xhr.responseText);
              xhr.status < 300
                ? resolve(result)
                : reject(Error(result.error || "上传失败"));
            } catch {
              reject(Error("上传响应无效"));
            }
          };
          xhr.onerror = () => reject(Error("上传连接中断，请重新上传"));
          xhr.send(form);
        });
        setNotice({ text: "上传完成，正在处理资源" });
        await refresh();
      } catch (e) {
        setNotice({ error: true, text: e.message });
      } finally {
        setUploading(null);
      }
    }
  };
  const formSubmit = async (e, type) => {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.currentTarget));
    let ok = false;
    if (type === "import")
      ok = await act(
        () => api("/resources/import", values),
        "资源已加入媒体库",
      );
    if (type === "screen")
      ok = await act(() => api("/screens", values), "屏幕创建指令已发送");
    if (type === "policy") {
      const s = data.screens.find((x) => x.id === modal.id);
      ok = await act(
        () =>
          api(
            "/screens/" + encodeURIComponent(s.id),
            {
              name: values.name,
              width: Number(values.width),
              facing: values.facing,
              policy: {
                onEnd: values.onEnd,
                pauseWhenEmpty: values.pauseWhenEmpty === "on",
                liveLatencySeconds: Number(values.latency),
                quality: Number(values.quality),
                cacheSeconds: Number(values.cacheSeconds),
                cacheStartSeconds: Number(values.cacheStartSeconds),
              },
            },
            "PATCH",
          ),
        "屏幕设置已保存",
      );
    }
    if (ok) setModal(null);
  };
  const card = (r) => (
    <div
      key={r.id}
      className={"asset " + (selected === r.id ? "selected" : "")}
      role="button" tabIndex={0} title={r.name} aria-pressed={selected===r.id}
      onKeyDown={e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();setSelected(r.id);}}}
      draggable={r.status === "ready"}
      onDragStart={(e) => e.dataTransfer.setData("text/plain", r.id)}
      onClick={() => setSelected(r.id)}
    >
      <div
        className={"thumb " + (r.kind === "live" ? "live-thumb" : "")}
        style={
          r.kind === "live"
            ? {}
            : { backgroundImage: "url(/posters/" + r.id + "/poster.jpg)" }
        }
      >
        {r.kind === "live" ? <Radio size={25} /> : <Play size={20} />}
        <span>{r.kind === "live" ? "LIVE" : clock(r.duration)}</span>
      </div>
      <div className="asset-copy">
        <strong>{r.name}</strong>
        <small>
          {r.format?.toUpperCase()} ·{" "}
          {r.storage === "direct"
            ? "CDN 直播"
            : r.storage === "r2"
              ? "R2 优先"
              : r.storage === "local"
                ? "本机资源"
                : "外部链接"}
        </small>
      </div>
      <Badge
        tone={
          r.status === "error"
            ? "red"
            : r.status === "processing"
              ? "yellow"
              : ""
        }
      >
        {r.status === "ready"
          ? "就绪"
          : r.status === "processing"
            ? "处理中"
            : "失败"}
      </Badge>
    </div>
  );
  const Header = (
    <>
      <div className="page-title">
        <span className="eyebrow">
          WORKSPACE / {nav.find((n) => n[0] === page)[1]}
        </span>
        <h1>{nav.find((n) => n[0] === page)[1]}</h1>
        <p>
          {
            {
              control: "把每一块屏幕，变成同一个现场。",
              resources: "整理影片、分片资源与直播信号。",
              screens: "连接 Minecraft 世界中的播放终端。",
              live: "从 OBS 推流，到世界里的每一块屏幕。",
              players: "管理在线玩家、观影状态和影院接入。",
              traffic: "查看各线路下载量、趋势和玩家用量。",
              settings: "配置媒体存储与运行环境。",
            }[page]
          }
        </p>
      </div>
      <div className="page-tools">
        {page === "resources" ? (
          <>
            <button onClick={() => setModal({ type: "import" })}>
              <Link2 size={16} />
              导入链接
            </button>
            <button
              className="primary"
              onClick={() => setModal({ type: "upload" })}
            >
              <Upload size={16} />
              上传资源
            </button>
          </>
        ) : page === "screens" ? (
          <button
            className="primary"
            onClick={() => setModal({ type: "screen" })}
          >
            <Plus size={16} />
            新建屏幕
          </button>
        ) : (
          <button onClick={refresh}>
            <RefreshCw size={15} />
            刷新状态
          </button>
        )}
      </div>
    </>
  );
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-icon">
            <Clapperboard size={22} />
          </span>
          <div>
            Cinema<span>CONTROL CENTER</span>
          </div>
          <Badge>02</Badge>
        </div>
        <div className="workspace">
          <span className="workspace-mark">MC</span>
          <div>
            <strong>Dream Cinema</strong>
            <small>{data.deployment ? "192.168.8.244 · 影院工作空间" : "本机工作空间"}</small>
          </div>
          <ChevronDown size={15} />
        </div>
        <span className="nav-caption">工作空间</span>
        <nav>
          {nav.map(([id, label, Icon]) => (
            <button
              key={id}
              className={page === id ? "active" : ""}
              onClick={() => setPage(id)}
            >
              <Icon size={18} />
              {label}
              {id === "live" && data.live.publishing && (
                <i className="dot red" />
              )}
            </button>
          ))}
        </nav>
        <div className="side-bottom">
          <div className="connection">
            <i className={"dot " + (data.bridge.online ? "green" : "")} />
            <div>
              <strong>
                {data.bridge.online ? "Minecraft 已连接" : "Minecraft 未连接"}
              </strong>
              <small>
                {data.bridge.online
                  ? data.bridge.serverName
                  : "启动服务端后自动连接"}
              </small>
            </div>
          </div>
          <div className="avatar">C</div>
          <div className="operator">
            <strong>影院管理员</strong>
            <small>控制台 · {data.deployment ? "192.168.8.244" : "localhost"}</small>
          </div>
        </div>
      </aside>
      <main>
        <header className="topbar">
          <div>
            <span className="breadcrumb">Dream Cinema</span>
            <span className="slash">/</span>
            <span>{nav.find((n) => n[0] === page)[1]}</span>
          </div>
          <div className="top-status">
            <span>
              <i
                className={
                  "dot " + (data.settings.r2.configured ? "green" : "yellow")
                }
              />
              {data.settings.r2.configured ? "R2 优先" : "R2 待配置"}
            </span>
            <span className="separator" />
            <span>
              <Monitor size={14} />
              {data.screens.filter((s) => s.online).length} 屏幕在线
            </span>
            <span className="version">v0.3.0</span>
            {data.authEnabled && <button onClick={async()=>{await api("/auth/logout",{});setData(null);setAuthRequired(true);}}>退出登录</button>}
          </div>
        </header>
        <div className="content">
          <section className="page-head">{Header}</section>
          {uploading && (
            <div className="upload-status">
              <Upload size={16} />
              <span>上传 {uploading.name}</span>
              <progress value={uploading.progress} max="100" />
              <strong>{uploading.progress}%</strong>
            </div>
          )}
          {page === "control" && (
            <>
              <div className="control-grid">
                <section className="panel library-panel">
                  <div className="panel-title">
                    <h2>
                      <FolderOpen size={16} />
                      媒体资源
                    </h2>
                    <button
                      className="icon-button"
                      aria-label="添加资源"
                      onClick={() => setModal({ type: "upload" })}
                    >
                      <Plus size={17} />
                    </button>
                  </div>
                  <label className="search">
                    <Search size={15} />
                    <input
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      placeholder="搜索资源…"
                    />
                  </label>
                  <div className="asset-list">
                    {assets.length ? (
                      assets.map(card)
                    ) : (
                      <Empty
                        title="还没有资源"
                        detail="上传影片或接收 OBS 信号"
                      />
                    )}
                  </div>
                  <div className="staged-resource">
                    <small>下一条素材 · 点击资源仅选择</small>
                    <strong title={staged?.name}>{staged?.name || "请先选择素材"}</strong>
                    <button disabled={!staged?.url} onClick={()=>setModal({type:"preview",id:staged.id})}><Eye size={14}/>独立预览素材</button>
                    <button disabled={!staged || staged.status!=="ready" || busy || !screenIds.length || !data.bridge.online} onClick={()=>control("load",{resourceId:staged.id})}>载入待播（暂停）</button>
                    <button className="primary" disabled={!staged || staged.status!=="ready" || busy || !screenIds.length || !data.bridge.online} onClick={startSelected}><Play size={14}/>立即播放到 {screenIds.length} 块屏幕</button>
                  </div>
                  <button
                    className="library-import"
                    onClick={() => setModal({ type: "import" })}
                  >
                    <Link2 size={15} />
                    导入媒体链接
                  </button>
                </section>
                <section className="program panel">
                  <div className="panel-title">
                    <h2>
                      <span className="program-tag">当前节目</span>
                      {preview?.name || "屏幕尚未载入节目"}
                    </h2>
                    <Badge tone={playing ? "green" : ""}>
                      {!screen?.online ? "未连接" : reports.some(r=>r.buffering) ? "游戏端缓冲中" : playing
                          ? "正在播放"
                          : screen?.url ? "已暂停" : "未载入"}
                    </Badge>
                  </div>
                  <div className="control-context"><strong>{screen?.name || "请勾选输出屏幕"}</strong><span>按钮控制已勾选的 {screenIds.length} 块屏幕 · 进度参考此屏幕</span></div>
                  <div
                    className="monitor-stage"
                    onDragOver={(e) => e.preventDefault()}
                    onDrop={(e) => {
                      e.preventDefault();
                      setSelected(e.dataTransfer.getData("text/plain"));
                    }}
                  >
                    {preview?.url ? (
                      <MediaPreview
                        url={preview.previewUrl || preview.url}
                        live={preview.kind === "live"}
                        playing={!!playing}
                        position={actualPosition ?? position}
                        targetPosition={position}
                        seekRevision={screen?.seekRevision || 0}
                      />
                    ) : (
                      <div className="monitor-empty">
                        <Clapperboard size={40} />
                        <strong>准备下一场放映</strong>
                        <p>选择左侧素材，点击“立即播放到屏幕”</p>
                      </div>
                    )}
                    <div className="monitor-corners">
                      <i />
                      <i />
                      <i />
                      <i />
                    </div>
                    <div className="monitor-top">
                      <span>
                        <i className={"dot " + (playing ? "red" : "")} />
                        {preview?.kind === "live" ? "直播参考画面" : "浏览器参考画面 · 非游戏画面回传"}
                      </span>
                      <span>16:9 · {screen?.width || 12} 格</span>
                    </div>
                    <div className="monitor-bottom">
                      <span>{screen?.name || "未选择输出屏幕"}</span>
                      <span className="mono">{clock(position)}</span>
                    </div>
                  </div>
                  <div className="timeline">
                    <span className="mono">{clock(position)}</span>
                    <input
                      aria-label="播放进度"
                      type="range"
                      min="0"
                      max={duration || 1}
                      step="0.1"
                      disabled={transportDisabled || !duration || preview?.kind === "live"}
                      value={seek ?? Math.min(position, duration || 1)}
                      onChange={(e) => setSeek(Number(e.target.value))}
                      onPointerUp={(e) => {
                        control("seek", {
                          position: Number(e.currentTarget.value),
                        });
                        setSeek(null);
                      }}
                      onKeyUp={(e) => {
                        if (
                          ["ArrowLeft", "ArrowRight", "Home", "End"].includes(
                            e.key,
                          )
                        ) {
                          control("seek", {
                            position: Number(e.currentTarget.value),
                          });
                          setSeek(null);
                        }
                      }}
                    />
                    <span className="mono">
                      {preview?.kind === "live" ? "LIVE" : clock(duration)}
                    </span>
                  </div>
                  <div className="playback-feedback" aria-live="polite">
                    <div><span>目标进度</span><strong>{clock(position)}</strong><span>游戏实际</span><strong>{actualPosition === null ? "等待客户端回报" : clock(actualPosition)}</strong><span>{screen?.live ? "直播进度独立于点播时间轴" : drift === null ? "观众进入后显示同步偏差" : Math.abs(drift)<0.3 ? "估算偏差 < 0.3 秒" : `${drift>0?"落后":"超前"} ${Math.abs(drift).toFixed(1)} 秒`}</span></div>
                    <div><span>{reference?.viewer ? `参考观众：${reference.viewer}` : `${screen?.audience || 0} 位观众`}</span><span>{reports.length} 个客户端回报</span><span>{reports.filter(r=>r.buffering).length ? `${reports.filter(r=>r.buffering).length} 个正在缓冲` : reports.length ? "游戏端正常播放 / 暂停" : "暂无播放状态"}</span><span>{reference ? `预读 ${Math.round(reference.cacheSeconds)} 秒` : ""}</span></div>
                    {reports.some(r=>r.error) && <p role="alert">{reports.find(r=>r.error).error}</p>}
                  </div>
                  <div className="transport">
                    <div>
                      <button disabled={transportDisabled} onClick={()=>control(playing?"pause":"play")} aria-label={playing?"暂停当前节目":"恢复当前节目"}>{playing?<Pause size={17}/>:<Play size={17}/>} {playing?"暂停节目":"播放 / 恢复"}</button>
                      <button disabled={transportDisabled || screen?.live} onClick={()=>control("seek",{position:0})}><RefreshCw size={15}/>回到片头</button>
                      <button disabled={transportDisabled} onClick={()=>control("stop")}><Square size={15}/>停止并清空</button>
                    </div>
                    <span className="command-status">{busy ? "正在等待 MC 执行回执…" : "控制当前节目"}</span>
                  </div>
                  <div className="signal-bar">
                    <span>
                      <ShieldCheck size={13} />
                      大容量预读 · 原位播控
                    </span>
                    <span>游戏终端仅播放</span>
                    <span className="mono">{screen?.quality === 1080 ? "1920 × 1080" : "1280 × 720"} · libmpv</span>
                  </div>
                </section>
                <section className="panel output-panel">
                  <div className="panel-title">
                    <h2>
                      <Monitor size={16} />
                      输出屏幕
                    </h2>
                    <Badge>{data.screens.length}</Badge>
                  </div>
                  <div className="output-list">
                    {data.screens.length ? (
                      data.screens.map((s) => (
                        <div
                          key={s.id}
                          className={
                            "output " +
                            (screenIds.includes(s.id) ? "armed" : "")
                          }
                        >
                          <div className="output-header">
                            <label>
                              <input
                                type="checkbox"
                                checked={screenIds.includes(s.id)}
                                onChange={() => toggleScreen(s.id)}
                              />
                              <strong>{s.name}</strong>
                            </label>
                            <i className={"dot " + (s.online ? "green" : "")} />
                          </div>
                          <div className="output-screen">
                            <Monitor size={26} />
                            <span>
                              {!s.online ? "离线" : !s.url ? "未载入" : s.playback?.some(r=>r.buffering) ? "缓冲中" : s.live
                                ? (s.playing?"直播中":"直播暂停")
                                : s.playing
                                  ? "ON AIR"
                                  : "已暂停"}
                            </span>
                          </div>
                          <small>
                            {s.dimension?.replace("minecraft:", "")} · {s.x},{" "}
                            {s.y}, {s.z}
                          </small>
                          <div className="output-footer">
                            <span>
                              <Eye size={12} />
                              {s.audience || 0} 观众
                            </span>
                            <button
                              className="icon-button"
                              aria-label={"设置 " + s.name}
                              onClick={() =>
                                setModal({ type: "policy", id: s.id })
                              }
                            >
                              <SlidersHorizontal size={14} />
                            </button>
                          </div>
                        </div>
                      ))
                    ) : (
                      <Empty
                        icon={Monitor}
                        title="等待屏幕连接"
                        detail="启动 MC 服务端，加载屏幕区块"
                        action={
                          <button onClick={() => setPage("screens")}>
                            管理屏幕
                          </button>
                        }
                      />
                    )}
                  </div>
                  <div className="output-hint">
                    <i className="dot green" />
                    勾选屏幕，批量同步播放
                  </div>
                </section>
              </div>
              <section className="panel playlist-panel">
                <div className="panel-title">
                  <h2>
                    <GripVertical size={16} />
                    队列草稿<Badge>{queue.length}</Badge>
                  </h2>
                  <div className="row">
                    <button
                      disabled={!selected}
                      onClick={() => setQueue((q) => [...q, selected])}
                    >
                      <Plus size={14} />
                      加入选中资源
                    </button>
                    <button disabled={busy || !screenIds.length || !data.bridge.online} onClick={() => control("playlist", { queue })}>
                      应用到所选屏幕
                      <ArrowRight size={14} />
                    </button>
                  </div>
                </div>
                <div className="queue-strip">
                  {queue.length ? (
                    queue.map((id, i) => {
                      const r = data.resources.find((r) => r.id === id);
                      return (
                        <div className="queue-item" key={i}>
                          <span className="queue-num">
                            {String(i + 1).padStart(2, "0")}
                          </span>
                          <div>
                            <strong>{r?.name || "资源已移除"}</strong>
                            <small>{clock(r?.duration)}</small>
                          </div>
                          <button
                            className="icon-button"
                            aria-label={"移除队列 " + (i + 1)}
                            onClick={() =>
                              setQueue((q) => q.filter((_, n) => n !== i))
                            }
                          >
                            <X size={14} />
                          </button>
                        </div>
                      );
                    })
                  ) : (
                    <p>选中素材后加入队列，在屏幕策略中选择「顺序轮播」。</p>
                  )}
                </div>
              </section>
              <div className="activity">
                <span>
                  <i className="dot" />
                  运行记录
                </span>
                <p>{data.events[0]?.message || "控制台就绪，等待第一条指令"}</p>
                <span className="mono">
                  {data.events[0]
                    ? new Date(data.events[0].time).toLocaleTimeString(
                        "zh-CN",
                        { hour12: false },
                      )
                    : "—"}
                </span>
              </div>
            </>
          )}
          {page === "resources" && (
            <>
              <div className="stats-row">
                <div>
                  <FolderOpen size={18} />
                  <strong>{data.resources.length}</strong>
                  <span>媒体资源</span>
                </div>
                <div>
                  <HardDrive size={18} />
                  <strong>
                    {bytes(
                      data.resources.reduce((n, r) => n + (r.size || 0), 0),
                    )}
                  </strong>
                  <span>源文件总量</span>
                </div>
                <div>
                  <Radio size={18} />
                  <strong>
                    {data.resources.filter((r) => r.kind === "live").length}
                  </strong>
                  <span>直播信号</span>
                </div>
                <div>
                  <ShieldCheck size={18} />
                  <strong>{data.settings.r2.storageLimitGB} GB</strong>
                  <span>R2 存储预算</span>
                </div>
              </div>
              <section className="panel">
                <div className="panel-title">
                  <h2>全部资源</h2>
                  <button disabled={busy} onClick={()=>act(()=>api("/resources/sync",{}),"已提交双副本校验与缺片补齐")}>同步 R2 / 源站</button>
                  <label className="search compact">
                    <Search size={15} />
                    <input
                      placeholder="搜索资源名称"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                    />
                  </label>
                </div>
                <div className="resource-grid">
                  {assets.map((r) => (
                    <article className="resource-card" key={r.id}>
                      <div
                        className="resource-cover"
                        style={{
                          backgroundImage:
                            "url(/posters/" + r.id + "/poster.jpg)",
                        }}
                      >
                        {r.kind === "live" ? (
                          <Radio size={34} />
                        ) : (
                          <Play size={30} />
                        )}
                        <Badge>{r.mediaType==='audio'?'音频':r.mediaType==='image'?'图片':r.format?.toUpperCase()}</Badge>
                      </div>
                      <div className="resource-info">
                        <h3>{r.name}</h3>
                        <div className="muted">
                          {r.kind === "live" ? "实时信号" : clock(r.duration)} ·{" "}
                          {r.size ? bytes(r.size) : "外部资源"}
                        </div>
                        <div className="resource-actions">
                          <Badge
                            tone={
                              r.status === "ready"
                                ? "green"
                                : r.status === "error"
                                  ? "red"
                                  : "yellow"
                            }
                          >
                            {r.status === "ready"
                              ? "就绪"
                              : r.status === "processing"
                                ? "处理中"
                                : "处理失败"}
                          </Badge>
                          <span>
                            {r.storage === "direct"
                              ? "CDN 直播"
                              : r.storage === "r2"
                              ? "R2 优先"
                              : r.storage === "local"
                                ? "本机"
                                : "外部"}
                          </span>
                          <button
                            className="icon-button"
                            aria-label={"编辑 " + r.name}
                            onClick={() =>
                              setModal({ type: "resource", id: r.id })
                            }
                          >
                            <SlidersHorizontal size={15} />
                          </button>
                        </div>
                      </div>
                    </article>
                  ))}
                  {!assets.length && (
                    <Empty
                      title="创建你的媒体库"
                      detail="影片会在本机处理，并在配置完成后发布至 R2"
                      action={
                        <button
                          className="primary"
                          onClick={() => setModal({ type: "upload" })}
                        >
                          <Upload size={15} />
                          上传第一个资源
                        </button>
                      }
                    />
                  )}
                </div>
              </section>
              {data.jobs.length > 0 && (
                <section className="panel jobs">
                  <div className="panel-title">
                    <h2>处理与上传任务</h2>
                  </div>
                  {data.jobs.map((j) => (
                    <div className="job" key={j.id}>
                      {j.status === "running" ? (
                        <LoaderCircle className="spin" size={16} />
                      ) : j.status === "done" ? (
                        <CheckCircle2 size={16} />
                      ) : (
                        <CircleAlert size={16} />
                      )}
                      <div>
                        <strong>{j.name}</strong>
                        <small>
                          {j.error ||
                            {
                              running: "正在处理，请保持管理服务运行",
                              done: "已完成",
                              error: "处理失败",
                            }[j.status]}
                        </small>
                      </div>
                      <progress value={j.progress} max="100" />
                      <Badge tone={j.status === "error" ? "red" : ""}>
                        {j.progress}%
                      </Badge>
                    </div>
                  ))}
                </section>
              )}
            </>
          )}
          {page === "players" && <Players api={api} screens={data.screens}/>}
          {page === "traffic" && <Traffic api={api}/>}
          {page === "screens" && (
            <section className="panel">
              <div className="panel-title">
                <h2>世界中的播放终端</h2>
                <Badge tone={data.bridge.online ? "green" : "yellow"}>
                  {data.bridge.online ? "服务端在线" : "等待服务端"}
                </Badge>
              </div>
              <div className="screen-grid">
                {data.screens.map((s) => (
                  <article className="screen-card" key={s.id}>
                    <div className="screen-diagram">
                      <div className="screen-plane">
                        <Monitor size={34} />
                      </div>
                      <span>
                        {s.width} × {((s.width * 9) / 16).toFixed(1)} 格
                      </span>
                    </div>
                    <div>
                      <h3>
                        {s.name}
                        <i className={"dot " + (s.online ? "green" : "")} />
                      </h3>
                      <p>
                        {s.dimension} · ({s.x}, {s.y}, {s.z})
                      </p>
                      <div className="tags">
                        <Badge>{s.facing}</Badge>
                        <Badge>
                          {s.policy?.onEnd === "loop"
                            ? "单片循环"
                            : s.policy?.onEnd === "next"
                              ? "顺序轮播"
                              : "结束保持"}
                        </Badge>
                        <Badge>{s.audience || 0} 人</Badge>
                      </div>
                      <button
                        onClick={() => setModal({ type: "policy", id: s.id })}
                      >
                        <SlidersHorizontal size={15} />
                        设置屏幕与播放策略
                      </button>
                    </div>
                  </article>
                ))}
                {!data.screens.length && (
                  <Empty
                    icon={Monitor}
                    title="还没有连接的屏幕"
                    detail="游戏内放置控制器会自动登记，也可以从这里创建"
                    action={
                      <button onClick={() => setModal({ type: "screen" })}>
                        <Plus size={15} />
                        新建屏幕
                      </button>
                    }
                  />
                )}
              </div>
            </section>
          )}
          {page === "live" && (
            <div className="live-layout">
              <section className="panel">
                <div className="panel-title">
                  <h2>
                    <Radio size={16} />
                    OBS 直播信号
                  </h2>
                  <Badge tone={data.live.publishing ? "red" : ""}>
                    {data.live.publishing
                      ? "ON AIR"
                      : data.live.listening
                        ? "等待推流"
                        : "未启动"}
                  </Badge>
                </div>
                <div className="live-preview">
                  {data.live.ready ? (
                    <MediaPreview url={data.live.previewUrl || data.live.url} live playing />
                  ) : (
                    <div>
                      <Radio size={40} />
                      <h3>
                        {data.live.listening
                          ? "等待 OBS 连接"
                          : "开启下一场直播"}
                      </h3>
                      <p>RTMP 接收 · HLS 分发 · Minecraft 播放</p>
                    </div>
                  )}
                </div>
                <div className="live-controls">
                  <div>
                    <i
                      className={"dot " + (data.live.publishing ? "red" : "")}
                    />
                    {data.live.error ||
                      (data.live.publishing
                        ? "正在生成并分发直播分片"
                        : "还没有推流信号")}
                  </div>
                  <button
                    className={data.live.listening ? "" : "primary"}
                    onClick={() =>
                      act(
                        () =>
                          api(
                            data.live.listening ? "/live/stop" : "/live/start",
                            {},
                          ),
                        data.live.listening
                          ? "直播接收端已停止"
                          : "直播接收端已启动",
                      )
                    }
                  >
                    {data.live.listening ? (
                      <Square size={15} />
                    ) : (
                      <Radio size={15} />
                    )}{" "}
                    {data.live.listening ? "停止接收" : "启动直播接收"}
                  </button>
                </div>
              </section>
              <section className="panel obs-panel">
                <div className="panel-title">
                  <h2>连接 OBS Studio</h2>
                  <Badge>RTMP</Badge>
                </div>
                <div className="obs-steps">
                  <div>
                    <span>01</span>
                    <p>
                      在 OBS 中打开「设置 → 直播」
                      <small>服务选择「自定义」</small>
                    </p>
                  </div>
                  <div>
                    <span>02</span>
                    <p>填入服务器和推流密钥</p>
                  </div>
                </div>
                <label className="field">
                  推流服务器
                  <div className="copy-field">
                    <input readOnly value={data.live.rtmpUrl} />
                    <button
                      aria-label="复制推流服务器"
                      onClick={() =>
                        act(
                          () =>
                            navigator.clipboard.writeText(data.live.rtmpUrl),
                          "已复制推流服务器",
                        )
                      }
                    >
                      <Copy size={14} />
                    </button>
                  </div>
                </label>
                <label className="field">
                  推流密钥
                  <div className="copy-field">
                    <input
                      readOnly
                      type={key ? "text" : "password"}
                      value={key || "••••••••••••••••••••"}
                    />
                    <button
                      onClick={() =>
                        act(async () =>
                          setKey(
                            (await api("/live/key", undefined, "GET")).key,
                          ),
                        )
                      }
                    >
                      显示
                    </button>
                  </div>
                </label>
                <p className="obs-note">
                  输出建议：H.264 / AAC，关键帧间隔 2 秒。点击
                  OBS「开始直播」。
                </p>
                <div className="obs-step-last">
                  <span>03</span>
                  <p>
                    返回播控台，选择「OBS 直播」
                    <small>发送至屏幕，然后开始播放</small>
                  </p>
                </div>
                <div className="signal-route">
                  <span>OBS</span>
                  <ArrowRight size={13} />
                  <span>{data.live.ingestHost || "直播服务器"} · HLS</span>
                  <ArrowRight size={13} />
                  <span>Minecraft</span>
                </div>
                <p className="muted">
                  接收与转码运行于 {data.live.ingestHost || "直播服务器"}，直播不上传 R2。
                  {data.live.url?.startsWith("https:")
                    ? "观众通过公网 HTTPS 获取 HLS；OBS 推流入口需要另行配置公网 TCP 映射。"
                    : "跨电脑观看需配置可访问的 HTTPS 直播分发地址。"}
                </p>
              </section>
            </div>
          )}
          {page === "settings" && (
            <form
              className="settings-form"
              onSubmit={(e) => {
                e.preventDefault();
                const f = Object.fromEntries(new FormData(e.currentTarget));
                act(
                  () =>
                    api("/settings", {
                      publicBase: f.publicBase,
                      ffmpeg: f.ffmpeg,
                      ffprobe: f.ffprobe,
                      liveBase: f.liveBase,
                      liveQuality: Number(f.liveQuality),
                      delivery:{cdnBase:f.cdnBase,backupBase:f.backupBase,backupEnabled:f.backupEnabled==="on",enabled:f.cdnEnabled==="on",vodFallback:f.vodFallback==="on",dayLimitGB:Number(f.dayLimitGB),monthLimitGB:Number(f.monthLimitGB),sessionLimitMB:Number(f.sessionLimitMB),vodStartupCdnSeconds:Number(f.vodStartupCdnSeconds),vodEmergencyCdnSeconds:Number(f.vodEmergencyCdnSeconds),vodPrefetchSeconds:Number(f.vodPrefetchSeconds),vodEoDeadlineSeconds:Number(f.vodEoDeadlineSeconds),vodBackupDeadlineSeconds:Number(f.vodBackupDeadlineSeconds)},
                      r2: {
                        accountId: f.accountId,
                        bucket: f.bucket,
                        accessKeyId: f.accessKeyId,
                        secretAccessKey: f.secretAccessKey,
                        storageLimitGB: Number(f.budget),
                      },
                    }),
                  "设置已保存",
                );
              }}
            >
              <section className="panel">
                <div className="panel-title">
                  <h2>Cloudflare R2</h2>
                  <Badge
                    tone={data.settings.r2.configured ? "green" : "yellow"}
                  >
                    {data.settings.r2.configured ? "凭据已配置" : "待配置"}
                  </Badge>
                </div>
                <div className="form-grid">
                  <label className="field">
                    公共媒体域名
                    <input
                      name="publicBase"
                      type="url"
                      required
                      defaultValue={data.settings.publicBase}
                    />
                    <small>点播优先读取此域名，失败时按策略回退到国内 CDN</small>
                  </label>
                  <label className="field">
                    存储桶
                    <input
                      name="bucket"
                      defaultValue={data.settings.r2.bucket}
                    />
                  </label>
                  <label className="field">
                    Cloudflare Account ID
                    <input
                      name="accountId"
                      defaultValue={data.settings.r2.accountId}
                    />
                  </label>
                  <label className="field">
                    存储预算（GB）
                    <input
                      name="budget"
                      type="number"
                      min="1"
                      max="1000"
                      defaultValue={data.settings.r2.storageLimitGB}
                    />
                  </label>
                  <label className="field">
                    S3 Access Key ID
                    <input
                      name="accessKeyId"
                      autoComplete="off"
                      placeholder={
                        data.settings.r2.configured
                          ? "已保存，留空保持"
                          : "仅限此桶的对象读写 ID"
                      }
                    />
                  </label>
                  <label className="field">
                    S3 Secret Access Key
                    <input
                      name="secretAccessKey"
                      type="password"
                      autoComplete="new-password"
                      placeholder={
                        data.settings.r2.configured
                          ? "已保存，留空保持"
                          : "密钥仅保存在后台服务端"
                      }
                    />
                  </label>
                </div>
                <p className="settings-note">
                  <ShieldCheck size={15} />
                  密钥不会发送给 Minecraft 玩家。R2 直连不受 Workers
                  每日请求上限保护。
                </p>
              </section>
              <section className="panel">
                <div className="panel-title">
                  <h2>6 路 R2 预取与分片限时兜底</h2>
                </div>
                <div className="form-grid">
                  <label className="field">国内 CDN 公共地址<input name="cdnBase" type="url" defaultValue={data.delivery?.cdnBase||"https://live.mc.acgzjd.cn"}/></label>
                  <label className="field">最后备用 CDN 地址<input name="backupBase" type="url" defaultValue={data.delivery?.backupBase||"https://live.al.mc.acgzjd.cn"}/></label>
                  <label className="field">单日 CDN 上限（GB，0 不设限）<input name="dayLimitGB" type="number" min="0" step="0.1" defaultValue={data.delivery?.dayLimitGB||0}/></label>
                  <label className="field">单月 CDN 上限（GB，0 不设限）<input name="monthLimitGB" type="number" min="0" step="0.1" defaultValue={data.delivery?.monthLimitGB||0}/></label>
                  <label className="field">单次播放 CDN 上限（MiB，0 不设限）<input name="sessionLimitMB" type="number" min="0" defaultValue={data.delivery?.sessionLimitMB||0}/></label>
                  <label className="field">未来 TS 预取范围（秒）<input name="vodPrefetchSeconds" type="number" min="60" max="3600" defaultValue={data.delivery?.vodPrefetchSeconds??600}/></label>
                  <label className="field">EO 补片窗口（距离播放不足此秒数）<input name="vodEoDeadlineSeconds" type="number" min="10" max="300" defaultValue={data.delivery?.vodEoDeadlineSeconds??40}/></label>
                  <label className="field">阿里云兜底窗口（距离播放不足此秒数）<input name="vodBackupDeadlineSeconds" type="number" min="5" max="300" defaultValue={data.delivery?.vodBackupDeadlineSeconds??20}/></label>
                </div>
                <details><summary>MP4 与旧客户端兼容策略</summary><div className="form-grid">
                  <label className="field">CDN 起播内容时长（秒，0 关闭）<input name="vodStartupCdnSeconds" type="number" min="0" max="300" defaultValue={data.delivery?.vodStartupCdnSeconds??60}/></label>
                  <label className="field">剩余缓存兜底窗口（秒）<input name="vodEmergencyCdnSeconds" type="number" min="30" max="300" defaultValue={data.delivery?.vodEmergencyCdnSeconds??30}/></label>
                </div></details>
                <label className="check-field"><input type="checkbox" name="cdnEnabled" defaultChecked={data.delivery?.enabled??true}/>启用 CDN 媒体（含直播）</label>
                <label className="check-field"><input type="checkbox" name="vodFallback" defaultChecked={data.delivery?.vodFallback??true}/>允许点播缺片使用 CDN 补齐</label>
                <label className="check-field"><input type="checkbox" name="backupEnabled" defaultChecked={data.delivery?.backupEnabled??true}/>EO 不可用时允许最后备用 CDN</label>
                <p className="muted">0.2.8 客户端对未来范围内的 TS 使用 6 路 R2 并行下载，完整片段暂存磁盘，再按顺序交给播放器。仅缺失片段距离播放不足 EO 窗口时允许 EO，不足阿里云窗口时才允许付费兜底；EO 提前失败也不会让远处片段提前走阿里云。起播和未缓存跳转处的片段已经到期，可补片。屏幕预读上限仍单独设置。</p>
                <p className="muted">直播仅走 EO → 阿里云。所有 CDN 补片受开关及合计预算约束。今日两家 CDN 合计 {bytes(data.delivery?.usage?.dayBytes||0)} · 本月 {bytes(data.delivery?.usage?.monthBytes||0)}。包含 CDN 缓存命中，不含网页和未上报请求，非云服务账单或硬封顶。</p>
              </section>
              <section className="panel">
                <div className="panel-title">
                  <h2>媒体处理环境</h2>
                </div>
                <div className="form-grid">
                  <label className="field">
                    FFmpeg 可执行文件
                    <input name="ffmpeg" defaultValue={data.settings.ffmpeg} />
                  </label>
                  <label className="field">
                    FFprobe 可执行文件
                    <input
                      name="ffprobe"
                      defaultValue={data.settings.ffprobe}
                    />
                  </label>
                  <label className="field">
                    OBS 转换清晰度
                    <select name="liveQuality" defaultValue={data.settings.live.quality || 1080}>
                      <option value="720">720p</option><option value="1080">1080p</option>
                    </select>
                    <small>下一次 OBS 开始推流时生效。</small>
                  </label>
                  <label className="field">
                    直播分发地址
                    <input
                      name="liveBase"
                      type="url"
                      defaultValue={
                        data.settings.live.publicBase || "http://127.0.0.1:3210"
                      }
                    />
                    <small>
                      直播只从此 HTTPS 域名读取 HLS，不上传 R2。
                    </small>
                  </label>
                </div>
                <div className="settings-note">
                  <Server size={15} />
                  {data.deployment ? `MC 通过公网 HTTPS 连接控制台 · OBS 接收与转码：${data.live.ingestHost}` : "MC 接入地址：http://127.0.0.1:3210 · 本机控制台"}
                </div>
              </section>
              <div className="form-actions">
                <button className="primary" disabled={busy} type="submit">
                  <Check size={16} />
                  保存配置
                </button>
              </div>
            </form>
          )}
          <footer>
            <span>Cinema Control Center</span>
            <span>资源 · 播放 · 现场</span>
            <span className="mono">
              {data.bridge.online ? "BRIDGE CONNECTED" : "BRIDGE STANDBY"}
            </span>
          </footer>
        </div>
      </main>
      {notice && (
        <div role="status" className={"toast " + (notice.error ? "error" : "")}>
          {notice.error ? (
            <CircleAlert size={17} />
          ) : (
            <CheckCircle2 size={17} />
          )}{" "}
          {notice.text}
        </div>
      )}
      {modal && (
        <div className="modal-backdrop" onClick={() => setModal(null)}>
          <section
            role="dialog"
            aria-modal="true"
            className="modal"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="modal-title">
              <h2>
                {
                  {
                    upload: "上传媒体资源",
                    import: "导入媒体链接",
                    screen: "新建游戏屏幕",
                    policy: "屏幕与播放策略",
                    resource: "资源管理",
                    preview:"独立素材预览 · 不影响游戏",
                  }[modal.type]
                }
              </h2>
              <button
                className="icon-button"
                aria-label="关闭对话框"
                onClick={() => setModal(null)}
              >
                <X size={20} />
              </button>
            </div>
            {modal.type === "preview" && (()=>{const resource=data.resources.find(r=>r.id===modal.id);return <div className="resource-preview"><p>{resource?.name}</p><div className="preview-video"><MediaPreview url={resource?.previewUrl || resource?.url} live={resource?.kind==="live"} independent playing={false}/></div><p className="muted">这里的播放、暂停、跳转只用于素材检查。预览默认静音，可在视频控件开启声音。</p></div>;})()}
            {modal.type === "upload" && (
              <form
                onSubmit={async (e) => {
                  e.preventDefault();
                  const form = e.currentTarget;
                  const files = form.elements.file.files;
                  const format = form.elements.format.value;
                  const quality = Number(form.elements.quality.value);
                  const imageSeconds=Number(form.elements.imageSeconds.value);
                  setModal(null);
                  await uploadFiles([...files], format, quality, imageSeconds);
                }}
              >
                <label className="upload-drop">
                  <Upload size={32} />
                  <strong>选择视频、音频或图片</strong>
                  <span>视频 · MP3 / WAV / FLAC / OGG / M4A / AAC · PNG / JPG / WebP</span>
                  <input
                    name="file"
                    type="file"
                    accept=".mp4,.mkv,.mov,.webm,.mp3,.wav,.flac,.ogg,.m4a,.aac,.png,.jpg,.jpeg,.webp"
                    required
                  />
                </label>
                <label className="field">图片展示时长（秒）<input name="imageSeconds" type="number" min="1" max="3600" step="1" defaultValue="15"/><small>仅图片生效；结束保持可持续显示，队列轮播可制作幻灯片。音频配静态音乐画面，声音由游戏播放器输出。</small></label>
                <label className="field">
                  HLS 转码清晰度
                  <select name="quality" defaultValue="1080">
                    <option value="720">720p</option>
                    <option value="1080">1080p</option>
                  </select>
                </label>
                <label className="field">
                  播放格式
                  <select name="format">
                    <option value="hls">HLS 分片 · 12 秒 / 片</option>
                    <option value="mp4">MP4 · 兼容编码 / 快速起播</option>
                  </select>
                </label>
                <p className="muted">
                  {data.settings.r2.configured
                    ? "处理完成后自动分片上传至 R2"
                    : "尚未配置 R2。先保存为本机资源，之后可发布到 R2。"}
                </p>
                <button className="primary full" type="submit">
                  上传并处理
                  <ArrowRight size={15} />
                </button>
              </form>
            )}
            {modal.type === "import" && (
              <form onSubmit={(e) => formSubmit(e, "import")}>
                <label className="field">
                  资源名称
                  <input name="name" required placeholder="例如：开场影片" />
                </label>
                <label className="field">
                  媒体地址
                  <input
                    name="url"
                    type="url"
                    required
                    placeholder="https://video.example.com/media/film/index.m3u8"
                  />
                </label>
                <div className="form-grid">
                  <label className="field">
                    类型
                    <select name="kind">
                      <option value="vod">点播影片</option>
                      <option value="live">HLS 直播</option>
                    </select>
                  </label>
                  <label className="field">
                    时长（秒）
                    <input
                      name="duration"
                      type="number"
                      min="0"
                      defaultValue="0"
                    />
                  </label>
                </div>
                <p className="muted">
                  未知时长填 0；自动结束策略需要准确时长。
                </p>
                <button className="primary full" type="submit">
                  加入媒体库
                </button>
              </form>
            )}
            {modal.type === "screen" && (
              <form onSubmit={(e) => formSubmit(e, "screen")}>
                <label className="field">
                  屏幕名称
                  <input name="name" required placeholder="主影院屏幕" />
                </label>
                <label className="field">
                  维度
                  <select name="dimension">
                    <option value="minecraft:overworld">主世界</option>
                    <option value="minecraft:the_nether">下界</option>
                    <option value="minecraft:the_end">末地</option>
                  </select>
                </label>
                <div className="coords">
                  {["x", "y", "z"].map((v, i) => (
                    <label className="field" key={v}>
                      {v.toUpperCase()}
                      <input
                        name={v}
                        type="number"
                        defaultValue={i === 1 ? 100 : 0}
                        required
                      />
                    </label>
                  ))}
                </div>
                <div className="form-grid">
                  <label className="field">
                    屏幕宽度（格）
                    <input
                      name="width"
                      type="number"
                      min="4"
                      max="32"
                      defaultValue="12"
                    />
                  </label>
                  <label className="field">
                    面向
                    <select name="facing">
                      <option value="south">南</option>
                      <option value="north">北</option>
                      <option value="east">东</option>
                      <option value="west">西</option>
                    </select>
                  </label>
                </div>
                <p className="muted">
                  在指定空位置放置控制器，不覆盖已有方块。
                </p>
                <button className="primary full" disabled={busy} type="submit">
                  在游戏中创建屏幕
                </button>
              </form>
            )}
            {modal.type === "policy" &&
              (() => {
                const s = data.screens.find((s) => s.id === modal.id);
                return (
                  <form onSubmit={(e) => formSubmit(e, "policy")}>
                    <label className="field">
                      屏幕名称
                      <input name="name" defaultValue={s.name} />
                    </label>
                    <div className="form-grid">
                      <label className="field">
                        宽度（格）
                        <input
                          name="width"
                          type="number"
                          min="4"
                          max="32"
                          defaultValue={s.width}
                        />
                      </label>
                      <label className="field">
                        面向
                        <select name="facing" defaultValue={s.facing}>
                          <option value="south">南</option>
                          <option value="north">北</option>
                          <option value="east">东</option>
                          <option value="west">西</option>
                        </select>
                      </label>
                      <label className="field">
                        游戏播放清晰度
                        <select name="quality" defaultValue={s.quality || s.policy?.quality || 720}>
                          <option value="720">720p · 1280×720</option>
                          <option value="1080">1080p · 1920×1080</option>
                        </select>
                      </label>
                      <label className="field">
                        点播预读上限（秒）
                        <input name="cacheSeconds" type="number" min="30" max="3600" defaultValue={s.cacheSeconds || s.policy?.cacheSeconds || 1200} />
                      </label>
                      <label className="field">
                        点播起播缓冲（秒）
                        <input name="cacheStartSeconds" type="number" min="1" max="120" defaultValue={s.cacheStartSeconds || s.policy?.cacheStartSeconds || 20} />
                      </label>
                      <label className="field">
                        点播结束策略
                        <select
                          name="onEnd"
                          defaultValue={s.policy?.onEnd || "hold"}
                        >
                          <option value="hold">结束后保持画面</option>
                          <option value="loop">单片循环</option>
                          <option value="next">顺序轮播队列</option>
                        </select>
                      </label>
                      <label className="field">
                        直播目标延迟（秒）
                        <input
                          name="latency"
                          type="number"
                          min="4"
                          max="60"
                          defaultValue={s.policy?.liveLatencySeconds || 12}
                        />
                      </label>
                    </div>
                    <label className="check-field">
                      <input
                        name="pauseWhenEmpty"
                        type="checkbox"
                        defaultChecked={s.policy?.pauseWhenEmpty}
                      />
                      无人时暂停，有观众进入后恢复
                    </label>
                    <p className="muted">
                      清晰度切换会重新缓冲，视频源分辨率决定真实细节。服务器重启后保持暂停。直播延迟受分片和缓冲影响。
                    </p>
                    <button className="primary full" type="submit">
                      保存并应用
                    </button>
                    <button
                      type="button"
                      className="danger full"
                      onClick={() =>
                        act(
                          async () => {
                            await api(
                              "/screens/" + encodeURIComponent(s.id),
                              undefined,
                              "DELETE",
                            );
                            setModal(null);
                          },
                          s.online ? "屏幕移除指令已发送" : "已取消登记",
                        )
                      }
                    >
                      {s.online ? "移除游戏屏幕" : "取消离线登记"}
                    </button>
                  </form>
                );
              })()}
            {modal.type === "resource" &&
              (() => {
                const r = data.resources.find((r) => r.id === modal.id);
                return (
                  <>
                    <form
                      onSubmit={(e) => {
                        e.preventDefault();
                        const name = new FormData(e.currentTarget).get("name");
                        act(
                          () => api("/resources/" + r.id, { name }, "PATCH"),
                          "资源名称已保存",
                        );
                      }}
                    >
                      <label className="field">
                        资源名称
                        <input name="name" required defaultValue={r.name} />
                      </label>
                      <button type="submit">保存名称</button>
                    </form>
                    <p className="muted">
                      {r.error || r.url || "正在处理资源…"}
                    </p>
                    {r.replicas && <p className="muted">本地副本：{r.replicas.local === "ready" ? "已就绪" : r.replicas.local || "待同步"} · R2 副本：{r.replicas.r2 === "ready" ? "已就绪" : r.replicas.r2 || "待同步"}{r.replicas.verifiedAt && <> · 校验于 {new Date(r.replicas.verifiedAt).toLocaleString()}</>}</p>}
                    {r.replicas?.syncError && <p role="alert">同步失败：{r.replicas.syncError}</p>}
                    {r.kind === "vod" && r.storage !== "external" && r.status === "ready" && (
                      <div className="form-actions">
                        {[720,1080].map(quality => <button key={quality} disabled={busy} onClick={() => act(
                          () => api("/resources/" + r.id + "/variant", {quality}), "已提交 " + quality + "p 副本转码任务")}>生成 {quality}p 副本</button>)}
                        <small>使用保留的原片，不改变正在播放的资源；新副本计入 R2 存储预算。</small>
                      </div>
                    )}
                    <div className="form-actions">
                      {r.storage === "local" && r.kind !== "live" && (
                        <button
                          className="primary"
                          disabled={!data.settings.r2.configured}
                          onClick={() =>
                            act(
                              () => api("/resources/" + r.id + "/publish", {}),
                              "已提交 R2 发布任务",
                            )
                          }
                        >
                          发布到 R2
                        </button>
                      )}
                      <button
                        onClick={() => {
                          setSelected(r.id);
                          setPage("control");
                          setModal(null);
                        }}
                      >
                        在播控台打开
                      </button>
                      <button
                        className="danger"
                        onClick={() =>
                          act(async () => {
                            await api(
                              "/resources/" + r.id,
                              { archived: true },
                              "PATCH",
                            );
                            setModal(null);
                          }, "已从资源库归档，源文件仍保留")
                        }
                      >
                        归档资源
                      </button>
                    </div>
                  </>
                );
              })()}
          </section>
        </div>
      )}
    </div>
  );
}
createRoot(document.getElementById("root")).render(<App />);
