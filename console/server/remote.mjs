import { Readable } from "node:stream";
import {join} from "node:path";
import {access} from "node:fs/promises";
import { state, settings, root } from "./model.mjs";
import { live } from "./live.mjs";

export const agentBase = process.env.CINEMA_LIVE_AGENT_URL;
export const remote = !!process.env.CINEMA_PUBLIC_ORIGIN;
export async function agentCall(path, body) {
  const result = await fetch(new URL("/api" + path, agentBase), {
    method:body === undefined ? "GET" : "POST",
    headers:body === undefined ? {} : {"Content-Type":"application/json"},
    body:body === undefined ? undefined : JSON.stringify(body),
    signal:AbortSignal.timeout(4000),
  });
  const data = await result.json();
  if (!result.ok) throw Error(data.error || "本机直播接收端未连接");
  return data;
}
export function watchAgent() {
  if (!agentBase) return;
  let busy = false;
  const poll = async () => {
    if (busy) return;
    busy = true;
    try { Object.assign(live, (await agentCall("/state")).live, {agent:true}); }
    catch { Object.assign(live, {ready:false,listening:false,publishing:false,error:"本机直播接收端未连接",agent:true}); }
    finally { busy = false; }
  };
  poll();
  const timer = setInterval(poll, 1000); timer.unref();
}
function previewTarget(resource) {
  if (!remote || !resource?.url) return null;
  const source = new URL(resource.url);
  if (resource.storage === "direct" && resource.id === settings.live.id && agentBase) {
    return new URL("/live/" + settings.live.id + "/index.m3u8", agentBase);
  }
  if(resource.storage==="direct"&&resource.id===settings.live.id&&!agentBase)return source;
  if (source.origin === new URL(settings.publicBase).origin && source.protocol === "https:") return source;
  return null;
}
export function previewURL(resource) {
  const source = previewTarget(resource);
  return source ? "/api/preview/" + resource.id + "/" + source.pathname.split("/").at(-1) : resource?.url;
}
async function relay(req, res, url, cache="no-store") {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 20000);
  res.on("close", () => abort.abort());
  try {
    const upstream = await fetch(url, {method:req.method === "HEAD" ? "HEAD" : "GET",redirect:"error",headers:req.get("range") ? {Range:req.get("range")} : {},signal:abort.signal});
    clearTimeout(timer);
    res.status(upstream.status);
    for (const header of ["content-type","content-length","content-range","accept-ranges","etag"]) {
      if (upstream.headers.has(header)) res.set(header, upstream.headers.get(header));
    }
    res.set("Cache-Control", upstream.ok ? cache : "no-store");
    if (req.method === "HEAD" || !upstream.body) return res.end();
    const stream = Readable.fromWeb(upstream.body);
    stream.on("error", () => res.destroy());
    stream.pipe(res);
  } catch {
    if (!res.headersSent && !res.destroyed) res.status(502).json({error:"媒体预览暂时无法连接，请重试"});
  } finally { clearTimeout(timer); }
}
export function installPreview(app) {
  app.get("/api/preview/:id/:file", async (req, res) => {
    const resource = state.resources.find(r => r.id === req.params.id && !r.archived);
    const source = previewTarget(resource);
    if (!source || !/^[a-zA-Z0-9_-]+\.(m3u8|ts|mp4|jpg)$/.test(req.params.file)) return res.status(404).end();
    if(resource.storage==="direct"&&resource.id===settings.live.id&&!agentBase)return res.sendFile(req.params.file,{root:join(root,"live",resource.id),dotfiles:"deny"});
    // Only siblings of a registered resource, never arbitrary proxy destinations.
    const target = new URL(req.params.file, source);
    if (target.href === source.href) target.search = source.search;
    if(resource.kind==="vod"&&resource.storage!=="external"){
      const dir=join(root,"resources",resource.id,resource.format==="hls"?"hls":"");
      try{await access(join(dir,req.params.file));return res.sendFile(req.params.file,{root:dir,dotfiles:"deny"});}catch{}
    }
    await relay(req, res, target);
  });
  if (agentBase) app.get("/live/:id/:file", async (req, res) => {
    if (req.params.id !== settings.live.id || !/^[a-zA-Z0-9_-]+\.(m3u8|ts)$/.test(req.params.file)) return res.status(404).end();
    if(!settings.delivery?.enabled)return res.status(503).set("Cache-Control","no-store").end();
    await relay(req, res, new URL("/live/" + req.params.id + "/" + req.params.file, agentBase),req.params.file.endsWith(".m3u8")?"public, max-age=1, must-revalidate":"public, max-age=86400, immutable");
  });
}
