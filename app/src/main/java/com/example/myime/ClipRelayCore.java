package com.example.myime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剪贴板联动走 Cloudflare Worker 的协议核心(纯 JDK,不依赖 Android 和 OkHttp,可以连真的 Worker 脚本一起测,见 tools/ClipRelayE2E.java)。
 * 真正的网络(OkHttp 的 WebSocket 和 HTTP)在 ClipRelayNet.kt,什么时候连、设置、主线程切换在 ClipSync.kt。数据模型和合并在 ClipSyncCore。
 *
 * <h3>怎么传</h3>
 * 命名空间 clip(连接和 R2 请求都带 X-App: clip,规则见 Worker 脚本开头),设备 ID 复用学习数据同步的那个。
 * <ul>
 * <li>增量:本机有变化 → 把改过的记录(ClipSyncCore.Rec,dirty)加密成一包,用 Worker 的 notify 逐台发给别的设备。
 *     消息体 {mid, clip:{s:序号, b:base64}},带 mid 就走 Worker 的回执机制:对方在线直接收,不在线 Worker 先存着(每台 200 条 / 50 MB / 7 天),
 *     对方上线时补发,处理完回 ack 才删。序号是本机每发一条 +1,别的设备据此发现"中间缺了几条"。</li>
 * <li>快照:本机窗口内的全部记录加密后存在 R2 的 clip/snap/设备ID。新设备第一次加入、或者发现缺了消息(暂存满了 / 离线超过 7 天)的设备读它追上。
 *     它同时是"都有哪些设备"的名册:列一下 clip/snap/ 就知道要发给谁。</li>
 * <li>欢迎:在线的设备发现有陌生设备上线(不在名册里),马上把自己当前的内容整份推给它(消息里 w:1)。新设备读到的快照可能比对方最近的几次改动旧
 *     (快照要等发完以后稍等一会儿才更新),这样不用等对方下次再改,新设备就是最新的。</li>
 * <li>连接:需要的时候才连(键盘弹出、本机有变化),发完发一个文本 ping,Worker 在 90~120 秒没动静后自己断开,这边不设定时器去关、也不自动重连。
 *     但上次发 ping(或刚连上)已经过了 90 秒,下一次要用时不再相信"还连着":Worker 多半已经把它关了,关闭通知可能因为手机休眠、换网络、
 *     路由器断了空闲连接而没送到,继续用会写进一条死连接、等到超时才发现。这时直接关掉重连(没有定时器、没有心跳,不增加耗电)。
 *     连接开着的时候别的设备的变化直接推过来(秒到)。</li>
 * </ul>
 * 所有工作在一个单线程执行器上顺序做(状态不用加锁);网络回调线程只把消息丢进来,收到发送确认(sent / error)直接在回调线程里处理,
 * 因为发送任务正占着执行器等它。
 */
public final class ClipRelayCore {
    public static final String NAMESPACE = "clip";
    public static final String SNAP_PREFIX = "clip/snap/";
    /** 一包增量里文字的总字数上限(按最坏 3 字节 / 字、gzip 不压缩、base64 估算,一条通知不超过 Worker 的 1.1 MB 上限) */
    static final int CHUNK_TEXT_CHARS = 120_000;
    static final int MAX_B64 = 1_000_000;
    private static final Pattern DEV = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern KEY = Pattern.compile("<Key>([^<]*)</Key>");

    private ClipRelayCore() {
    }

    public static boolean validDev(String s) {
        return s != null && DEV.matcher(s).matches();
    }

