// 剪贴板联动(Worker 版)的完整流程自测:几台模拟的设备,通过 tools/worker/server.mjs(用真正的 Worker 脚本、本机起的真 WebSocket / HTTP 服务)
// 互相同步。网络层用 JDK 自带的 WebSocket / HttpClient(App 里是 OkHttp,见 ClipRelayNet.kt),协议和合并用的是 App 里同一份 ClipRelayCore / ClipSyncCore。
// 在工程根目录执行(需要 JDK 17+ 和 node 22+):
//   (node --experimental-sqlite --no-warnings tools/worker/server.mjs tools/worker/hulian-worker.js 18093 &)
//   javac -encoding UTF-8 -d /tmp/crE app/src/main/java/com/example/myime/{ClipRelayCore,ClipSyncCore,BackupCrypto}.java tools/ClipRelayE2E.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/crE ClipRelayE2E 18093
// 覆盖:第一次加入(读别的设备的快照、传自己的)、新设备上线时在线的设备直接把当前内容推给它、连着的时候改动秒到且不去轮询 R2、删除 / 上锁 / 恢复 / 挪顺序联动、
//       对方离线 → Worker 暂存 → 上线补发 → 回执后不再重复、暂存被清掉造成缺号 → 读快照追上、新设备加入后继续实时同步、
//       大批量内容分包、2 万字和特殊字符、密钥不一致的设备(跳过、回执掉、不污染别人)、断网后恢复、删除云端本机快照、最后几台完全一致。
import com.example.myime.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ClipRelayE2E {
    static int fails = 0, passes = 0;
    static final String SECRET = "s3cret";

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passes++; else fails++;
    }

    static List<String> L(String... a) { return new ArrayList<>(Arrays.asList(a)); }

    interface Cond { boolean ok() throws Exception; }

    static boolean waitFor(Cond c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try { if (c.ok()) return true; } catch (Exception ignored) { }
            try { Thread.sleep(25); } catch (InterruptedException e) { return false; }
        }
        try { return c.ok(); } catch (Exception e) { return false; }
    }

    // ---------------------------------------------------------------- 网络层(JDK 自带)
    static final class Net implements ClipRelayCore.Transport {
        volatile String hostPort;
        final String dev;
        final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();   // 不用 h2c 升级(会被当成 WebSocket 升级请求)
        volatile WebSocket ws;
        volatile boolean open;
        volatile ClipRelayCore.Listener lst;
        volatile int gen;
        final AtomicInteger r2Calls = new AtomicInteger(), connects = new AtomicInteger();

        Net(String hostPort, String dev) { this.hostPort = hostPort; this.dev = dev; }

        public void connect(ClipRelayCore.Listener l) {
            lst = l;
            connects.incrementAndGet();
            final int g = ++gen;
            http.newWebSocketBuilder().header("X-Relay-Secret", SECRET).header("X-Device", dev).header("X-App", "clip").header("X-Ack", "1")
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://" + hostPort + "/ws"), new WebSocket.Listener() {
                    final StringBuilder sb = new StringBuilder();
                    public void onOpen(WebSocket w) { if (g != gen) { w.abort(); return; } ws = w; open = true; w.request(1); l.onOpen(); }
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        sb.append(data);
                        if (last) { String s = sb.toString(); sb.setLength(0); if (g == gen) l.onText(s); }
                        w.request(1);
                        return null;
                    }
                    public CompletionStage<?> onClose(WebSocket w, int code, String reason) { if (g == gen) { open = false; l.onClosed("服务器关闭了连接 (" + code + ")"); } return null; }
                    public void onError(WebSocket w, Throwable e) { if (g == gen) { open = false; l.onClosed("连接出错:" + e); } }
                }).exceptionally(e -> { if (g == gen) { open = false; l.onClosed("连不上:" + e); } return null; });
        }

        public boolean isOpen() { WebSocket w = ws; return open && w != null && !w.isOutputClosed() && !w.isInputClosed(); }

        public boolean send(String text) {
            WebSocket w = ws;
            if (w == null || !open) return false;
            try { synchronized (this) { w.sendText(text, true).get(5, TimeUnit.SECONDS); } return true; } catch (Exception e) { return false; }
        }

        public void close() {
            WebSocket w = ws;
            if (w != null) w.abort();
            ClipRelayCore.Listener l = lst;
            if (open) { open = false; if (l != null) l.onClosed("本机关闭"); }
        }

        HttpRequest.Builder rb(String path) {
            return HttpRequest.newBuilder(URI.create("http://" + hostPort + path)).header("X-Relay-Secret", SECRET).header("X-App", "clip").timeout(Duration.ofSeconds(10));
        }

        <T> HttpResponse<T> call(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException {
            r2Calls.incrementAndGet();
            try { return http.send(r, h); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("被中断"); }
        }

        public void r2Put(String key, byte[] data) throws IOException {
            HttpResponse<String> r = call(rb("/r2/" + key).PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        }

        public byte[] r2Get(String key) throws IOException {
            HttpResponse<byte[]> r = call(rb("/r2/" + key).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 404) return null;
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            return r.body();
        }

        public List<String> r2List(String prefix) throws IOException {
            HttpResponse<String> r = call(rb("/r2?prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            return ClipRelayCore.parseListKeys(r.body());
        }

        public void r2Delete(String key) throws IOException {
            HttpResponse<String> r = call(rb("/r2/" + key).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 204 && r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        }
    }

    // ---------------------------------------------------------------- 一台模拟的设备
    static final class Phone implements ClipSyncCore.Host, ClipRelayCore.State, ClipRelayCore.Sink {
        final String id;
        final Net net;
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        final ClipRelayCore.Engine engine;
        final ClipSyncCore.Session session;
        List<String> clips = new ArrayList<>();
        Set<String> locked = new LinkedHashSet<>();
        List<String> trash = new ArrayList<>();
        // 同步状态(内存)
        long mySeq = 0;
        final Map<String, Long> last = new ConcurrentHashMap<>();
        Set<String> roster = new TreeSet<>();
        long rosterAt = 0;
        volatile boolean snapDirty, inited;
        final AtomicInteger bad = new AtomicInteger();
        final List<String> okMsgs = new CopyOnWriteArrayList<>();
        volatile String lastFail;

        Phone(String id, String hostPort, byte[] master) {
            this.id = id;
            this.net = new Net(hostPort, id);
            this.session = new ClipSyncCore.Session(new ClipSyncCore.Model(), this, 500, 300_000, 10, System::currentTimeMillis);
            ClipSyncCore.Store store = new ClipSyncCore.Store() {
                public <T> T atomically(ClipSyncCore.Op<T> op) throws IOException {
                    synchronized (Phone.this) { return session.atomically(op); }
                }
            };
            ClipRelayCore.Config cfg = new ClipRelayCore.Config();
            cfg.flushDelayMs = 30; cfg.minGapMs = 100; cfg.snapDelayMs = 250; cfg.replyTimeoutMs = 3000; cfg.connectTimeoutMs = 5000;
            this.engine = new ClipRelayCore.Engine(net, store, this, id, master, new byte[16], System::currentTimeMillis, exec, this, cfg);
        }

        // Host(在 synchronized 里被 Session 调用)
        public ClipSyncCore.Lists lists() { return new ClipSyncCore.Lists(new ArrayList<>(clips), new LinkedHashSet<>(locked), new ArrayList<>(trash)); }
        public void apply(ClipSyncCore.Lists l) { clips = new ArrayList<>(l.clips); locked = new LinkedHashSet<>(l.locked); trash = new ArrayList<>(l.trash); }
        // State
        public long mySeq() { return mySeq; }
        public void setMySeq(long s) { mySeq = s; }
        public long last(String d) { Long v = last.get(d); return v == null ? -1 : v; }
        public void setLast(String d, long s) { last.put(d, s); }
        public Set<String> roster() { return new TreeSet<>(roster); }
        public long rosterAt() { return rosterAt; }
        public void setRoster(Set<String> r, long at) { roster = new TreeSet<>(r); rosterAt = at; }
        public boolean snapDirty() { return snapDirty; }
        public void setSnapDirty(boolean v) { snapDirty = v; }
        public boolean inited() { return inited; }
        public void setInited(boolean v) { inited = v; }
        public int badCount() { return bad.get(); }
        public void addBad() { bad.incrementAndGet(); }
        // Sink
        public void ok(String m) { okMsgs.add(m); }
        public void fail(String w) { lastFail = w; }

        // 用户操作(和 MyImeService 里的一致)
        synchronized void copy(String t) { if (!clips.isEmpty() && clips.get(0).equals(t)) return; clips.remove(t); clips.add(0, t); }
        synchronized void paste(String t) { if (clips.remove(t)) clips.add(0, t); }
        synchronized void delete(String t) { if (locked.contains(t)) return; if (clips.remove(t)) { trash.remove(t); trash.add(0, t); while (trash.size() > 10) trash.remove(trash.size() - 1); } }
        synchronized void lock(String t) { if (clips.contains(t)) locked.add(t); }
        synchronized void restore(String t) { if (trash.remove(t)) { clips.remove(t); clips.add(0, t); } }
        void changed() { engine.onLocalChange(); }
        synchronized List<String> clipsCopy() { return new ArrayList<>(clips); }
        synchronized Set<String> lockedCopy() { return new LinkedHashSet<>(locked); }
        synchronized List<String> trashCopy() { return new ArrayList<>(trash); }
        boolean sync() throws Exception { return engine.syncNow().get(40, TimeUnit.SECONDS); }
        synchronized String state() { return clips + "|" + locked + "|" + trash; }
    }

    static boolean same(Phone a, Phone b) { return a.state().equals(b.state()); }

    static int pending(String hostPort, String key, boolean clear) throws Exception {
        HttpClient h = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://" + hostPort + "/__test/pending?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)));
        HttpResponse<String> r = h.send((clear ? rb.DELETE() : rb.GET()).build(), HttpResponse.BodyHandlers.ofString());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"n\":(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    public static void main(String[] args) throws Exception {
        final String hp = "127.0.0.1:" + args[0];
        byte[] master = BackupCrypto.deriveMaster("same-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);
        byte[] other = BackupCrypto.deriveMaster("another-build".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);

        Phone A = new Phone("phoneA", hp, master), B = new Phone("phoneB", hp, master), C = new Phone("phoneC", hp, master);

        // ---- 1. 第一台设备:第一次加入,传自己的快照
        A.copy("one"); A.copy("two"); A.copy("three");
        boolean aOk = A.sync();
        check(aOk, "A:第一次同步成功" + (aOk ? "" : ":" + A.lastFail));
        check(A.net.r2List(ClipRelayCore.SNAP_PREFIX).contains("clip/snap/phoneA"), "A:R2 里有了自己的快照(别的设备靠它认识 A)");
        check(A.inited && A.net.isOpen(), "A:已加入,连接开着");

        // ---- 2. 第二台设备加入:读 A 的快照
        check(B.sync(), "B:第一次同步成功");
        check(B.clipsCopy().equals(L("three", "two", "one")), "B:加入后拿到 A 的完整剪贴板,顺序一样");
        check(B.net.r2List(ClipRelayCore.SNAP_PREFIX).containsAll(L("clip/snap/phoneA", "clip/snap/phoneB")), "B:R2 里有了 B 的快照");

        // ---- 3. 连着的时候:新复制秒到,不轮询
        int bR2 = B.net.r2Calls.get(), bConn = B.net.connects.get();
        A.copy("four"); A.changed();
        check(waitFor(() -> B.clipsCopy().equals(L("four", "three", "two", "one")), 5000), "实时:A 复制,B 几乎立刻收到,在最前");
        check(B.net.r2Calls.get() == bR2 && B.net.connects.get() == bConn, "实时:B 接收时没有去访问 R2、没有重连");
        check(waitFor(() -> !A.snapDirty, 5000), "A:发完以后稍等一会儿更新了自己的快照");
        Thread.sleep(600);
        int bR2b = B.net.r2Calls.get(), aR2b = A.net.r2Calls.get();
        Thread.sleep(1200);
        check(B.net.r2Calls.get() == bR2b && A.net.r2Calls.get() == aR2b, "空闲时:A、B 都没有任何 R2 请求(不轮询)");

        // ---- 4. 删除 / 上锁 / 恢复 / 挪顺序联动
        B.delete("two"); B.changed();
        check(waitFor(() -> A.trashCopy().contains("two") && !A.clipsCopy().contains("two"), 5000), "删除:B 删,A 也进了回收箱");
        A.lock("one"); A.changed();
        check(waitFor(() -> B.lockedCopy().contains("one"), 5000), "上锁:A 上锁,B 也上了锁");
        B.restore("two"); B.changed();
        check(waitFor(() -> A.clipsCopy().get(0).equals("two") && A.trashCopy().isEmpty(), 5000), "恢复:B 从回收箱恢复,A 也恢复,放在最前");
        A.paste("three"); A.changed();
        check(waitFor(() -> B.clipsCopy().get(0).equals("three"), 5000), "挪顺序:A 点用挪到最前,B 顺序也变了");
        check(waitFor(() -> same(A, B), 5000), "A、B 完全一致");

        // ---- 5. 对方离线:Worker 暂存,上线补发,回执后不再重复
        B.net.close();
        check(waitFor(() -> !B.net.isOpen(), 2000), "B:断开连接");
        A.delete("four"); A.changed();
        check(waitFor(() -> pending(hp, "clip~phoneB", false) >= 1, 8000), "A 删了一条:Worker 给离线的 B 暂存了第 1 条通知");
        A.copy("while-B-offline"); A.changed();
        check(waitFor(() -> pending(hp, "clip~phoneB", false) >= 2, 8000), "A 又复制一条:Worker 给离线的 B 暂存了第 2 条通知(共 " + pending(hp, "clip~phoneB", false) + " 条)");
        long bLastBefore = B.last("phoneA");
        B.engine.onShown();
        check(waitFor(() -> B.clipsCopy().contains("while-B-offline") && B.trashCopy().contains("four"), 8000), "B 上线:补发的改动都收到了");
        check(waitFor(() -> pending(hp, "clip~phoneB", false) == 0, 5000), "B 回执以后,Worker 不再留着(没有重复补发)");
        check(B.last("phoneA") > bLastBefore, "B:记住了收到 A 的哪个序号");
        B.net.close();
        B.engine.onShown();
        waitFor(() -> B.net.isOpen(), 5000);
        Thread.sleep(500);
        check(waitFor(() -> same(A, B), 5000), "B 再断再连:没有重复内容,仍和 A 一致");

        // ---- 6. 暂存被清掉(模拟挤掉 / 过期)造成缺号:读快照追上
        B.net.close();
        A.copy("lost-1"); A.changed();
        check(waitFor(() -> pending(hp, "clip~phoneB", false) >= 1, 8000), "缺号:A 发了第一条,B 离线,Worker 暂存");
        pending(hp, "clip~phoneB", true);   // 暂存丢了
        A.copy("lost-2"); A.changed();
        check(waitFor(() -> pending(hp, "clip~phoneB", false) >= 1 && !A.snapDirty, 8000), "缺号:A 又发了第二条,快照也更新了");
        B.engine.onShown();
        check(waitFor(() -> B.clipsCopy().contains("lost-1") && B.clipsCopy().contains("lost-2"), 8000), "缺号:B 发现中间缺了一条,读 A 的快照追上,两条都有");
        check(waitFor(() -> same(A, B), 5000), "缺号修复后 A、B 一致");

        // ---- 7. 新设备 C 加入,之后实时同步
        check(C.sync(), "C:第一次同步成功");
        check(waitFor(() -> same(A, C), 5000), "C:加入后和 A 完全一致(含上锁、回收箱)");
        A.copy("after-C"); A.changed();
        check(waitFor(() -> C.clipsCopy().get(0).equals("after-C"), 5000), "C 加入后,A 的新内容实时到 C");
        C.copy("from-C"); C.changed();
        check(waitFor(() -> A.clipsCopy().get(0).equals("from-C") && B.clipsCopy().contains("from-C"), 8000), "C 的新内容也实时到 A 和 B");

        // ---- 8. 大批量内容分包,2 万字,特殊字符
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder("big" + i + ":");
            for (int j = 0; j < 8000; j++) sb.append("汉字abc").append(j % 10);
            A.copy(sb.toString().substring(0, 40_000));
        }
        A.changed();
        check(waitFor(() -> B.clipsCopy().size() == A.clipsCopy().size() && same(A, B), 15000), "大批量:20 条各 4 万字(约 80 万字,分了好几包)全部同步,顺序一样");
        StringBuilder twenty = new StringBuilder();
        for (int i = 0; i < 4000; i++) twenty.append("长文abc\n");
        String t20 = twenty.substring(0, 20000);
        B.copy(t20); B.changed();
        check(waitFor(() -> A.clipsCopy().get(0).equals(t20), 8000), "2 万字的内容(含换行)原样同步");
        String odd = "a\tb\\n c\r\nd 😀 \\\\ \"q\" {json}";
        A.copy(odd); A.changed();
        check(waitFor(() -> B.clipsCopy().get(0).equals(odd), 5000), "制表符 / 反斜杠 / 引号 / 换行 / emoji 原样同步");

        // ---- 9. 密钥不一致的设备:跳过、回执掉,不污染别人
        Phone D = new Phone("phoneD", hp, other);
        D.copy("secret-D");
        int abad = A.badCount(), bbad = B.badCount();
        String aBefore = A.state(), bBefore = B.state();
        check(D.sync(), "D(别的构建的密钥):同步流程不报错");
        check(D.badCount() >= 3 && !D.clipsCopy().contains("one"), "D:读不了别人的快照,记了 " + D.badCount() + " 个解不开,没有合并任何东西");
        check(waitFor(() -> A.badCount() > abad && B.badCount() > bbad, 8000), "A、B:收到 D 发的解不开的消息,计数增加");
        check(A.state().equals(aBefore) && B.state().equals(bBefore), "A、B 的内容没有被 D 影响");
        check(waitFor(() -> pending(hp, "clip~phoneA", false) == 0 && pending(hp, "clip~phoneB", false) == 0, 5000), "解不开的消息也回执了(Worker 不会一直补发)");

        // ---- 10. 断网后恢复
        Phone E = new Phone("phoneE", "127.0.0.1:1", master);
        E.copy("offline-E");
        check(!E.sync() && E.lastFail != null, "E:连不上时同步失败,给出原因:" + E.lastFail);
        E.net.hostPort = hp;
        check(E.sync(), "E:网络恢复后同步成功");
        check(waitFor(() -> A.clipsCopy().contains("offline-E") && C.clipsCopy().contains("offline-E"), 8000), "E:断网期间复制的内容,恢复后传给了别的设备");
        check(waitFor(() -> same(A, E) && E.clipsCopy().contains("one") && E.lockedCopy().contains("one"), 8000), "E:同时拿到了别的设备已有的内容(含上锁的),和 A 完全一致(包括刚发生的改动:对方上线时直接把自己当前的内容推了过来)");

        // ---- 11. 删除云端本机的快照
        check(C.engine.deleteOwnCloud().get(10, TimeUnit.SECONDS), "C:删除云端本机的快照");
        check(!C.net.r2List(ClipRelayCore.SNAP_PREFIX).contains("clip/snap/phoneC") && !C.inited, "C:R2 里没有 C 的快照了,下次当作第一次加入");

        // ---- 12. 最后:A、B、E 完全一致(D 是别的构建,C 删过快照但还连着)
        boolean fin = waitFor(() -> same(A, B) && same(B, E), 10000);
        if (!fin) {
            for (Phone p : new Phone[]{A, B, E}) {
                StringBuilder sb = new StringBuilder(p.id + ": clips=" + p.clipsCopy().size() + " locked=" + p.lockedCopy() + " trash=" + p.trashCopy().size() + " :");
                for (String c : p.clipsCopy()) sb.append(" [").append(c.length() > 12 ? c.substring(0, 12) + "…" + c.length() : c).append("]");
                System.out.println(sb);
            }
        }
        check(fin, "最后:A、B、E 三台完全一致");
        check(waitFor(() -> same(A, C), 10000), "最后:C 也和 A 一致");

        for (Phone p : new Phone[]{A, B, C, D, E}) { p.engine.close(); p.exec.shutdownNow(); }
        System.out.println(fails == 0 ? "ALL PASS (" + passes + " 项)" : "FAILED " + fails + "(通过 " + passes + " 项)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
