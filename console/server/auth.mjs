import { readFileSync } from "node:fs";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";

export function installAuth(app, path = process.env.CINEMA_AUTH_FILE) {
  if (!path) return false;
  const config = JSON.parse(readFileSync(path, "utf8"));
  const expected = Buffer.from(config.hash, "hex");
  if (expected.length !== 64 || !config.salt) throw Error("管理登录配置无效");
  const sessions = new Map(), failures = new Map();
  const cookieName = "cinema_session";
  const session = (req) => {
    const value = req.get("cookie")?.split(";").map(v => v.trim()).find(v => v.startsWith(cookieName + "="))?.slice(cookieName.length + 1);
    const until = sessions.get(value);
    if (!until || until < Date.now()) { sessions.delete(value); return false; }
    return true;
  };
  const prune = setInterval(() => {
    for (const [id, until] of sessions) if (until < Date.now()) sessions.delete(id);
    for (const [ip, f] of failures) if (f.until < Date.now()) failures.delete(ip);
  }, 60000);
  prune.unref();
  app.post("/api/auth/login", (req, res) => {
    const f = failures.get(req.ip);
    if (f && f.until > Date.now() && f.count >= 5) return res.status(429).json({error:"尝试次数过多，请 5 分钟后重试"});
    const password = String(req.body.password || "");
    const actual = scryptSync(password.slice(0, 256), config.salt, 64);
    if (password.length > 256 || !timingSafeEqual(actual, expected)) {
      failures.set(req.ip, {count: (f?.until > Date.now() ? f.count : 0) + 1, until:Date.now()+300000});
      return res.status(401).json({error:"管理密码不正确"});
    }
    failures.delete(req.ip);
    const id = randomBytes(32).toString("hex");
    sessions.set(id, Date.now() + 12*3600000);
    res.cookie(cookieName, id, {httpOnly:true, sameSite:"strict", secure:req.secure, maxAge:12*3600000, path:"/"});
    res.json({ok:true});
  });
  app.use("/api", (req, res, next) => {
    // Minecraft authenticates independently with the existing bridge token.
    if (["/bridge/poll", "/delivery/policy", "/delivery/report"].includes(req.path)) return next();
    if (!session(req)) return res.status(401).json({error:"请登录管理台"});
    next();
  });
  app.post("/api/auth/logout", (req, res) => {
    const value = req.get("cookie")?.split(";").map(v => v.trim()).find(v => v.startsWith(cookieName + "="))?.slice(cookieName.length + 1);
    sessions.delete(value);
    res.clearCookie(cookieName, {httpOnly:true,sameSite:"strict",path:"/"});
    res.json({ok:true});
  });
  return true;
}