    /** 解析 Worker 的 /r2 列表(S3 ListObjectsV2 的 XML 子集),取出所有对象名。 */
    public static List<String> parseListKeys(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = KEY.matcher(xml);
        while (m.find()) {
            out.add(m.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"));
        }
        return out;
    }

    // ------------------------------------------------------------ 接口

    public interface Listener {
        void onOpen();

        void onText(String text);

        /** 断开或没连上,why 是原因(给人看)。 */
        void onClosed(String why);
    }

    /** 网络层:WebSocket 连 Worker 的 /ws,HTTP 存取 R2。Android 上是 OkHttp,测试里是 JDK 自带的。 */
    public interface Transport {
        /** 非阻塞地开始连接;连上调 listener.onOpen,没连上或以后断开调 onClosed。 */
        void connect(Listener l) throws IOException;

        boolean isOpen();

        boolean send(String text);

        void close();

        void r2Put(String key, byte[] data) throws IOException;

        /** 不存在返回 null。 */
        byte[] r2Get(String key) throws IOException;

        List<String> r2List(String prefix) throws IOException;

        void r2Delete(String key) throws IOException;
    }

    /** 同步状态的存取(Android 上放在设备专属的设置里,不进备份)。 */
    public interface State {
        long mySeq();

        void setMySeq(long seq);

        /** 已经收到这台设备的哪个序号;没收过返回 -1。 */
        long last(String dev);

        void setLast(String dev, long seq);

        Set<String> roster();

        long rosterAt();

        void setRoster(Set<String> roster, long at);

        /** 本机的快照比本机内容旧(有变化后还没上传)。 */
        boolean snapDirty();

        void setSnapDirty(boolean v);

        /** 这台设备做过"第一次加入"了(读过别人的快照、传过自己的)。还原备份、换环境后会被清掉重来。 */
        boolean inited();

        void setInited(boolean v);

        int badCount();

        void addBad();
    }

    public interface Sink {
        void ok(String msg);

        void fail(String why);
    }

    public static final class Config {
        public long flushDelayMs = 500;        // 本机有变化后,合并这一小段时间内的多次变化再发
        public long minGapMs = 5_000;          // 两次发送至少隔这么久(连续操作合成一两次)
        public long snapDelayMs = 10_000;      // 发完以后等这么久再更新 R2 里的快照(一阵操作只更新一次)
        public long rosterTtlMs = 3_600_000;   // 设备名册缓存多久
        public long replyTimeoutMs = 8_000;    // 等 Worker 确认"收到了"的时间
        public long connectTimeoutMs = 15_000;
        /** 连着的连接超过这么久没发过 ping(也没重新连过),就当 Worker 已经把它关了,下次用之前重连。
         *  Worker 的 HEARTBEAT_TIMEOUT_MS 也是 90 秒,而且只认 ping 和连上的那一刻(notify、ack 不重置它),所以这里也只算这两样;
         *  不能比 Worker 的时限长,自测会对照 tools/worker/hulian-worker.js 检查。 */
        public long staleAfterMs = 90_000;
    }

    // ------------------------------------------------------------ 很小的 JSON 解析(Worker 的消息都很简单,不用引入库)

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        /** 解析一个 JSON 对象;不是对象或格式不对返回 null。值类型:Map / List / String / Long / Double / Boolean / null。 */
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String text) {
            try {
                Json j = new Json(text);
                j.ws();
                Object o = j.value();
                j.ws();
                if (j.i != j.s.length() || !(o instanceof Map)) return null;
                return (Map<String, Object>) o;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            if (i >= s.length()) throw new IllegalStateException();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw new IllegalStateException();
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalStateException();
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalStateException();
            }
        }

