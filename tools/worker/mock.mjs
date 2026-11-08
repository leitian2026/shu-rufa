// Cloudflare Workers / Durable Object 的最小模拟(只够跑 hulian-worker 的代码):内存 SQLite、WebSocketPair、R2。
// 被测的是真正的 Worker 脚本;这里模拟的只是 Cloudflare 的运行环境。
import { DatabaseSync } from "node:sqlite";
import { pathToFileURL } from "node:url";

let installed = false;
export class MockSock {
  constructor() {
    this.readyState = 1;
    this.peer = null;
    this.onSend = null;     // 谁来处理“这一端发出去的文本”
    this.inbox = [];        // 这一端收到的文本
    this.listeners = [];
    this.attachment = undefined;
    this.lastAuto = 0;
    this.closeInfo = null;
  }
  send(t) {
    if (this.readyState !== 1) throw new Error("socket closed");
    if (this.onSend) this.onSend(t);
    else if (this.peer) this.peer.deliver(t);
  }
  deliver(t) {
    this.inbox.push(t);
    for (const l of this.listeners) l(t);
  }
  close(code, reason) {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.closeInfo = { code, reason };
    if (this.peer && this.peer.readyState !== 3) {
      this.peer.readyState = 3;
      this.peer.closeInfo = { code, reason };
      for (const l of this.peer.closeListeners || []) l(code, reason);
    }
  }
  serializeAttachment(v) { this.attachment = structuredClone(v); }
  deserializeAttachment() { return this.attachment === undefined ? null : structuredClone(this.attachment); }
}

export function installGlobals() {
  if (installed) return;
  installed = true;
  const Orig = globalThis.Response;
  class R extends Orig {
    constructor(body, init) {
      if (init && init.status === 101) {
        super(null, { status: 200 });
        Object.defineProperty(this, "status", { value: 101 });
        this.webSocket = init.webSocket;
      } else {
        super(body, init);
      }
    }
  }
  globalThis.Response = R;
  globalThis.WebSocketPair = class {
    constructor() {
      this[0] = new MockSock();
      this[1] = new MockSock();
      this[0].peer = this[1];
      this[1].peer = this[0];
    }
  };
  globalThis.WebSocketRequestResponsePair = class {
    constructor(a, b) { this.request = a; this.response = b; }
  };
}

function mockBucket() {
  const m = new Map();
  return {
    m,
    async put(key, body) {
      const buf = Buffer.from(await new Response(body).arrayBuffer());
      m.set(key, { data: buf, uploaded: new Date() });
    },
    async get(key) {
      const o = m.get(key);
      if (!o) return null;
      return { key, size: o.data.length, httpEtag: '"e"', body: o.data, range: undefined };
    },
    async delete(key) { m.delete(key); },
    async list({ prefix = "", delimiter }) {
      const objects = [];
      for (const [k, v] of m) {
        if (!k.startsWith(prefix)) continue;
        if (delimiter && k.slice(prefix.length).includes(delimiter)) continue;
        objects.push({ key: k, size: v.data.length, uploaded: v.uploaded });
      }
      objects.sort((a, b) => (a.key < b.key ? -1 : 1));
      return { objects, truncated: false };
    }
  };
}

