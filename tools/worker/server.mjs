// 在本机用真正的 Worker 脚本起一个能用真 WebSocket / HTTP 连的服务,给 Java 端的测试连(tools/ClipRelayE2E.java)。
//   node --experimental-sqlite tools/worker/server.mjs <Worker 脚本路径> <端口>
// 只是 Cloudflare 运行环境的模拟(mock.mjs),被测的是真正的脚本。额外有两个测试用的地址(不要部署):
//   /__test/pending?key=<暂存键>  返回这个键下的暂存条数;  DELETE 同一地址清空(模拟暂存被挤掉 / 过期)
import http from "node:http";
import crypto from "node:crypto";
import { createWorld } from "./mock.mjs";

const [, , workerPath, portStr] = process.argv;
const world = await createWorld(workerPath);
const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

function frame(opcode, payload) {
  const len = payload.length;
  let head;
  if (len < 126) head = Buffer.from([0x80 | opcode, len]);
  else if (len < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | opcode; head[1] = 126; head.writeUInt16BE(len, 2); }
  else { head = Buffer.alloc(10); head[0] = 0x80 | opcode; head[1] = 127; head.writeBigUInt64BE(BigInt(len), 2); }
  return Buffer.concat([head, payload]);
}

const srv = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks);
  const url = new URL(req.url, "http://x");
  if (url.pathname === "/__test/pending") {
    const key = url.searchParams.get("key") || "";
    if (req.method === "DELETE") world.db.prepare("DELETE FROM pending WHERE to_dev = ?").run(key);
    const n = world.db.prepare("SELECT COUNT(*) AS n FROM pending WHERE to_dev = ?").get(key).n;
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ n }));
    return;
  }
  const h = new Headers();
  for (const [k, v] of Object.entries(req.headers)) if (/^x-|^content-type$|^range$/i.test(k)) h.set(k, String(v));
  const init = { method: req.method, headers: h };
  if (req.method !== "GET" && req.method !== "HEAD" && body.length) init.body = body;
  const r = await world.mod.default.fetch(new Request("https://w.example" + req.url, init), world.env);
  const out = Buffer.from(await r.arrayBuffer());
  const heads = {};
  r.headers.forEach((v, k) => { heads[k] = v; });
  res.writeHead(r.status, heads);
  res.end(out);
});

srv.on("upgrade", async (req, socket) => {
  const hdrs = new Headers();
  for (const [k, v] of Object.entries(req.headers)) hdrs.set(k, String(v));
  const wreq = { url: "https://w.example" + req.url, method: "GET", headers: hdrs };
  const res = await world.mod.default.fetch(wreq, world.env);
  if (res.status !== 101) {
    const out = Buffer.from(await res.arrayBuffer());
    socket.write(`HTTP/1.1 ${res.status} X\r\ncontent-type: application/json\r\ncontent-length: ${out.length}\r\nconnection: close\r\n\r\n`);
    socket.end(out);
    return;
  }
  const client = res.webSocket;
  const serverEnd = client.peer;
  const accept = crypto.createHash("sha1").update(req.headers["sec-websocket-key"] + GUID).digest("base64");
  socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
  // Worker 在返回 101 之前就会把暂存的通知发出来(Cloudflare 会先排队、握手完成后送出):握手写完以后把已经攒着的先送出去
  for (const t of client.inbox) socket.write(frame(1, Buffer.from(t, "utf8")));
  client.inbox.length = 0;
  client.listeners.push((t) => { if (!socket.destroyed) socket.write(frame(1, Buffer.from(t, "utf8"))); });
  client.closeListeners = [() => { if (!socket.destroyed) { socket.write(frame(8, Buffer.alloc(0))); socket.end(); } }];
  let buf = Buffer.alloc(0);
  let msg = [];
  let gone = false;
  const bye = (code, reason) => {
    if (gone) return;
    gone = true;
    if (serverEnd.readyState !== 3) { serverEnd.readyState = 3; client.readyState = 3; world.hub.webSocketClose(serverEnd, code, reason); }
  };
  socket.on("data", (d) => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 2) {
      const fin = (buf[0] & 0x80) !== 0, op = buf[0] & 15, masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 127, off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      const mlen = masked ? 4 : 0;
      if (buf.length < off + mlen + len) return;
      const mask = masked ? buf.subarray(off, off + 4) : null;
      const data = Buffer.from(buf.subarray(off + mlen, off + mlen + len));
      if (mask) for (let i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
      buf = buf.subarray(off + mlen + len);
      if (op === 8) { try { socket.write(frame(8, Buffer.alloc(0))); socket.end(); } catch (_) {} bye(1000, "client close"); return; }
      if (op === 9) { socket.write(frame(10, data)); continue; }
      if (op === 10) continue;
      if (op === 1 || op === 0) {
        msg.push(data);
        if (fin) {
          const text = Buffer.concat(msg).toString("utf8");
          msg = [];
          if (serverEnd.readyState === 1) world.serverReceive(serverEnd, text);
        }
      }
    }
  });
  socket.on("close", () => bye(1006, "gone"));
  socket.on("error", () => bye(1006, "error"));
});

setInterval(() => { try { world.hub.alarm(); } catch (_) {} }, 30000).unref();
srv.listen(Number(portStr), "127.0.0.1", () => console.log("READY " + portStr));
process.on("SIGTERM", () => process.exit(0));
