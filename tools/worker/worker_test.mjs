// hulian-worker 自测:在 Node 里用 Cloudflare 环境的最小模拟(mock.mjs)跑真正的 Worker 脚本。
//   node --experimental-sqlite tools/worker/worker_test.mjs <v2 脚本路径> [<v1 原脚本路径>]
// 给了 v1 路径就会把同一套老 App 场景在 v1 和 v2 上各跑一遍,逐条对比收到的消息和暂存表,证明老 App 的行为没变。
import { createWorld } from "./mock.mjs";

let pass = 0, fail = 0;
function t(ok, name) {
  console.log((ok ? "PASS " : "FAIL ") + name);
  ok ? pass++ : fail++;
}
const J = (x) => JSON.stringify(x);
const [, , v2Path, v1Path] = process.argv;

// ---------------------------------------------------------------- A. 老 App 场景:v1 / v2 对比
async function legacyScenario(path) {
  const w = await createWorld(path);
  const log = {};
  const A = await w.connect({ dev: "devA" });
  const B = await w.connect({ dev: "devB", ack: false });   // 旧版 App:不带 X-Ack
  const C = await w.connect({ dev: "devC" });
  // 1. 普通通知(带 mid 的需要回执)
  A.send({ op: "notify", to: "devB", body: { mid: "m1", text: "hi" } });
  B.send({ op: "notify", to: "devA", body: { mid: "m2", text: "yo" } });
  // 回执
  const nA = A.inbox.find((m) => m.op === "notify");
  if (nA && nA.n) A.send({ op: "ack", n: nA.n });
  // 2. 对方离线 → 暂存 → 重连补发(新版带序号、旧版不带)
  B.close();
  A.send({ op: "notify", to: "devB", body: { mid: "m3", text: "while offline" } });
  A.send({ op: "notify", to: "devB", body: { mid: "m4", bye: true } });          // bye 不存
  A.send({ op: "notify", to: "devB", body: { mid: "m5", p: 3 } });              // 分段进度:不走回执,离线时照存
  C.close();
  A.send({ op: "notify", to: "devC", body: { mid: "m6", text: "for C" } });
  const C2 = await w.connect({ dev: "devC" });                                   // 新版:带序号补发,不回执就一直留着
  const B2 = await w.connect({ dev: "devB", ack: false });                       // 旧版:补发完就删
  // 3. 回执只能删发给自己的
  const nC = C2.inbox.find((m) => m.op === "notify" && m.n);
  A.send({ op: "ack", n: nC ? nC.n : 999 });                                     // A 不能替 C 回执
  const stillThere = w.pending().length;
  if (nC) C2.send({ op: "ack", n: nC.n });
  // 4. 同一设备新连接顶掉旧连接
  const A2 = await w.connect({ dev: "devA" });
  // 5. 垃圾输入被忽略
  A2.send("not json");
  A2.send(J({ op: "notify", to: "bad id!", body: {} }));
  A2.send(J({ op: "unknown" }));
  // 6. 暂存额度:给离线设备发 205 条需要回执的通知
  const errs = [];
  for (let i = 0; i < 205; i++) {
    A2.send({ op: "notify", to: "devZ", body: { mid: "z" + i } });
  }
  for (const m of A2.inbox) if (m.op === "error") errs.push(m.error);
  // 7. ping/pong
  C2.clear(); C2.send("ping");
  log.pong = C2.client.inbox.slice();
  log.errs = errs;
  log.stillThere = stillThere;
  log.pendingCount = w.pending().length;
  log.pendingRows = w.pending().filter((r) => r.to_dev !== "devZ");
  log.inbox = { A: A.inbox, B: B.inbox, C: C.inbox, B2: B2.inbox, C2: C2.inbox.filter((m) => m !== "pong"), A2: A2.inbox.filter((m) => m.op !== "sent" || true) };
  log.AclosedByReplace = !A.open;
  return log;
}

