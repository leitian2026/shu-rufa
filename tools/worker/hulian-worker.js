// worker.js —— 互传的 Cloudflare 中转（转发通知；小内容直接放通知里；大文件走网盘，或者经本 Worker 存取 R2：绑定变量名 BUCKET）
// 通知回执：新版 App 连接时带 X-Ack: 1，Worker 对它的“新消息”通知先存表、带序号发出，
// 收到 App 回的 {op:"ack", n:序号} 才删；没回执的会在它下次连上时补发。旧版 App 不带这个头，行为和以前完全一样。
//
// ======================================================================================================
//  多个 App 共用这个 Worker 的规则（v2 起；v3 加了第 9 条）。以后加新 App 的人请先读完，并且不要改变已有命名空间的行为
// ======================================================================================================
//  1. 命名空间：每个 App 连接 /ws 和访问 /r2 时，用请求头 X-App 声明自己的命名空间（小写字母或数字，最长 16 位，例如 clip）。
//     不带 X-App 的都算“老 App”这一组（命名空间是空字符串），互传目前就是这一组，行为和 v1 完全一样。
//  2. 默认完全隔离：在线名单（presence）、新消息（notify）、回执（ack）、离线暂存、暂存额度、心跳，全部只在同一个命名空间内。
//     一个 App 的设备 ID、连接、消息、额度，不会影响另一个 App；两个 App 可以用同样的设备 ID，互不顶替。
//  3. 想收别的 App 的通知，要接收方自己同意：连接时用 X-Accept 列出愿意接收的命名空间（逗号分隔，最多 8 个，不能列自己，
//     也不能列老 App 那一组）。没列的，别的 App 发不进来；发送方不能硬塞。
//  4. 跨 App 发通知：发送方在 notify 里加 toApp（对方的命名空间）。只实时送达：对方不在线或没同意就丢掉
//     （发送方收到 sent 里的 online:false）；不暂存、不回执、不占对方的暂存额度。收到的通知多一个 app 字段（发送方的命名空间），
//     接收方自己决定要不要处理。跨 App 通知不改变任何一方的在线名单。
//  5. 在线名单永远只含同一命名空间的设备，不跨 App 共享在线状态。
//  6. R2：带 X-App 的 App，只能读写、列出 “<命名空间>/” 开头的对象（别的 App 不能覆盖或删它的文件）。老 App 不受限制，和 v1 一样。
//  7. 暂存额度（每台设备 200 条 / 50 MB / 7 天）按“命名空间 + 设备 ID”各算各的。
//  8. 新增功能前，先把规则写在这里；已有命名空间的行为不能变。X-App / X-Accept 格式不对，直接 400，不会悄悄当成别的。
//  9. 翻译转发（v3 新增，命名空间 tr，给键盘的输入翻译用）：POST /tr/google?sl=源语言&tl=目标语言，必须带 X-App: tr 和口令。
//     原文放在请求体的 q=… 里（表单格式），不放进网址，所以不会出现在任何访问记录的网址里；本脚本不记录、不缓存内容。
//     只转发到写死的 https://translate.googleapis.com/translate_a/single（不是通用代理，别的路径和网址一律 404）；
//     请求体里只取 q，别的字段不转发；语言代码和请求体大小（最大 40 KB）都检查。上游返回 429 就回 429，其余失败回 502。
//     这条路径不碰 /ws、/r2 和任何命名空间的数据，别的 App 的行为不受影响。
var DEVICE = /^[A-Za-z0-9_-]{1,64}$/;
var APP = /^[a-z0-9]{1,16}$/;
var MAX_ACCEPT = 8;
var PENDING_TTL_MS = 7 * 24 * 3600 * 1e3;
var PENDING_MAX_PER_DEVICE = 200;
var PENDING_MAX_BYTES = 50 * 1024 * 1024;
var HEARTBEAT_TIMEOUT_MS = 90 * 1e3;
var SWEEP_MS = 30 * 1e3;

function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

