// 剪贴板联动:过了 Worker 空闲时限的连接要重连(0.47.13)。纯 JDK,不联网,不需要 node:
// 用假的网络层(能假装"标志是开着、其实已经被网络悄悄断掉了")和可以随意拨动的时钟,验证 ClipRelayCore.Engine 的判断。
// 在工程根目录执行(需要 JDK 17+):
//   javac -encoding UTF-8 -d /tmp/crS app/src/main/java/com/example/myime/{ClipRelayCore,ClipSyncCore,BackupCrypto}.java tools/stub/com/example/myime/ErrorHint.java tools/ClipRelayStaleSelfTest.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/crS ClipRelayStaleSelfTest
// 覆盖:刚连上 / 刚发过 ping 的连接不乱重连、过了 90 秒(Worker 的空闲时限)才重连、边界(正好 90 秒还算活着)、
//       界面上的"已连接"在过期后显示没连着、ping 没发出去时不算续命、服务器自己关了照样重连、
//       没记录过时刻(keepAt = 0)时不乱重连、客户端的时限不比 Worker 脚本里的 HEARTBEAT_TIMEOUT_MS 长。
import com.example.myime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class ClipRelayStaleSelfTest {
    static int fails = 0, passes = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passes++; else fails++;
    }

    /** 假的网络层:不联网。isOpen 只看自己的标志,所以可以"看起来开着其实没人回"——这正是被网络悄悄断掉的连接的样子。 */
    static final class FakeNet implements ClipRelayCore.Transport {
        final AtomicInteger connects = new AtomicInteger(), closes = new AtomicInteger();
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open;
        volatile boolean sendOk = true;   // false:send 返回 false(发不出去)
        volatile ClipRelayCore.Listener lst;

        public void connect(ClipRelayCore.Listener l) {
            lst = l;
            connects.incrementAndGet();
            open = true;
            l.onOpen();
        }

        public boolean isOpen() { return open; }

        public boolean send(String text) {
            sent.add(text);
            return open && sendOk;
        }

        public void close() {
            closes.incrementAndGet();
            if (open) {
                open = false;
                ClipRelayCore.Listener l = lst;
                if (l != null) l.onClosed("本机关闭了连接");
            }
        }

        /** 模拟服务器主动关(关闭通知送到了手机)。 */
        void serverCloses() {
            if (open) {
                open = false;
                ClipRelayCore.Listener l = lst;
                if (l != null) l.onClosed("服务器关闭了连接(1001 heartbeat timeout)");
            }
        }

        int pings() {
            int n = 0;
            for (String s : sent) if ("ping".equals(s)) n++;
            return n;
        }

        public void r2Put(String key, byte[] data) {}

        public byte[] r2Get(String key) { return null; }

        public List<String> r2List(String prefix) { return new ArrayList<>(); }

        public void r2Delete(String key) {}
    }

    /** 一台模拟设备:Engine 要的 Host / State / Sink 都放在一起(和 ClipRelayE2E 里的 Phone 同一个写法,只是不联网)。 */
    static final class Dev implements ClipSyncCore.Host, ClipRelayCore.State, ClipRelayCore.Sink {
        final FakeNet net = new FakeNet();
        final AtomicLong now;
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        final ClipRelayCore.Engine engine;
        final ClipSyncCore.Session session;
        volatile String lastFail;
        long mySeq = 0;
        final Map<String, Long> last = new ConcurrentHashMap<>();
        Set<String> roster = new TreeSet<>();
        long rosterAt = 0;
        volatile boolean snapDirty, inited = true;   // 已经"第一次加入"过,不去读快照
        final AtomicInteger bad = new AtomicInteger();

        Dev(AtomicLong now, byte[] master) {
            this.now = now;
            this.session = new ClipSyncCore.Session(new ClipSyncCore.Model(), this, 500, 300_000, 10, now::get);
            ClipSyncCore.Store store = new ClipSyncCore.Store() {
                public <T> T atomically(ClipSyncCore.Op<T> op) throws java.io.IOException {
                    synchronized (Dev.this) { return session.atomically(op); }
                }
            };
            ClipRelayCore.Config cfg = new ClipRelayCore.Config();
            cfg.connectTimeoutMs = 2000; cfg.replyTimeoutMs = 500;
            this.engine = new ClipRelayCore.Engine(net, store, this, "devA", master, new byte[16], now::get, exec, this, cfg);
        }

        // Host
        List<String> clips = new ArrayList<>();
        Set<String> locked = new LinkedHashSet<>();
        List<String> trash = new ArrayList<>();
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
        public void ok(String m) {}
        public void fail(String w) { lastFail = w; }

        /** 键盘弹出一次,并等这次会话做完。 */
        void shown() throws Exception {
            engine.onShown();
            exec.submit(() -> {}).get(10, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] master = BackupCrypto.deriveMaster("same-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        final long SEC = 1000L;

        // ---- 1. 第一次:连上、发 ping
        Dev d = new Dev(now, master);
        d.shown();
        check(d.net.connects.get() == 1 && d.net.closes.get() == 0, "第一次键盘弹出:连了一次,没有关过");
        check(d.net.pings() == 1, "连上后发了一个 ping(让 Worker 的 90 秒计时从现在算)");
        check(d.engine.isConnected(), "刚连上:界面显示已连接");

        // ---- 2. 没过期的连接不乱重连,每次 ping 都让时限重新算
        now.addAndGet(30 * SEC);
        d.shown();
        check(d.net.connects.get() == 1 && d.net.closes.get() == 0, "30 秒后再弹出:还是同一条连接,没重连");
        check(d.net.pings() == 2, "30 秒后又发了一个 ping");
        now.addAndGet(80 * SEC);   // 距上次 ping 80 秒,但距最早连上已经 110 秒:算的是上次 ping
        d.shown();
        check(d.net.connects.get() == 1 && d.net.closes.get() == 0, "距上次 ping 80 秒(距最早连上已 110 秒):按上次 ping 算,没重连");
        check(d.net.pings() == 3, "又发了一个 ping");

        // ---- 3. 边界:正好 90 秒还算活着,多 1 毫秒就算过期
        now.addAndGet(90 * SEC);
        check(d.engine.isConnected(), "距上次 ping 正好 90 秒:还算活着");
        now.addAndGet(1);
        check(!d.engine.isConnected() && d.net.isOpen(), "多 1 毫秒:界面显示没连着(虽然那条连接的标志还开着,正是被悄悄断掉的样子)");

        // ---- 4. 过期了:关掉旧的、重新连、在新连接上发 ping
        int pingsBefore = d.net.pings();
        d.shown();
        check(d.net.closes.get() == 1 && d.net.connects.get() == 2, "过期后键盘弹出:关掉旧连接并重连(关 1 次,连 2 次)");
        check(d.net.pings() == pingsBefore + 1 && d.engine.isConnected(), "新连接上发了 ping,界面显示已连接");
        check(d.lastFail == null, "整个过程没有报失败");

        // ---- 5. ping 没发出去的不算续命
        d.net.sendOk = false;
        now.addAndGet(30 * SEC);
        d.shown();
        check(d.net.connects.get() == 2, "30 秒后(没过期):没重连,但这次 ping 没发出去");
        now.addAndGet(61 * SEC);   // 距上次成功的 ping 91 秒
        check(!d.engine.isConnected(), "ping 没发出去不算续命:距上次成功的 ping 91 秒,算过期");
        d.net.sendOk = true;
        d.shown();
        check(d.net.connects.get() == 3 && d.net.closes.get() == 2, "过期后重连(连 3 次,关 2 次)");

        // ---- 6. 服务器自己关了(通知送到了):照样重连,不需要本机再关一次
        int closesBefore = d.net.closes.get();
        d.net.serverCloses();
        d.shown();
        check(d.net.connects.get() == 4 && d.net.closes.get() == closesBefore, "服务器关了连接:重连,本机没有多关");

        // ---- 7. 没记录过时刻(连接不是这个 Engine 自己连的)时不乱重连
        Dev e = new Dev(now, master);
        e.net.open = true;   // 外面已经连好了,Engine 没经历过 onOpen,所以 keepAt 是 0
        now.addAndGet(1000 * SEC);
        check(e.engine.isConnected(), "没记录过连上的时刻:不当作过期");
        e.shown();
        check(e.net.connects.get() == 0 && e.net.closes.get() == 0 && e.net.pings() == 1, "没记录过时刻:不重连,发了 ping(之后就有记录了)");
        now.addAndGet(91 * SEC);
        e.shown();
        check(e.net.closes.get() == 1 && e.net.connects.get() == 1, "有记录之后过了 91 秒:才重连");

        // ---- 8. 客户端的时限不能比 Worker 脚本里的空闲时限长
        String js = new String(Files.readAllBytes(Paths.get("tools/worker/hulian-worker.js")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("HEARTBEAT_TIMEOUT_MS\\s*=\\s*(\\d+)\\s*\\*\\s*1e3").matcher(js);
        boolean found = m.find();
        check(found, "在 tools/worker/hulian-worker.js 里找到了 HEARTBEAT_TIMEOUT_MS");
        if (found) {
            long workerMs = Long.parseLong(m.group(1)) * 1000L;
            long mine = new ClipRelayCore.Config().staleAfterMs;
            check(mine <= workerMs, "Config.staleAfterMs(" + mine + " ms)不比 Worker 的 HEARTBEAT_TIMEOUT_MS(" + workerMs + " ms)长");
        }

        d.exec.shutdownNow();
        e.exec.shutdownNow();
        System.out.println();
        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