if (v1Path) {
  const a = await legacyScenario(v1Path);
  const b = await legacyScenario(v2Path);
  t(J(a.inbox) === J(b.inbox), "老 App 场景:v1 和 v2 每个客户端收到的消息逐条一致");
  t(J(a.pendingRows) === J(b.pendingRows) && a.pendingCount === b.pendingCount, "老 App 场景:暂存表一致(键仍是设备 ID 本身)");
  t(J(a.errs) === J(b.errs) && a.errs.length > 0, "老 App 场景:暂存额度的出错提示一致(" + a.errs.length + " 条)");
  t(J(a.pong) === J(b.pong) && a.stillThere === b.stillThere, "老 App 场景:心跳回应一致;别人替你回执删不掉");
  t(a.AclosedByReplace === b.AclosedByReplace && b.AclosedByReplace, "老 App 场景:同一设备的新连接顶掉旧连接,一致");
}

// ---------------------------------------------------------------- B. 命名空间
const w = await createWorld(v2Path);
const A = await w.connect({ dev: "devA" });                       // 老 App
const C1 = await w.connect({ dev: "c1", app: "clip" });
const C2 = await w.connect({ dev: "c2", app: "clip" });
const O1 = await w.connect({ dev: "o1", app: "other", accept: "clip" });
const presOf = (c) => c.inbox.filter((m) => m.op === "presence").map((m) => m.online.slice().sort());
const lastPres = (c) => presOf(c).at(-1);

t(J(lastPres(A)) === J(["devA"]), "在线名单:老 App 只看到老 App 的设备");
t(J(lastPres(C1)) === J(["c1", "c2"]) && J(lastPres(C2)) === J(["c1", "c2"]), "在线名单:clip 只看到 clip 的设备");
t(J(lastPres(O1)) === J(["o1"]), "在线名单:other 只看到 other 的设备");
t(!A.inbox.some((m) => m.op === "presence" && m.online.some((d) => d !== "devA")), "老 App 从来没收到过别的命名空间的设备 ID");

// 同一设备 ID 在两个命名空间里互不顶替
const X1 = await w.connect({ dev: "same" });
const X2 = await w.connect({ dev: "same", app: "clip" });
t(X1.open && X2.open, "同一个设备 ID 在老 App 和 clip 里各有一条连接,互不顶替");
const X3 = await w.connect({ dev: "same", app: "clip" });
t(!X2.open && X3.open && X1.open, "同一命名空间里同一设备 ID 的新连接才顶替旧的");

// 通知隔离
A.clear(); C1.clear(); C2.clear();
C1.send({ op: "notify", to: "c2", body: { mid: "k1", clip: 1 } });
t(C2.inbox.some((m) => m.op === "notify" && m.from === "c1"), "同命名空间内通知正常送达");
const sentC1 = C1.inbox.find((m) => m.op === "sent");
t(sentC1 && sentC1.online === true, "同命名空间内 sent 回报在线");
C1.clear();
C1.send({ op: "notify", to: "devA", body: { mid: "k2" } });   // devA 只存在于老 App
t(!A.inbox.some((m) => m.op === "notify"), "clip 发给“老 App 的设备 ID”:老 App 收不到");
t(C1.inbox.find((m) => m.op === "sent").online === false, "clip 发给不在自己命名空间的设备:回报不在线");
t(w.pending().some((r) => r.to_dev === "clip~devA"), "离线暂存在 clip~devA 下,不在 devA 下");
const A2 = await w.connect({ dev: "devA" });
t(!A2.inbox.some((m) => m.op === "notify"), "老 App 的 devA 重连:收不到 clip 命名空间的暂存");