/** 请求里的命名空间和愿意接收的命名空间。格式不对返回 error。不带 X-App = 老 App 那一组（app 为空字符串） */
function parseApp(req) {
  const raw = req.headers.get("X-App");
  let app = "";
  if (raw !== null && raw !== "") {
    if (!APP.test(raw)) return { error: "X-App 格式不对（小写字母或数字，最长 16 位）" };
    app = raw;
  }
  const accept = [];
  const rawAcc = req.headers.get("X-Accept");
  if (rawAcc) {
    for (const x of rawAcc.split(",")) {
      const a = x.trim();
      if (a === "") continue;
      if (!APP.test(a)) return { error: "X-Accept 格式不对（逗号分隔的命名空间）" };
      if (a !== app && accept.indexOf(a) < 0) accept.push(a);
    }
    if (accept.length > MAX_ACCEPT) return { error: "X-Accept 最多列 " + MAX_ACCEPT + " 个" };
  }
  return { app, accept };
}

/** 连接和暂存用的键：老 App 就是设备 ID 本身（和 v1 一样），新 App 是 “命名空间~设备ID”（~ 不会出现在设备 ID 里，不会撞） */
function tagOf(app, dev) {
  return app ? app + "~" + dev : dev;
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

/** 需要回执的通知：新消息、已收到(got)、取消 / 拒绝、已读 / 撤回等控制消息（丢了发送方状态就不对了）。分段进度、暂停、取消等丢了有网盘兜底，不走回执 */
function ackable(body) {
  if (body && body.ctl && typeof body.ctl === "object") return true;
  if (!body || typeof body.mid !== "string") return false;
  if (body.cancel) return true;
  return !(body.bye || body.abort || body.poke || body.qonly || body.pause !== undefined || (body.p | 0) > 0);
}

var LANG = /^(auto|[A-Za-z]{2,3}(-[A-Za-z]{2,4})?)$/;
var MAX_TRANSLATE_BODY = 40 * 1024;
var GOOGLE_TRANSLATE = "https://translate.googleapis.com/translate_a/single";

/** 翻译转发（规则 9）：只转发到谷歌的 translate_a/single，原文走请求体，不记录、不缓存 */
async function handleTranslateGoogle(req, url) {
  if (req.method !== "POST") return json({ ok: false, error: "只接受 POST（原文放在请求体里，不进网址）" }, 405);
  const sl = url.searchParams.get("sl") || "auto";
  const tl = url.searchParams.get("tl") || "";
  if (!LANG.test(sl) || !LANG.test(tl) || tl === "auto") return json({ ok: false, error: "语言代码不对" }, 400);
  const body = await req.text();
  if (body.length > MAX_TRANSLATE_BODY) return json({ ok: false, error: "原文太长" }, 413);
  const q = new URLSearchParams(body).get("q");
  if (q === null || q === "") return json({ ok: false, error: "请求体里没有 q" }, 400);
  let r;
  try {
    r = await fetch(GOOGLE_TRANSLATE + "?client=gtx&sl=" + encodeURIComponent(sl) + "&tl=" + encodeURIComponent(tl) + "&dt=t", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded; charset=UTF-8", "user-agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36" },
      body: "q=" + encodeURIComponent(q)
    });
  } catch (_) {
    return json({ ok: false, error: "连不上谷歌翻译" }, 502);
  }
  const text = await r.text();
  if (!r.ok) return json({ ok: false, error: "谷歌翻译返回 HTTP " + r.status }, r.status === 429 ? 429 : 502);
  return new Response(text, { status: 200, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

/** 经 Worker 存取 R2（绑定变量名 BUCKET）：手机不用持有 R2 密钥。对象名 = /r2/ 后面的路径；列表返回和 S3 ListObjectsV2 相同的 XML 子集 */
function xmlEsc(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

async function handleR2(req, env, url, app) {
  if (!env.BUCKET) return json({ ok: false, error: "Worker 没有绑定 R2 桶（绑定的变量名必须是 BUCKET）" }, 500);
  if (url.pathname === "/r2") {
    if (req.method !== "GET") return json({ ok: false, error: "不支持" }, 405);
    const prefix = url.searchParams.get("prefix") || "";
    if (app && !prefix.startsWith(app + "/")) return json({ ok: false, error: "只能列出 " + app + "/ 开头的对象" }, 403);
    const token = url.searchParams.get("continuation-token") || undefined;
    const r = await env.BUCKET.list({ prefix, cursor: token, limit: 1000, delimiter: "/" });
    let xml = '<?xml version="1.0" encoding="UTF-8"?><ListBucketResult>';
    for (const o of r.objects) {
      xml += "<Contents><Key>" + xmlEsc(o.key) + "</Key><Size>" + o.size + "</Size><LastModified>" + o.uploaded.toISOString() + "</LastModified></Contents>";
    }
    xml += "<IsTruncated>" + (r.truncated ? "true" : "false") + "</IsTruncated>";
    if (r.truncated && r.cursor) xml += "<NextContinuationToken>" + xmlEsc(r.cursor) + "</NextContinuationToken>";
    xml += "</ListBucketResult>";
    return new Response(xml, { status: 200, headers: { "content-type": "application/xml; charset=utf-8" } });
  }
  let segs;
  try {
    segs = url.pathname.slice(4).split("/").map(decodeURIComponent);
  } catch (_) {
    return json({ ok: false, error: "对象名格式不对" }, 400);
  }
  if (segs.length === 0 || segs.some((x) => x === "" || x === "." || x === "..") || segs.join("/").length > 900) {
    return json({ ok: false, error: "对象名不合法" }, 400);
  }
  const key = segs.join("/");
  if (app && !key.startsWith(app + "/")) return json({ ok: false, error: "只能存取 " + app + "/ 开头的对象" }, 403);
  if (req.method === "PUT") {
    if (!req.body) return json({ ok: false, error: "没有内容" }, 400);
    await env.BUCKET.put(key, req.body, { httpMetadata: { contentType: "application/octet-stream" } });
    return new Response(null, { status: 200 });
  }
  if (req.method === "GET") {
    const obj = await env.BUCKET.get(key, { range: req.headers });
    if (!obj) return new Response("not found", { status: 404 });
    const h = new Headers();
    h.set("etag", obj.httpEtag);
    h.set("content-type", "application/octet-stream");
    let status = 200;
    if (obj.range && req.headers.get("Range")) {
      const off = obj.range.offset !== undefined ? obj.range.offset : 0;
      const len = obj.range.length !== undefined ? obj.range.length : obj.size - off;
      h.set("content-range", "bytes " + off + "-" + (off + len - 1) + "/" + obj.size);
      h.set("content-length", String(len));
      status = 206;
    } else {
      h.set("content-length", String(obj.size));
    }
    return new Response(obj.body, { status, headers: h });
  }
  if (req.method === "DELETE") {
    await env.BUCKET.delete(key);
    return new Response(null, { status: 204 });
  }
  return json({ ok: false, error: "不支持" }, 405);
}

var worker_default = {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (!env.SECRET) {
      return json({ ok: false, error: "服务器还没有设置口令（SECRET）" }, 500);
    }
    if (!same(req.headers.get("X-Relay-Secret") || "", env.SECRET)) {
      return json({ ok: false, error: "口令不对" }, 401);
    }
    if (url.pathname === "/test") {
      return json({ ok: true, time: Date.now() });
    }
    if (url.pathname === "/tr/google") {
      const p = parseApp(req);
      if (p.error) return json({ ok: false, error: p.error }, 400);
      if (p.app !== "tr") return json({ ok: false, error: "翻译转发需要 X-App: tr" }, 403);
      return handleTranslateGoogle(req, url);
    }
    if (url.pathname === "/r2" || url.pathname.startsWith("/r2/")) {
      const p = parseApp(req);
      if (p.error) return json({ ok: false, error: p.error }, 400);
      return handleR2(req, env, url, p.app);
    }
    if (url.pathname === "/ws") {
      const p = parseApp(req);
      if (p.error) return json({ ok: false, error: p.error }, 400);
      const dev = req.headers.get("X-Device") || "";
      if (!DEVICE.test(dev)) return json({ ok: false, error: "设备 ID 格式不对" }, 400);
      if (req.headers.get("Upgrade") !== "websocket") return json({ ok: false, error: "需要 WebSocket" }, 426);
      const stub = env.HUB.get(env.HUB.idFromName("main"));
      return stub.fetch(req);
    }
    return json({ ok: false, error: "没有这个地址" }, 404);
  }
};

var Hub = class {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.sql = ctx.storage.sql;
    this.sql.exec(
      "CREATE TABLE IF NOT EXISTS pending (id INTEGER PRIMARY KEY AUTOINCREMENT, to_dev TEXT NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL)"
    );
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  /** 一条连接的身份：设备 ID、命名空间、愿意接收哪些命名空间的通知、是不是新版（带回执）。v2 之前就连着的老连接没有这些字段，当作老 App */
  meta(ws) {
    let at = null;
    try {
      at = ws.deserializeAttachment();
    } catch (_) {
    }
    const tags = this.ctx.getTags(ws);
    return {
      dev: (at && at.dev) || tags[0] || "",
      app: (at && at.app) || "",
      accept: (at && at.accept) || [],
      ack: !!(at && at.ack)
    };
  }

  /** 某个命名空间里在线的设备 ID（只含这个命名空间） */
  online(app) {
    const set = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== 1) continue;
      const m = this.meta(ws);
      if (m.app !== app) continue;
      if (m.dev) set.add(m.dev);
    }
    return [...set];
  }

  /** 这条连接是不是新版 App（连接时带了 X-Ack: 1） */
  acks(ws) {
    return this.meta(ws).ack;
  }

  async fetch(req) {
    const dev = req.headers.get("X-Device");
    const ack = req.headers.get("X-Ack") === "1";
    const p = parseApp(req);
    if (p.error) return json({ ok: false, error: p.error }, 400);
    const app = p.app;
    const key = tagOf(app, dev);
    for (const old of this.ctx.getWebSockets(key)) {
      try {
        old.close(1e3, "被同一设备的新连接替换");
      } catch (_) {
      }
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [key]);
    server.serializeAttachment({ t: Date.now(), ack, dev, app, accept: p.accept });
    await this.ensureAlarm();
    const cutoff = Date.now() - PENDING_TTL_MS;
    this.sql.exec("DELETE FROM pending WHERE ts < ?", cutoff);
    // 补发留给这台设备的通知。新版 App：需要回执的带序号发，不删，等它回执；其余照旧发完就删。旧版 App：全部发完就删
    const rows = this.sql.exec("SELECT id, body FROM pending WHERE to_dev = ? ORDER BY id", key).toArray();
    for (const r of rows) {
      let keep = false;
      let text = r.body;
      if (ack) {
        try {
          const o = JSON.parse(r.body);
          if (ackable(o.body)) {
            o.n = r.id;
            text = JSON.stringify(o);
            keep = true;
          }
        } catch (_) {
        }
      }
      try {
        server.send(text);
      } catch (_) {
        keep = true;
      }
      if (!keep) this.sql.exec("DELETE FROM pending WHERE id = ?", r.id);
    }
    server.send(JSON.stringify({ op: "presence", online: this.online(app) }));
    this.presence(app, dev);
    return new Response(null, { status: 101, webSocket: client });
  }

  async ensureAlarm() {
    if (await this.ctx.storage.getAlarm() == null) await this.ctx.storage.setAlarm(Date.now() + SWEEP_MS);
  }

  async alarm() {
    const now = Date.now();
    const droppedApps = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== 1) continue;
      const a = this.ctx.getWebSocketAutoResponseTimestamp(ws);
      let last = a ? a.getTime() : 0;
      try {
        const at = ws.deserializeAttachment();
        if (at && at.t > last) last = at.t;
      } catch (_) {
      }
      if (now - last > HEARTBEAT_TIMEOUT_MS) {
        const app = this.meta(ws).app;
        try {
          ws.close(1001, "heartbeat timeout");
        } catch (_) {
        }
        droppedApps.add(app);
      }
    }
    for (const app of droppedApps) this.presence(app, null);
    if (this.ctx.getWebSockets().some((w) => w.readyState === 1)) await this.ctx.storage.setAlarm(Date.now() + SWEEP_MS);
  }

  /** 把某个命名空间的在线名单发给这个命名空间里的所有连接（exceptDev 除外）。别的命名空间收不到 */
  presence(app, exceptDev) {
    const s = JSON.stringify({ op: "presence", online: this.online(app) });
    for (const ws of this.ctx.getWebSockets()) {
      const m = this.meta(ws);
      if (m.app !== app || m.dev === exceptDev) continue;
      try {
        ws.send(s);
      } catch (_) {
      }
    }
  }

  /** 跨 App 通知：只实时送达；对方（指定命名空间里的这台设备）必须在连接时用 X-Accept 同意接收发送方的命名空间。不暂存、不回执 */
  crossApp(ws, me, m, toApp) {
    const msg = JSON.stringify({ op: "notify", from: me.dev, app: me.app, body: m.body || {} });
    let delivered = false;
    for (const t of this.ctx.getWebSockets(tagOf(toApp, m.to))) {
      if (t.readyState !== 1) continue;
      if (this.meta(t).accept.indexOf(me.app) < 0) continue;
      try {
        t.send(msg);
        delivered = true;
      } catch (_) {
      }
    }
    ws.send(JSON.stringify({ op: "sent", mid: m.body && m.body.mid, online: delivered }));
  }

  webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 1100000) return;   // 小文字 / 小文件直接放在通知里（App 限制 700 KB 以内，加密转文本后接近 1 MB）
    const me = this.meta(ws);
    const from = me.dev;
    const app = me.app;
    const myKey = tagOf(app, from);
    let m;
    try {
      m = JSON.parse(message);
    } catch (_) {
      return;
    }
    // 回执：只能删发给自己的那条
    if (m.op === "ack") {
      if (Number.isInteger(m.n)) this.sql.exec("DELETE FROM pending WHERE id = ? AND to_dev = ?", m.n, myKey);
      return;
    }
    if (m.op !== "notify" || typeof m.to !== "string" || !DEVICE.test(m.to)) return;
    // 目标命名空间：默认和发送方相同；toApp 不同就是跨 App（规则 4）
    if (m.toApp !== undefined) {
      if (typeof m.toApp !== "string" || (m.toApp !== "" && !APP.test(m.toApp))) return;
      if (m.toApp !== app) {
        this.crossApp(ws, me, m, m.toApp);
        return;
      }
    }
    const toKey = tagOf(app, m.to);
    const msg = { op: "notify", from, body: m.body || {} };
    const plain = JSON.stringify(msg);
    const targets = this.ctx.getWebSockets(toKey);
    const isBye = !!(m.body && m.body.bye === true);
    const needAck = ackable(m.body) && targets.some((t) => t.readyState === 1 && this.acks(t));
    let id = null;
    if (needAck) {
      // 先存再发：连接是半死的话，send 也不会报错，没有回执就留着，等对方重连补发
      const n = this.sql.exec("SELECT COUNT(*) AS n FROM pending WHERE to_dev = ?", toKey).one().n;
      const bytes = this.sql.exec("SELECT COALESCE(SUM(LENGTH(body)), 0) AS b FROM pending WHERE to_dev = ?", toKey).one().b;
      if (n >= PENDING_MAX_PER_DEVICE || bytes > PENDING_MAX_BYTES) {
        ws.send(JSON.stringify({ op: "error", mid: m.body && m.body.mid, error: "对方暂存的通知已满" }));
        return;
      }
      id = this.sql.exec("INSERT INTO pending (to_dev, body, ts) VALUES (?, ?, ?) RETURNING id", toKey, plain, Date.now()).one().id;
    }
    let delivered = false;
    for (const t of targets) {
      try {
        t.send(id !== null && this.acks(t) ? JSON.stringify({ op: "notify", from, body: msg.body, n: id }) : plain);
        delivered = true;
      } catch (_) {
      }
    }
    if (!delivered && !isBye && id === null) {
      const n = this.sql.exec("SELECT COUNT(*) AS n FROM pending WHERE to_dev = ?", toKey).one().n;
      if (n >= PENDING_MAX_PER_DEVICE) {
        ws.send(JSON.stringify({ op: "error", mid: m.body && m.body.mid, error: "对方离线且暂存的通知已满" }));
        return;
      }
      this.sql.exec("INSERT INTO pending (to_dev, body, ts) VALUES (?, ?, ?)", toKey, plain, Date.now());
    }
    ws.send(JSON.stringify({ op: "sent", mid: m.body && m.body.mid, online: delivered }));
  }

  webSocketClose(ws, code, reason) {
    const app = this.meta(ws).app;   // 先取命名空间：关掉以后再读连接的附件可能读不到
    try {
      ws.close(code, reason);
    } catch (_) {
    }
    this.presence(app, null);
  }

  webSocketError(ws) {
    this.webSocketClose(ws, 1011, "error");
  }
};

export {
  Hub,
  worker_default as default
};
