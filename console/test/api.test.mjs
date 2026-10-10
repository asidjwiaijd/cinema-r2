import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, dirname, basename } from "node:path";
import { spawn } from "node:child_process";
const port = 32711,
  base = "http://127.0.0.1:" + port;
let dir, child, token;
async function call(path, body, headers = {}, method = "POST") {
  const r = await fetch(base + path, {
    method,
    headers: { "Content-Type": "application/json", ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, body: await r.json() };
}
before(async () => {
  dir = await mkdtemp(join(tmpdir(), "cinema-api-"));
  child = spawn(process.execPath, ["server/index.mjs"], {
    env: { ...process.env, PORT: String(port), CINEMA_DATA: dir },
    stdio: "ignore",
    windowsHide: true,
  });
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(base + "/api/state")).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  token = JSON.parse(
    await readFile(join(dir, "settings.json"), "utf8"),
  ).bridgeToken;
});
after(async () => {
  child?.kill();
  await new Promise((r) => child?.once("exit", r));
  assert.equal(dirname(dir), tmpdir());
  assert.ok(basename(dir).startsWith("cinema-api-"));
  await rm(dir, { recursive: true, force: true });
});
test("bridge rejects unauthorized commands; public state omits persistent credentials", async () => {
  assert.equal((await call("/api/bridge/poll", { screens: [] })).status, 401);
  const state = await call("/api/state", undefined, {}, "GET");
  assert.equal(state.status, 200);
  assert.equal(state.body.settings.bridgeToken, undefined);
  assert.equal(state.body.settings.r2.secretAccessKey, undefined);
  assert.equal(state.body.settings.live.streamKey, undefined);
  assert.equal(
    (
      await call(
        "/api/settings",
        { publicBase: "https://example.com" },
        { Origin: "https://evil.example" },
      )
    ).status,
    403,
  );
  assert.equal(
    (await call("/api/control", { screenIds: ["x"], action: "play" })).status,
    400,
  );
});
test("media import rejects embedded credentials and unsafe transport", async () => {
  for (const url of [
    "http://remote.example/media/a.mp4",
    "https://name:secret@example.com/a.mp4",
    "https://example.com/a.mp4#fragment",
  ])
    assert.equal((await call("/api/resources/import", { url })).status, 400);
  assert.equal(
    (
      await call("/api/resources/import", {
        name: "Test",
        url: "https://media.example/a/index.m3u8",
        duration: 10,
      })
    ).status,
    200,
  );
});
test("acknowledgement removes only executed commands; create IDs cannot overwrite command IDs", async () => {
  const headers = { Authorization: "Bearer " + token };
  const screen = {
    id: "minecraft:overworld@0,100,0",
    online: true,
    dimension: "minecraft:overworld",
    x: 0,
    y: 100,
    z: 0,
    width: 12,
  };
  assert.equal(
    (await call("/api/bridge/poll", { screens: [screen] }, headers)).status,
    200,
  );
  assert.equal(
    (
      await call(
        "/api/bridge/poll",
        { instanceId: "second-server", screens: [screen] },
        headers,
      )
    ).status,
    409,
  );
  const created = await call("/api/screens", {
    x: 2,
    y: 100,
    z: 0,
    name: "Second",
  });
  assert.equal(created.status, 202);
  let reply = await call("/api/bridge/poll", { screens: [screen] }, headers);
  const command = reply.body.commands[0];
  assert.equal(command.action, "create");
  assert.notEqual(command.id, created.body.id);
  assert.equal(command.screenId, created.body.id);
  assert.equal(
    (await call("/api/control", { screenIds: [screen.id], action: "play" }))
      .status,
    202,
  );
  reply = await call(
    "/api/bridge/poll",
    { screens: [screen], acks: [{ id: command.id }] },
    headers,
  );
  assert.equal(reply.body.commands.length, 1);
  assert.equal(reply.body.commands[0].action, "play");
  assert.equal(
    (
      await call("/api/control", {
        screenIds: [screen.id],
        action: "seek",
        position: "NaN",
      })
    ).status,
    400,
  );
});

test("quality and cache policy reach the MC bridge and external variants are rejected", async () => {
  const headers={Authorization:"Bearer "+token};
  const screen={id:"minecraft:overworld@0,100,0",width:12,quality:720};
  await call("/api/bridge/poll",{screens:[screen]},headers);
  assert.equal((await call("/api/screens/"+encodeURIComponent(screen.id),{policy:{quality:1080,cacheSeconds:1200,cacheStartSeconds:20}}, {}, "PATCH")).status,200);
  const reply=await call("/api/bridge/poll",{screens:[screen]},headers);
  const command=reply.body.commands.find(x=>x.action==="configure");
  assert.equal(command.policy.quality,1080);assert.equal(command.policy.cacheSeconds,1200);assert.equal(command.policy.cacheStartSeconds,20);
  const imported=await call("/api/resources/import",{url:"https://media.example/a.mp4"});
  assert.equal((await call("/api/resources/"+imported.body.id+"/variant",{quality:1080})).status,400);
});

 test("take is atomic and acknowledged errors and real playback reports reach the console",async()=>{
   const headers={Authorization:"Bearer "+token};
   const screen={id:"minecraft:overworld@0,100,0",url:"https://media.example/a/index.m3u8",playback:[{viewer:"Test",positionMs:1000,cacheSeconds:120,buffering:true,receivedAt:Date.now(),error:""}]};
   await call("/api/bridge/poll",{screens:[screen]},headers);
   const current=await call("/api/state",undefined,{},"GET");
   const resource=current.body.resources.find(r=>r.name==="Test");
   const accepted=await call("/api/control",{screenIds:[screen.id],action:"take",resourceId:resource.id});
   assert.equal(accepted.status,202);assert.equal(accepted.body.commandIds.length,1);
   const poll=await call("/api/bridge/poll",{screens:[screen]},headers);
   const take=poll.body.commands.find(c=>c.id===accepted.body.commandIds[0]);
   assert.equal(take.action,"take");assert.equal(take.source.url,resource.url);
   await call("/api/bridge/poll",{screens:[screen],acks:[{id:take.id,error:"test-only error"}]},headers);
   const state=await call("/api/state",undefined,{},"GET");
   assert.equal(state.body.commandResults.find(c=>c.id===take.id).error,"test-only error");
   assert.equal(state.body.pendingCommands.some(c=>c.id===take.id),false);
   assert.equal(state.body.screens.find(s=>s.id===screen.id).playback[0].buffering,true);
 });