// 暂存额度各算各的
for (let i = 0; i < 205; i++) C1.send({ op: "notify", to: "gone", body: { mid: "q" + i } });
const full = C1.inbox.filter((m) => m.op === "error").length;
t(full >= 1, "clip 里给离线设备攒满 200 条以后出错");
const L1 = await w.connect({ dev: "gone" });                  // 老 App 里同名设备 gone
const L0 = await w.connect({ dev: "sender0" });
L0.send({ op: "notify", to: "gone", body: { mid: "ok1" } });
t(L1.inbox.some((m) => m.op === "notify" && m.body.mid === "ok1"), "clip 的暂存满了,不影响老 App 里同名设备收通知");
t(!L1.inbox.some((m) => m.op === "notify" && String(m.body.mid).startsWith("q")), "老 App 的 gone 收不到 clip 里给 gone 的暂存");

// 回执隔离
const legacyRow = w.pending().find((r) => !r.to_dev.includes("~"));
const clipRowId = w.pending().find((r) => r.to_dev.startsWith("clip~"));
const before = w.pending().length;
C1.send({ op: "ack", n: (w.pending().filter((r) => !r.to_dev.includes("~"))[0] || { id: 1 }).id });
t(w.pending().length === before, "clip 的连接不能替老 App 的设备回执(暂存没少)");

// 跨 App
O1.clear(); C1.clear();
C1.send({ op: "notify", to: "o1", toApp: "other", body: { mid: "x1", hello: 1 } });
const got = O1.inbox.find((m) => m.op === "notify");
t(got && got.app === "clip" && got.from === "c1" && got.n === undefined && got.body.hello === 1, "跨 App:对方同意接收时实时送达,带 app 字段,没有回执序号");
t(C1.inbox.find((m) => m.op === "sent").online === true, "跨 App:发送方得到 online:true");
const O2 = await w.connect({ dev: "o2", app: "other" });          // 没同意任何人
C1.clear(); const pendBefore = w.pending().length;
C1.send({ op: "notify", to: "o2", toApp: "other", body: { mid: "x2" } });
t(!O2.inbox.some((m) => m.op === "notify") && C1.inbox.find((m) => m.op === "sent").online === false, "跨 App:对方没同意就发不进去");
t(w.pending().length === pendBefore, "跨 App:不暂存,不占对方的暂存额度");
C1.send({ op: "notify", to: "devA", toApp: "", body: { mid: "x3" } });
t(!A2.inbox.some((m) => m.op === "notify" && m.body.mid === "x3"), "跨 App:发给老 App 那一组也发不进去(老 App 没法同意)");
A2.send({ op: "notify", to: "o1", toApp: "other", body: { mid: "x4" } });
t(!O1.inbox.some((m) => m.op === "notify" && m.body.mid === "x4"), "跨 App:老 App 发给同意了 clip 的 other 也发不进去(other 只同意 clip)");
const presBefore = J(presOf(O1));
O1.clear(); C1.send({ op: "notify", to: "o1", toApp: "other", body: { mid: "x5" } });
t(!O1.inbox.some((m) => m.op === "presence"), "跨 App 通知不改变在线名单");
C1.send({ op: "notify", to: "o1", toApp: "BAD!", body: { mid: "x6" } });
t(true, "toApp 格式不对的通知被忽略(没有抛错)");

// 请求头格式
const bad1 = await w.connect({ dev: "d9", app: "Clip" });
t(bad1.status === 400, "X-App 有大写字母:400");
const bad2 = await w.connect({ dev: "d9", app: "clip", accept: "ok,BAD" });
t(bad2.status === 400, "X-Accept 里有不合法的命名空间:400");
const bad3 = await w.connect({ dev: "d9", app: "clip", accept: "a,b,c,d,e,f,g,h,i" });
t(bad3.status === 400, "X-Accept 超过 8 个:400");
const selfAcc = await w.connect({ dev: "d10", app: "clip", accept: "clip,other" });
t(selfAcc.status === 101, "X-Accept 里列了自己:忽略,不报错");
const r400 = await w.http("GET", "/r2?prefix=clip/", { headers: { "X-App": "Clip" } });
t(r400.status === 400, "/r2 的 X-App 格式不对:400");

