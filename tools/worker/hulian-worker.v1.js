// worker.js —— 互传的 Cloudflare 中转（转发通知；小内容直接放通知里；大文件走网盘，或者经本 Worker 存取 R2：绑定变量名 BUCKET）
// 新增：通知回执。新版 App 连接时带 X-Ack: 1，Worker 对它的“新消息”通知先存表、带序号发出，
// 收到 App 回的 {op:"ack", n:序号} 才删；没回执的会在它下次连上时补发。旧版 App 不带这个头，行为和以前完全一样。
var DEVICE = /^[A-Za-z0-9_-]{1,64}$/;
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

/** 经 Worker 存取 R2（绑定变量名 BUCKET）：手机不用持有 R2 密钥。对象名 = /r2/ 后面的路径；列表返回和 S3 ListObjectsV2 相同的 XML 子集 */
function xmlEsc(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

async function handleR2(req, env, url) {
  if (!env.BUCKET) return json({ ok: false, error: "Worker 没有绑定 R2 桶（绑定的变量名必须是 BUCKET）" }, 500);
  if (url.pathname === "/r2") {
    if (req.method !== "GET") return json({ ok: false, error: "不支持" }, 405);
    const prefix = url.searchParams.get("prefix") || "";
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
    if (url.pathname === "/r2" || url.pathname.startsWith("/r2/")) {
      return handleR2(req, env, url);
    }
    if (url.pathname === "/ws") {
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

  online() {
    const set = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== 1) continue;
      const t = this.ctx.getTags(ws);
      if (t.length) set.add(t[0]);
    }
    return [...set];
  }

  /** 这条连接是不是新版 App（连接时带了 X-Ack: 1） */
  acks(ws) {
    try {
      const at = ws.deserializeAttachment();
      return !!(at && at.ack);
    } catch (_) {
      return false;
    }
  }

  async fetch(req) {
    const dev = req.headers.get("X-Device");
    const ack = req.headers.get("X-Ack") === "1";
    for (const old of this.ctx.getWebSockets(dev)) {
      try {
        old.close(1e3, "被同一设备的新连接替换");
      } catch (_) {
      }
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [dev]);
    server.serializeAttachment({ t: Date.now(), ack });
    await this.ensureAlarm();
    const cutoff = Date.now() - PENDING_TTL_MS;
    this.sql.exec("DELETE FROM pending WHERE ts < ?", cutoff);
    // 补发留给这台设备的通知。新版 App：需要回执的带序号发，不删，等它回执；其余照旧发完就删。旧版 App：全部发完就删
    const rows = this.sql.exec("SELECT id, body FROM pending WHERE to_dev = ? ORDER BY id", dev).toArray();
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
    server.send(JSON.stringify({ op: "presence", online: this.online() }));
    this.broadcast({ op: "presence", online: this.online() }, dev);
    return new Response(null, { status: 101, webSocket: client });
  }

  async ensureAlarm() {
    if (await this.ctx.storage.getAlarm() == null) await this.ctx.storage.setAlarm(Date.now() + SWEEP_MS);
  }

  async alarm() {
    const now = Date.now();
    let dropped = false;
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
        try {
          ws.close(1001, "heartbeat timeout");
        } catch (_) {
        }
        dropped = true;
      }
    }
    if (dropped) this.broadcast({ op: "presence", online: this.online() }, null);
    if (this.ctx.getWebSockets().some((w) => w.readyState === 1)) await this.ctx.storage.setAlarm(Date.now() + SWEEP_MS);
  }

  broadcast(msg, exceptDev) {
    const s = JSON.stringify(msg);
    for (const ws of this.ctx.getWebSockets()) {
      const t = this.ctx.getTags(ws);
      if (t[0] === exceptDev) continue;
      try {
        ws.send(s);
      } catch (_) {
      }
    }
  }

  webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 1100000) return;   // 小文字 / 小文件直接放在通知里（App 限制 700 KB 以内，加密转文本后接近 1 MB）
    const from = this.ctx.getTags(ws)[0];
    let m;
    try {
      m = JSON.parse(message);
    } catch (_) {
      return;
    }
    // 回执：只能删发给自己的那条
    if (m.op === "ack") {
      if (Number.isInteger(m.n)) this.sql.exec("DELETE FROM pending WHERE id = ? AND to_dev = ?", m.n, from);
      return;
    }
    if (m.op !== "notify" || typeof m.to !== "string" || !DEVICE.test(m.to)) return;
    const msg = { op: "notify", from, body: m.body || {} };
    const plain = JSON.stringify(msg);
    const targets = this.ctx.getWebSockets(m.to);
    const isBye = !!(m.body && m.body.bye === true);
    const needAck = ackable(m.body) && targets.some((t) => t.readyState === 1 && this.acks(t));
    let id = null;
    if (needAck) {
      // 先存再发：连接是半死的话，send 也不会报错，没有回执就留着，等对方重连补发
      const n = this.sql.exec("SELECT COUNT(*) AS n FROM pending WHERE to_dev = ?", m.to).one().n;
      const bytes = this.sql.exec("SELECT COALESCE(SUM(LENGTH(body)), 0) AS b FROM pending WHERE to_dev = ?", m.to).one().b;
      if (n >= PENDING_MAX_PER_DEVICE || bytes > PENDING_MAX_BYTES) {
        ws.send(JSON.stringify({ op: "error", mid: m.body && m.body.mid, error: "对方暂存的通知已满" }));
        return;
      }
      id = this.sql.exec("INSERT INTO pending (to_dev, body, ts) VALUES (?, ?, ?) RETURNING id", m.to, plain, Date.now()).one().id;
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
      const n = this.sql.exec("SELECT COUNT(*) AS n FROM pending WHERE to_dev = ?", m.to).one().n;
      if (n >= PENDING_MAX_PER_DEVICE) {
        ws.send(JSON.stringify({ op: "error", mid: m.body && m.body.mid, error: "对方离线且暂存的通知已满" }));
        return;
      }
      this.sql.exec("INSERT INTO pending (to_dev, body, ts) VALUES (?, ?, ?)", m.to, plain, Date.now());
    }
    ws.send(JSON.stringify({ op: "sent", mid: m.body && m.body.mid, online: delivered }));
  }

  webSocketClose(ws, code, reason) {
    try {
      ws.close(code, reason);
    } catch (_) {
    }
    this.broadcast({ op: "presence", online: this.online() }, null);
  }

  webSocketError(ws) {
    this.webSocketClose(ws, 1011, "error");
  }
};

export {
  Hub,
  worker_default as default
};