        private String string() {
            if (s.charAt(i) != '"') throw new IllegalStateException();
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        b.append(e);
                }
            }
        }

        private Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalStateException();
            String t = s.substring(st, i);
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
            return Double.parseDouble(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof String ? (String) o : null;
    }

    private static long num(Map<String, Object> m, String k, long def) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return (long) (double) (Double) o;
        return def;
    }

    // ------------------------------------------------------------ 引擎

    /** 一批发出去的通知在等 Worker 确认。 */
    private static final class Batch {
        final int expected;
        final CountDownLatch latch;
        final AtomicInteger replied = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        volatile boolean closed;

        Batch(int expected) {
            this.expected = expected;
            this.latch = new CountDownLatch(expected);
        }
    }

    public static final class Engine {
        private final Transport t;
        private final ClipSyncCore.Store store;
        private final State st;
        private final String self;
        private final byte[] master;
        private final byte[] salt;
        private final LongSupplier clock;
        private final ScheduledExecutorService exec;
        private final Sink sink;
        private final Config cfg;

        private final ConcurrentHashMap<String, Batch> inflight = new ConcurrentHashMap<>();
        private volatile CountDownLatch openLatch = new CountDownLatch(0);
        private volatile String closedWhy;
        private final Set<String> online = new HashSet<>();   // 只在执行器线程里用
        private final Set<String> seen = new HashSet<>();     // 只在执行器线程里用
        private boolean flushScheduled;                       // 受 this 保护
        private boolean snapScheduled;                        // 受 this 保护
        private final java.util.concurrent.atomic.AtomicBoolean shownQueued = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean dirtyHint = true;            // 本机可能有还没发出去的改动(进程刚启动时也当作有,让第一次对一次账)
        private long lastFlushAt;                             // 只在执行器线程里用
        private volatile long keepAt;                         // 最近一次连上、或发出 ping 的时刻(clock);Worker 的 90 秒空闲计时就是从这两样算起的。0 = 还没连过

        private final Listener listener = new Listener() {
            @Override
            public void onOpen() {
                keepAt = clock.getAsLong();
                closedWhy = null;
                openLatch.countDown();
            }

            @Override
            public void onText(String text) {
                handleText(text);
            }

            @Override
            public void onClosed(String why) {
                closedWhy = why == null ? "连接断开" : why;
                openLatch.countDown();
                for (Batch b : inflight.values()) {
                    b.closed = true;
                    while (b.latch.getCount() > 0) b.latch.countDown();
                }
                try {
                    exec.execute(new Runnable() {
                        @Override
                        public void run() {
                            online.clear();
                        }
                    });
                } catch (RuntimeException ignored) {
                    // 执行器已经关了
                }
            }
        };

        /** exec 必须是单线程的(同一时刻只有一个任务在跑)。 */
        public Engine(Transport t, ClipSyncCore.Store store, State st, String selfDev, byte[] master, byte[] salt,
                      LongSupplier clock, ScheduledExecutorService exec, Sink sink, Config cfg) {
            this.t = t;
            this.store = store;
            this.st = st;
            this.self = selfDev;
            this.master = master;
            this.salt = salt;
            this.clock = clock;
            this.exec = exec;
            this.sink = sink;
            this.cfg = cfg;
        }

        /** 连着、而且没过 Worker 的空闲时限(过了的话 Worker 多半已经关了它,界面上也该显示没连着)。 */
        public boolean isConnected() {
            return t.isOpen() && !stale();
        }

        /** 上次连上 / 发 ping 已经超过 staleAfterMs。keepAt 是 0(还没记录过)时当作没过期,不乱重连。 */
        private boolean stale() {
            long k = keepAt;
            return k > 0 && clock.getAsLong() - k > cfg.staleAfterMs;
        }

        public void close() {
            t.close();
        }

        // ---- 入口(任何线程都可以调,工作都丢给执行器)

        /** 键盘弹出:没连就连上(Worker 会把暂存的补发过来),必要时做第一次加入,发还没发出去的改动,最后发一个 ping 让连接多留一会。 */
        public void onShown() {
            if (!shownQueued.compareAndSet(false, true)) return;
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    shownQueued.set(false);
                    session(false, false);
                }
            });
        }

        /** 本机有不紧急的变化(比如只是点用后挪了顺序):只记一笔,等键盘收起(onHidden)或下次键盘弹出时再一起发,不为它单独联网。 */
        public void markDirty() {
            dirtyHint = true;
        }

        /** 键盘收起:本机有还没发出去的改动就发(没连就连上)。 */
        public void onHidden() {
            if (!dirtyHint) return;
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    session(true, false);
                }
            });
        }

        /** 本机有变化:合并一小段时间内的多次变化再发;两次发送至少隔 minGapMs。 */
        public void onLocalChange() {
            dirtyHint = true;
            synchronized (this) {
                if (flushScheduled) return;
                flushScheduled = true;
            }
            long wait = Math.max(cfg.flushDelayMs, lastFlushAt + cfg.minGapMs - clock.getAsLong());
            exec.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (Engine.this) {
                        flushScheduled = false;
                    }
                    session(true, false);
                }
            }, wait, TimeUnit.MILLISECONDS);
        }

        /** 立即同步(备份页的按钮):重新读一遍别的设备的快照、发出没发的改动、更新自己的快照。 */
        public Future<Boolean> syncNow() {
            return exec.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return session(true, true);
                }
            });
        }

        /** 删掉这台设备在 R2 里的快照(不影响别的设备和本机的剪贴板);下次联动当作第一次加入。 */
        public Future<Boolean> deleteOwnCloud() {
            return exec.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    try {
                        t.r2Delete(SNAP_PREFIX + self);
                        st.setInited(false);
                        st.setSnapDirty(false);
                        sink.ok("已删除云端本机的快照");
                        return true;
                    } catch (IOException e) {
                        sink.fail(why(e));
                        return false;
                    }
                }
            });
        }

        // ---- 一次会话

        private boolean session(boolean flush, boolean force) {
            try {
                ensureConnected();
                if (force || !st.inited()) initialJoin();
                if (flush || dirtyHint) flushDirty();
                if (st.snapDirty()) scheduleSnapshot();
                if (t.send("ping")) keepAt = clock.getAsLong();   // 让 Worker 的 90 秒计时从现在重新算(发出去了才记)
                sink.ok(force ? "已同步" : "已连接");
                return true;
            } catch (IOException e) {
                sink.fail(why(e));
                t.close();
                return false;
            } catch (RuntimeException e) {
                sink.fail(why(e));
                t.close();
                return false;
            }
        }

        private void ensureConnected() throws IOException {
            if (t.isOpen()) {
                if (!stale()) return;
                // 标志是开着,但已经过了 Worker 的空闲时限:不信它,先关掉再重连(关的时候会回调 onClosed,清掉旧的在线名单)
                t.close();
            }
            openLatch = new CountDownLatch(1);
            closedWhy = null;
            t.connect(listener);
            try {
                if (!openLatch.await(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                    t.close();
                    throw new IOException("连接 Worker 超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("被中断");
            }
            if (!t.isOpen()) throw new IOException(closedWhy == null ? "连接 Worker 失败" : closedWhy);
        }

        /** 第一次加入:读别的设备的快照合并进来,传自己的快照(别人才知道有这台设备)。 */
        private void initialJoin() throws IOException {
            Set<String> peers = refreshRoster(true);
            final List<ClipSyncCore.Rec> incoming = new ArrayList<>();
            int read = 0;
            for (String dev : peers) {
                byte[] b = t.r2Get(SNAP_PREFIX + dev);
                if (b == null) continue;
                try {
                    ClipSyncCore.Decoded d = ClipSyncCore.decode(b, master);
                    incoming.addAll(d.recs);
                    st.setLast(dev, Math.max(st.last(dev), d.seq));
                    read++;
                } catch (IOException e) {
                    st.addBad();   // 解不开(别的构建、损坏):跳过
                }
            }
            if (!incoming.isEmpty()) {
                final long now = clock.getAsLong();
                store.atomically(new ClipSyncCore.Op<Integer>() {
                    @Override
                    public Integer run(ClipSyncCore.Model m) {
                        return m.merge(incoming, now);
                    }
                });
            }
            dirtyHint = true;
            uploadSnapshot();
            st.setInited(true);
        }

        // ---- 发送

        private Set<String> refreshRoster(boolean force) throws IOException {
            long now = clock.getAsLong();
            if (!force && st.rosterAt() > 0 && now - st.rosterAt() < cfg.rosterTtlMs) return st.roster();
            Set<String> r = new TreeSet<>();
            for (String key : t.r2List(SNAP_PREFIX)) {
                if (!key.startsWith(SNAP_PREFIX)) continue;
                String dev = key.substring(SNAP_PREFIX.length());
                if (validDev(dev) && !dev.equals(self)) r.add(dev);
            }
            st.setRoster(r, now);
            return r;
        }

        private Set<String> peersForSend() {
            Set<String> peers = new TreeSet<>();
            try {
                peers.addAll(refreshRoster(false));
            } catch (IOException e) {
                peers.addAll(st.roster());   // 名册拉不下来:用上次的,加上现在在线的
            }
            peers.addAll(online);
            peers.addAll(seen);
            peers.remove(self);
            return peers;
        }

        private void flushDirty() throws IOException {
            final List<ClipSyncCore.Rec> dirty = store.atomically(new ClipSyncCore.Op<List<ClipSyncCore.Rec>>() {
                @Override
                public List<ClipSyncCore.Rec> run(ClipSyncCore.Model m) {
                    return m.takeDirty();
                }
            });
            if (dirty.isEmpty()) {
                dirtyHint = false;
                return;
            }
            Set<String> peers = peersForSend();
            int sent = 0;
            if (!peers.isEmpty()) {
                List<List<ClipSyncCore.Rec>> groups = groupBySize(dirty);
                Batch batch = new Batch(groups.size() * peers.size());
                List<String> mids = new ArrayList<>();
                long seq = st.mySeq();
                for (List<ClipSyncCore.Rec> g : groups) {
                    seq++;
                    byte[] enc = ClipSyncCore.encode(master, salt, false, self, seq, g);
                    String b64 = Base64.getEncoder().encodeToString(enc);
                    if (b64.length() > MAX_B64) throw new IOException("单次要发的内容太大");
                    for (String peer : peers) {
                        String mid = self + "." + seq + "." + peer;
                        inflight.put(mid, batch);
                        mids.add(mid);
                        String json = "{\"op\":\"notify\",\"to\":\"" + peer + "\",\"body\":{\"mid\":\"" + mid + "\",\"clip\":{\"s\":" + seq + ",\"b\":\"" + b64 + "\"}}}";
                        if (!t.send(json)) {
                            for (String m2 : mids) inflight.remove(m2);
                            throw new IOException("连接已断开,没发出去");
                        }
                    }
                }
                st.setMySeq(seq);
                boolean done;
                try {
                    done = batch.latch.await(cfg.replyTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done = false;
                } finally {
                    for (String m2 : mids) inflight.remove(m2);
                }
                if (!done || batch.closed || batch.replied.get() < batch.expected) {
                    throw new IOException("发出去的改动没有得到 Worker 的确认,稍后重试");
                }
                sent = peers.size();
            }
            store.atomically(new ClipSyncCore.Op<Object>() {
                @Override
                public Object run(ClipSyncCore.Model m) {
                    m.clearDirty(dirty);
                    return null;
                }
            });
            st.setSnapDirty(true);
            dirtyHint = false;
            lastFlushAt = clock.getAsLong();
            sink.ok(sent > 0 ? "已发送 " + dirty.size() + " 条改动给 " + sent + " 台设备" : "已记录改动(还没有别的设备)");
        }

        private List<List<ClipSyncCore.Rec>> groupBySize(List<ClipSyncCore.Rec> recs) {
            List<List<ClipSyncCore.Rec>> out = new ArrayList<>();
            List<ClipSyncCore.Rec> cur = new ArrayList<>();
            int chars = 0;
            for (ClipSyncCore.Rec r : recs) {
                int n = r.text == null ? 0 : r.text.length();
                if (!cur.isEmpty() && chars + n > CHUNK_TEXT_CHARS) {
                    out.add(cur);
                    cur = new ArrayList<>();
                    chars = 0;
                }
                cur.add(r);
                chars += n;
            }
            if (!cur.isEmpty()) out.add(cur);
            return out;
        }

        private final java.util.concurrent.atomic.AtomicLong midSeq = new java.util.concurrent.atomic.AtomicLong();

        /** 把本机当前窗口内的内容整份推给刚上线的陌生设备(不等回复:对方没收到的话,下次发现缺号会读快照)。 */
        private void welcome(Set<String> devs) {
            try {
                final long now = clock.getAsLong();
                List<ClipSyncCore.Rec> win = store.atomically(new ClipSyncCore.Op<List<ClipSyncCore.Rec>>() {
                    @Override
                    public List<ClipSyncCore.Rec> run(ClipSyncCore.Model m) {
                        return m.window(ClipSyncCore.WINDOW, now);
                    }
                });
                if (win.isEmpty()) return;
                long seq = st.mySeq();
                for (List<ClipSyncCore.Rec> g : groupBySize(win)) {
                    byte[] enc = ClipSyncCore.encode(master, salt, true, self, seq, g);
                    String b64 = Base64.getEncoder().encodeToString(enc);
                    if (b64.length() > MAX_B64) continue;
                    for (String dev : devs) {
                        String mid = self + ".w" + seq + "." + midSeq.incrementAndGet();
                        t.send("{\"op\":\"notify\",\"to\":\"" + dev + "\",\"body\":{\"mid\":\"" + mid + "\",\"clip\":{\"s\":" + seq + ",\"w\":1,\"b\":\"" + b64 + "\"}}}");
                    }
                }
            } catch (IOException e) {
                sink.fail(why(e));
            } catch (RuntimeException e) {
                sink.fail(why(e));
            }
        }

        // ---- 快照

        private void scheduleSnapshot() {
            synchronized (this) {
                if (snapScheduled) return;
                snapScheduled = true;
            }
            exec.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (Engine.this) {
                        snapScheduled = false;
                    }
                    if (!st.snapDirty()) return;
                    try {
                        uploadSnapshot();
                    } catch (IOException e) {
                        sink.fail(why(e));   // 下次发送或键盘弹出时会再排一次
                    }
                }
            }, cfg.snapDelayMs, TimeUnit.MILLISECONDS);
        }

        private void uploadSnapshot() throws IOException {
            final long now = clock.getAsLong();
            List<ClipSyncCore.Rec> win = store.atomically(new ClipSyncCore.Op<List<ClipSyncCore.Rec>>() {
                @Override
                public List<ClipSyncCore.Rec> run(ClipSyncCore.Model m) {
                    return m.window(ClipSyncCore.WINDOW, now);
                }
            });
            byte[] enc = ClipSyncCore.encode(master, salt, true, self, st.mySeq(), win);
            t.r2Put(SNAP_PREFIX + self, enc);
            st.setSnapDirty(false);
        }

        // ---- 接收

        private void handleText(String text) {
            if ("pong".equals(text)) return;
            final Map<String, Object> o = Json.parseObject(text);
            if (o == null) return;
            String op = str(o, "op");
            if ("sent".equals(op)) {
                Batch b = inflight.get(str(o, "mid"));
                if (b != null) {
                    b.replied.incrementAndGet();
                    b.latch.countDown();
                }
                return;
            }
            if ("error".equals(op)) {
                // 对方暂存满了之类:Worker 没收这一条。当作"已处理":对方上线后发现缺了消息会自己读快照追上
                Batch b = inflight.get(str(o, "mid"));
                if (b != null) {
                    b.rejected.incrementAndGet();
                    b.replied.incrementAndGet();
                    b.latch.countDown();
                }
                return;
            }
            try {
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        process(o);
                    }
                });
            } catch (RuntimeException ignored) {
                // 执行器已经关了
            }
        }

        private void process(Map<String, Object> o) {
            String op = str(o, "op");
            if ("presence".equals(op)) {
                online.clear();
                Object arr = o.get("online");
                if (arr instanceof List) {
                    for (Object x : (List<?>) arr) {
                        if (x instanceof String && validDev((String) x) && !x.equals(self)) online.add((String) x);
                    }
                }
                Set<String> newcomers = new TreeSet<>();
                Set<String> known = st.roster();
                for (String dev : online) if (!seen.contains(dev) && !known.contains(dev)) newcomers.add(dev);
                seen.addAll(online);
                if (!newcomers.isEmpty()) {
                    Set<String> r = new TreeSet<>(known);
                    r.addAll(newcomers);
                    st.setRoster(r, st.rosterAt());   // 记住了,以后不再当陌生设备
                    welcome(newcomers);
                }
                return;
            }
            if (!"notify".equals(op)) return;
            Map<String, Object> body = map(o.get("body"));
            Map<String, Object> clip = map(body == null ? null : body.get("clip"));
            long n = num(o, "n", 0);
            String from = str(o, "from");
            if (clip == null || from == null || !validDev(from) || from.equals(self)) {
                ack(n);   // 不是剪贴板联动的消息:收到了就回执,免得一直补发
                return;
            }
            try {
                receive(from, n, clip);
            } catch (IOException e) {
                sink.fail(why(e));   // 没回执:Worker 下次连上会再补发
            } catch (RuntimeException e) {
                sink.fail(why(e));
            }
        }

        private void receive(String from, long n, Map<String, Object> clip) throws IOException {
            long s = num(clip, "s", -1);
            String b64 = str(clip, "b");
            if (s < 0 || b64 == null) {
                ack(n);
                return;
            }
            seen.add(from);
            if (num(clip, "w", 0) == 1) {
                // 对方的完整内容(欢迎):直接合并(合并是幂等的),并记下对方这时的序号,之后的增量接着往下
                final List<ClipSyncCore.Rec> all;
                try {
                    all = ClipSyncCore.decode(Base64.getDecoder().decode(b64), master).recs;
                } catch (IllegalArgumentException | IOException e) {
                    st.addBad();   // 解不开:回执掉,不然会一直补发
                    ack(n);
                    return;
                }
                final long now = clock.getAsLong();
                int changed = store.atomically(new ClipSyncCore.Op<Integer>() {
                    @Override
                    public Integer run(ClipSyncCore.Model m) {
                        return m.merge(all, now);
                    }
                });
                st.setLast(from, Math.max(st.last(from), s));
                ack(n);
                if (changed > 0) sink.ok("收到 " + changed + " 条(对方的完整内容)");
                return;
            }
            long last = st.last(from);
            if (s <= last) {   // 重复(重连后补发的、已经处理过的)
                ack(n);
                return;
            }
            ClipSyncCore.Decoded d;
            try {
                d = ClipSyncCore.decode(Base64.getDecoder().decode(b64), master);
            } catch (IllegalArgumentException | IOException e) {
                st.addBad();   // 解不开(别的构建、损坏):回执掉,不然会一直补发
                ack(n);
                return;
            }
            final List<ClipSyncCore.Rec> recs = new ArrayList<>(d.recs);
            boolean gap = last < 0 ? s != 1 : s != last + 1;
            if (gap) {
                // 中间缺了几条(这台设备离线太久 / 暂存满了 / 刚加入):读发送方的快照追上
                byte[] sb = t.r2Get(SNAP_PREFIX + from);   // 网络出错:抛出去,不回执,Worker 下次连上会再补发
                if (sb != null) {
                    try {
                        ClipSyncCore.Decoded sd = ClipSyncCore.decode(sb, master);
                        recs.addAll(0, sd.recs);   // 快照在前,这条增量在后(合并按修改时间,顺序无所谓)
                        last = Math.max(last, sd.seq);
                    } catch (IOException e) {
                        st.addBad();   // 快照解不开:只用这一条增量
                    }
                }
            }
            final long now = clock.getAsLong();
            int changed = store.atomically(new ClipSyncCore.Op<Integer>() {
                @Override
                public Integer run(ClipSyncCore.Model m) {
                    return m.merge(recs, now);
                }
            });
            st.setLast(from, Math.max(last, s));
            ack(n);
            if (changed > 0) sink.ok("收到 " + changed + " 条更新");
        }

        private void ack(long n) {
            if (n > 0) t.send("{\"op\":\"ack\",\"n\":" + n + "}");
        }

        private static String why(Exception e) {
            String m = e.getMessage();
            String s = m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m.split("\n")[0];
            return s.length() > 120 ? s.substring(0, 120) : s;
        }
    }
}