// R2
const put1 = await w.http("PUT", "/r2/foo/bar.bin", { body: "legacy" });
t(put1.status === 200, "R2:老 App 不带 X-App,和以前一样可以存任意对象名");
const put2 = await w.http("PUT", "/r2/foo/bar2.bin", { headers: { "X-App": "clip" }, body: "x" });
t(put2.status === 403, "R2:clip 不能存 clip/ 以外的对象");
const put3 = await w.http("PUT", "/r2/clip/snap/c1", { headers: { "X-App": "clip" }, body: "snap-data" });
t(put3.status === 200, "R2:clip 可以存 clip/ 开头的对象");
const get3 = await w.http("GET", "/r2/clip/snap/c1", { headers: { "X-App": "clip" } });
t(get3.status === 200 && get3.text === "snap-data", "R2:clip 读回自己的对象");
const get4 = await w.http("GET", "/r2/clip/snap/c1", { headers: { "X-App": "other" } });
t(get4.status === 403, "R2:other 读不了 clip/ 的对象");
const get5 = await w.http("GET", "/r2/foo/bar.bin", { headers: { "X-App": "clip" } });
t(get5.status === 403, "R2:clip 读不了老 App 的对象");
const del1 = await w.http("DELETE", "/r2/foo/bar.bin", { headers: { "X-App": "clip" } });
t(del1.status === 403 && w.bucket.m.has("foo/bar.bin"), "R2:clip 删不了老 App 的对象");
const list1 = await w.http("GET", "/r2?prefix=clip/snap/", { headers: { "X-App": "clip" } });
t(list1.status === 200 && list1.text.includes("<Key>clip/snap/c1</Key>"), "R2:clip 列出自己前缀下的对象");
const list2 = await w.http("GET", "/r2?prefix=", { headers: { "X-App": "clip" } });
t(list2.status === 403, "R2:clip 不能列出整个桶");
const list3 = await w.http("GET", "/r2?prefix=other/", { headers: { "X-App": "clip" } });
t(list3.status === 403, "R2:clip 不能列出别的命名空间");
const list4 = await w.http("GET", "/r2?prefix=clip/", {});
t(list4.status === 200, "R2:老 App 列出任意前缀,和以前一样");
const trav = await w.http("PUT", "/r2/clip/../x", { headers: { "X-App": "clip" }, body: "x" });
t(trav.status === 400 || trav.status === 403, "R2:路径里的 .. 被拒绝");