export async function createWorld(modPath, { secret = "s3cret" } = {}) {
  installGlobals();
  const mod = await import(pathToFileURL(modPath).href + "?w=" + Math.random());
  const db = new DatabaseSync(":memory:");
  const sockets = new Set();
  const tags = new Map();
  let alarm = null;
  const ctx = {
    storage: {
      sql: {
        exec(sql, ...params) {
          const st = db.prepare(sql);
          let rows = [];
          if (/^\s*select/i.test(sql) || /returning/i.test(sql)) rows = st.all(...params);
          else st.run(...params);
          return { toArray: () => rows, one: () => rows[0] };
        }
      },
      getAlarm: async () => alarm,
      setAlarm: async (t) => { alarm = t; }
    },
    setWebSocketAutoResponse() {},
    acceptWebSocket(ws, t) { sockets.add(ws); tags.set(ws, t || []); },
    getWebSockets(tag) {
      return [...sockets].filter((ws) => ws.readyState !== 3 && (tag === undefined || (tags.get(ws) || []).includes(tag)));
    },
    getTags(ws) { return tags.get(ws) || []; },
    getWebSocketAutoResponseTimestamp(ws) { return ws.lastAuto ? new Date(ws.lastAuto) : null; }
  };
  const bucket = mockBucket();
  let hub;
  const env = {
    SECRET: secret,
    BUCKET: bucket,
    HUB: { idFromName: (n) => n, get: () => ({ fetch: (req) => hub.fetch(req) }) }
  };
  hub = new mod.Hub(ctx, env);

  function serverReceive(server, text) {
    if (text === "ping") {
      server.lastAuto = Date.now();
      if (server.peer && server.peer.readyState === 1) server.peer.deliver("pong");
      return;
    }
    hub.webSocketMessage(server, text);
  }

  const world = {
    mod, hub, db, ctx, bucket, env,
    rows(sql, ...p) { return db.prepare(sql).all(...p); },
    pending() { return db.prepare("SELECT id, to_dev, body FROM pending ORDER BY id").all(); },
    /** 连接 /ws。返回 { status, body } 或 Client */
    async connect({ dev, app, accept, ack = true, extra = {}, secretOverride } = {}) {
      const h = new Headers({ "X-Relay-Secret": secretOverride ?? secret, Upgrade: "websocket" });
      if (dev !== undefined) h.set("X-Device", dev);
      if (app !== undefined) h.set("X-App", app);
      if (accept !== undefined) h.set("X-Accept", accept);
      if (ack) h.set("X-Ack", "1");
      for (const [k, v] of Object.entries(extra)) h.set(k, v);
      const req = { url: "https://w.example/ws", method: "GET", headers: h };
      const res = await mod.default.fetch(req, env);
      if (res.status !== 101) return { status: res.status, body: await res.json() };
      const client = res.webSocket;
      const server = client.peer;
      client.onSend = (t) => serverReceive(server, t);
      client.closeListeners = [];
      // 客户端主动关:通知 Hub
      const origClose = client.close.bind(client);
      client.close = (code = 1000, reason = "") => {
        if (client.readyState === 3) return;
        origClose(code, reason);
        hub.webSocketClose(server, code, reason);
      };
      const c = {
        status: 101, client, server,
        get inbox() { return client.inbox.map((t) => { try { return JSON.parse(t); } catch (_) { return t; } }); },
        send(o) { client.send(typeof o === "string" ? o : JSON.stringify(o)); },
        close() { client.close(1000, "bye"); },
        clear() { client.inbox.length = 0; },
        get open() { return client.readyState === 1; }
      };
      return c;
    },
    async http(method, path, { headers = {}, body, secretOverride } = {}) {
      const h = new Headers({ "X-Relay-Secret": secretOverride ?? secret, ...headers });
      const init = { method, headers: h };
      if (body !== undefined && method !== "GET") init.body = body;
      const res = await mod.default.fetch(new Request("https://w.example" + path, init), env);
      const text = await res.text();
      return { status: res.status, text };
    },
    /** 把一个“部署前就连着”的老连接直接塞进 Hub:附件只有 {t, ack},标签只有设备 ID */
    legacyConnection(dev, ack = true) {
      const pair = new WebSocketPair();
      const client = pair[0], server = pair[1];
      ctx.acceptWebSocket(server, [dev]);
      server.serializeAttachment({ t: Date.now(), ack });
      client.onSend = (t) => serverReceive(server, t);
      return {
        client, server,
        get inbox() { return client.inbox.map((t) => { try { return JSON.parse(t); } catch (_) { return t; } }); },
        send(o) { client.send(typeof o === "string" ? o : JSON.stringify(o)); },
        clear() { client.inbox.length = 0; }
      };
    },
    serverReceive,
    runAlarm() { return hub.alarm(); }
  };
  return world;
}