// 翻译转发(规则 9):只转发到谷歌的 translate_a/single,原文走请求体;上游用假的 fetch 代替
const upCalls = [];
let upMode = "ok";
globalThis.fetch = async (url, init) => {
  const method = (init && init.method) || "GET";
  upCalls.push({ url: String(url), method, headers: init && init.headers, body: init && init.body });
  if (upMode === "throw") throw new Error("boom");
  if (upMode === "429") return new Response("<html><style>.a{}</style><body>Our systems have detected <b>unusual</b> traffic from your computer network. IP address: 1.2.3.4</body></html>", { status: 429 });
  if (upMode === "getfail" && method === "GET") return new Response("slow down", { status: 429 });
  if (upMode === "500") return new Response("oops", { status: 500 });
  return new Response('[[["Hello","你好",null,null,1]],null,"zh-CN"]', { status: 200 });
};
const TR = { "X-App": "tr", "content-type": "application/x-www-form-urlencoded" };
const trPost = (qs, body, headers = TR) => w.http("POST", "/tr/google" + qs, { headers, body });
const BASE = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl=en&dt=t";
let tr = await trPost("?sl=zh-CN&tl=en", "q=" + encodeURIComponent("你好"));
t(tr.status === 200 && tr.text.includes("Hello"), "翻译转发:正常请求原样返回谷歌的结果");
t(tr.headers.get("cache-control") === "no-store" && tr.headers.get("content-type").startsWith("application/json"), "翻译转发:返回 no-store、不缓存");
t(upCalls.length === 1 && upCalls[0].method === "GET" && upCalls[0].url === BASE + "&q=" + encodeURIComponent("你好") && tr.headers.get("x-tr-via") === "GET", "翻译转发:短文本先用 GET(和浏览器直接访问一样),上游地址写死,只调用一次");
t(upCalls[0].body === undefined && /Chrome/.test(upCalls[0].headers["user-agent"]) && upCalls[0].headers["accept"] === "*/*", "翻译转发:GET 没有请求体,请求头像浏览器");
const weird = "a&b=c+d%e\n日本語 😀 =";
await trPost("?sl=zh-CN&tl=ja", "q=" + encodeURIComponent(weird));
t(new URL(upCalls.at(-1).url).searchParams.get("q") === weird && upCalls.at(-1).method === "GET", "翻译转发:& = + % 换行 日文 emoji 原样往返");
await trPost("?sl=auto&tl=en", "q=hi&evil=1&client=x&dt=bd");
{
  const u = new URL(upCalls.at(-1).url);
  t(u.searchParams.get("q") === "hi" && u.searchParams.get("dt") === "t" && u.searchParams.get("client") === "gtx" && !upCalls.at(-1).url.includes("evil"), "翻译转发:请求体里只取 q,别的字段不转发");
}
upMode = "getfail";
let nA = upCalls.length;
tr = await trPost("?sl=zh-CN&tl=en", "q=" + encodeURIComponent("你好"));
t(tr.status === 200 && upCalls.length === nA + 2 && upCalls[nA].method === "GET" && upCalls[nA + 1].method === "POST" && tr.headers.get("x-tr-via") === "POST", "翻译转发:GET 被谷歌拒绝 → 自动改用 POST,成功时写明是 POST");
t(upCalls[nA + 1].body === "q=" + encodeURIComponent("你好") && !upCalls[nA + 1].url.includes("q=") && upCalls[nA + 1].headers["content-type"].startsWith("application/x-www-form-urlencoded"), "翻译转发:POST 时原文在请求体里,不在网址里");
upMode = "ok";
nA = upCalls.length;
const longText = "汉".repeat(600);
tr = await trPost("?sl=zh-CN&tl=en", "q=" + encodeURIComponent(longText));
t(tr.status === 200 && upCalls.length === nA + 1 && upCalls[nA].method === "POST" && upCalls[nA].body === "q=" + encodeURIComponent(longText), "翻译转发:原文太长(网址放不下)直接用 POST,只调用一次");
const n0 = upCalls.length;
t((await w.http("POST", "/tr/google?sl=zh-CN&tl=en", { body: "q=hi" })).status === 403, "翻译转发:不带 X-App 拒绝");
t((await trPost("?sl=zh-CN&tl=en", "q=hi", { "X-App": "clip" })).status === 403, "翻译转发:别的命名空间(clip)拒绝");
t((await trPost("?sl=zh-CN&tl=en", "q=hi", { "X-App": "Tr" })).status === 400, "翻译转发:X-App 格式不对 400");
t((await w.http("GET", "/tr/google?sl=zh-CN&tl=en&q=hi", { headers: TR })).status === 405, "翻译转发:GET 拒绝(原文不许放网址)");
t((await trPost("?sl=zh$CN&tl=en", "q=hi")).status === 400 && (await trPost("?sl=zh-CN&tl=auto", "q=hi")).status === 400 && (await trPost("?sl=zh-CN", "q=hi")).status === 400, "翻译转发:语言代码格式不对 / 目标是 auto / 没有目标语言,400");
t((await trPost("?sl=zh-CN&tl=en", "other=1")).status === 400 && (await trPost("?sl=zh-CN&tl=en", "q=")).status === 400 && (await trPost("?sl=zh-CN&tl=en", "")).status === 400, "翻译转发:没有 q / q 是空的 / 没有请求体,400");
t((await trPost("?sl=zh-CN&tl=en", "q=" + "a".repeat(41 * 1024))).status === 413, "翻译转发:请求体超过 40 KB,413");
t((await w.http("POST", "/tr/google?sl=zh-CN&tl=en", { headers: TR, body: "q=hi", secretOverride: "wrong" })).status === 401, "翻译转发:口令不对 401(和别的路径一样)");
t(upCalls.length === n0, "翻译转发:上面这些被拒绝的请求,一个都没有转发给谷歌");
upMode = "429";
tr = await trPost("?sl=zh-CN&tl=en", "q=" + encodeURIComponent("你好"));
{
  const j = JSON.parse(tr.text);
  t(tr.status === 429 && j.error.includes("GET 429、POST 429") && j.detail.includes("unusual traffic") && !j.detail.includes("<") && !j.detail.includes("你好"), "翻译转发:谷歌一直拒绝 → 回 429,错误里写明每种方式的结果和谷歌返回内容的开头(去掉标签,不含原文)");
}
upMode = "500";
tr = await trPost("?sl=zh-CN&tl=en", "q=hi");
t(tr.status === 502 && JSON.parse(tr.text).error.includes("GET 500、POST 500"), "翻译转发:谷歌返回别的错误 → 回 502,错误里写明");
upMode = "throw";
tr = await trPost("?sl=zh-CN&tl=en", "q=hi");
t(tr.status === 502 && JSON.parse(tr.text).error.includes("GET 连不上、POST 连不上"), "翻译转发:连不上谷歌 → 回 502,错误里写明");
upMode = "ok";
const n1 = upCalls.length;
t((await trPost("/x?sl=zh-CN&tl=en", "q=hi")).status === 404, "翻译转发:不是 /tr/google 的路径 404");
const other = await w.http("POST", "/tr/other", { headers: TR, body: "q=hi" });
const deeper = await w.http("POST", "/tr/google/translate_a/single", { headers: TR, body: "q=hi" });
const bare = await w.http("POST", "/tr", { headers: TR, body: "q=hi" });
t(other.status === 404 && deeper.status === 404 && bare.status === 404 && upCalls.length === n1, "翻译转发:不是通用代理:别的路径一律 404,也没有转发");
const trWs = await w.connect({ dev: "trdev", app: "tr" });
t(trWs.status === 101, "命名空间 tr 也能像别的 App 一样连 /ws(规则 2),不受翻译路径影响");

// 口令
const noSecret = await w.http("GET", "/test", { secretOverride: "wrong" });
t(noSecret.status === 401, "口令不对:401(和以前一样)");
const ok = await w.http("GET", "/test");
t(ok.status === 200, "/test:和以前一样");

// 部署前就连着的老连接
const old = w.legacyConnection("oldie", true);
const lg = await w.connect({ dev: "newcomer" });
t(lastPres(lg).includes("oldie"), "部署前就连着的老连接:出现在老 App 的在线名单里");
old.clear();
lg.send({ op: "notify", to: "oldie", body: { mid: "pp1", text: "hi old" } });
t(old.inbox.some((m) => m.op === "notify" && m.n > 0), "部署前就连着的老连接:能收到老 App 的通知,而且带回执序号");
old.send({ op: "notify", to: "newcomer", body: { mid: "pp2" } });
t(lg.inbox.some((m) => m.op === "notify" && m.from === "oldie"), "部署前就连着的老连接:能发通知(设备 ID 取自标签)");
const clipSees = lastPres(C1);
t(!clipSees.includes("oldie"), "部署前就连着的老连接:不会出现在 clip 的名单里");

// 关连接时的名单
C2.close();
t(J(lastPres(C1)) === J(["c1", "d10", "same"].sort()) || lastPres(C1).includes("c1") && !lastPres(C1).includes("c2"), "clip 里一台设备断开:只有 clip 的其他连接收到新名单");
t(!A.inbox.some((m) => m.op === "presence" && m.online.includes("c2") === true), "clip 设备上下线:老 App 收不到名单更新");

console.log(fail === 0 ? `ALL PASS (${pass} 项)` : `FAILED ${fail}(通过 ${pass} 项)`);
process.exit(fail === 0 ? 0 : 1);
