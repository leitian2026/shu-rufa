// 剪贴板联动两条通道同时开着(Worker 主通道 + 坚果云备用通道)的自测:几台模拟设备,同一份数据同时走两条通道。
// 需要两个本机服务同时在跑(run_selftests.sh 会自动起):
//   node --experimental-sqlite --no-warnings tools/worker/server.mjs tools/worker/hulian-worker.js 18096 &   # 真 Worker 脚本
//   python3 tools/fake_dav.py 18097 /tmp/davdata5 &                                                          # 本机假的 WebDAV
//   javac -encoding UTF-8 -d /tmp/cdual app/src/main/java/com/example/myime/{ClipRelayCore,ClipDavCore,ClipSyncCore,BackupCrypto,WebDavClient}.java tools/ClipRelayE2E.java tools/ClipDualE2E.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/cdual ClipDualE2E 18096 18097
// 覆盖:两条通道各记各的"待发送"(Worker 发完不影响坚果云照样传)、同一条改动从两条通道都收到不重复、
//       连不上 Worker 的设备靠坚果云和别的设备同步(收发、删除、上锁)、只有坚果云的设备、最后几台完全一致。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ClipDualE2E {
    static int fails = 0, passes = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passes++; else fails++;
    }

    static List<String> L(String... a) { return new ArrayList<>(Arrays.asList(a)); }

    // 设备 ID 用十六进制字符(坚果云的文件名规则只认 0-9a-f,App 里的设备 ID 本来就是十六进制)
    static final class Phone implements ClipSyncCore.Host {
        final String id;
        final File tmp;
        final ClipSyncCore.Session session;
        final ClipSyncCore.Store store;
        final ClipRelayE2E.Net net;
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        final ClipRelayCore.Engine engine;
        List<String> clips = new ArrayList<>();
        Set<String> locked = new LinkedHashSet<>();
        List<String> trash = new ArrayList<>();
        final byte[] master;
        // 主通道状态
        long rSeq = 0;
        final Map<String, Long> rLast = new ConcurrentHashMap<>();
        Set<String> roster = new TreeSet<>();
        long rosterAt = 0;
        volatile boolean snapDirty, inited;
        // 备用通道状态
        long dSeq = 0;
        final Map<String, Long> dCur = new HashMap<>();
        final Set<String> dBad = new HashSet<>();

        Phone(String id, String workerHp, byte[] master) throws IOException {
            this.id = id;
            this.master = master;
            this.tmp = Files.createTempDirectory("dual_" + id).toFile();
            this.session = new ClipSyncCore.Session(new ClipSyncCore.Model(), this, 500, 300_000, 10, System::currentTimeMillis);
            this.store = new ClipSyncCore.Store() {
                public <T> T atomically(ClipSyncCore.Op<T> op) throws IOException {
                    synchronized (Phone.this) { return session.atomically(op); }
                }
            };
            this.net = new ClipRelayE2E.Net(workerHp, id);
            ClipRelayCore.Config cfg = new ClipRelayCore.Config();
            cfg.flushDelayMs = 30; cfg.minGapMs = 100; cfg.snapDelayMs = 250; cfg.replyTimeoutMs = 3000; cfg.connectTimeoutMs = 3000;
            ClipRelayCore.State rs = new ClipRelayCore.State() {
                public long mySeq() { return rSeq; }
                public void setMySeq(long s) { rSeq = s; }
                public long last(String d) { Long v = rLast.get(d); return v == null ? -1 : v; }
                public void setLast(String d, long s) { rLast.put(d, s); }
                public Set<String> roster() { return new TreeSet<>(roster); }
                public long rosterAt() { return rosterAt; }
                public void setRoster(Set<String> r, long at) { roster = new TreeSet<>(r); rosterAt = at; }
                public boolean snapDirty() { return snapDirty; }
                public void setSnapDirty(boolean v) { snapDirty = v; }
                public boolean inited() { return inited; }
                public void setInited(boolean v) { inited = v; }
                public int badCount() { return 0; }
                public void addBad() { }
            };
            this.engine = new ClipRelayCore.Engine(net, store, rs, id, master, new byte[16], System::currentTimeMillis, exec,
                new ClipRelayCore.Sink() { public void ok(String m) { } public void fail(String w) { } }, cfg);
        }

        public ClipSyncCore.Lists lists() { return new ClipSyncCore.Lists(new ArrayList<>(clips), new LinkedHashSet<>(locked), new ArrayList<>(trash)); }
        public void apply(ClipSyncCore.Lists l) { clips = new ArrayList<>(l.clips); locked = new LinkedHashSet<>(l.locked); trash = new ArrayList<>(l.trash); }

        final ClipDavCore.State davState = new ClipDavCore.State() {
            public long cursor(String d) { Long v = dCur.get(d); return v == null ? -1 : v; }
            public void setCursor(String d, long s) { dCur.put(d, s); }
            public boolean bad(String n) { return dBad.contains(n); }
            public void markBad(String n) { dBad.add(n); }
            public long mySeq() { return dSeq; }
            public void setMySeq(long s) { dSeq = s; }
        };

        ClipDavCore.Result davSync(WebDavClient cl, List<String> folder, boolean push) throws IOException {
            return ClipDavCore.run(cl, folder, id, master, new byte[16], store, tmp, davState, push, System.currentTimeMillis());
        }

        synchronized void copy(String t) { if (!clips.isEmpty() && clips.get(0).equals(t)) return; clips.remove(t); clips.add(0, t); }
        synchronized void delete(String t) { if (locked.contains(t)) return; if (clips.remove(t)) { trash.remove(t); trash.add(0, t); while (trash.size() > 10) trash.remove(trash.size() - 1); } }
        synchronized void lock(String t) { if (clips.contains(t)) locked.add(t); }
        synchronized String state() { return clips + "|" + new TreeSet<>(locked) + "|" + trash; }   // 上了锁的是集合,不比较先后
        synchronized List<String> clipsCopy() { return new ArrayList<>(clips); }
        synchronized List<String> trashCopy() { return new ArrayList<>(trash); }
        synchronized Set<String> lockedCopy() { return new LinkedHashSet<>(locked); }
    }

    static boolean same(Phone a, Phone b) { return a.state().equals(b.state()); }

    public static void main(String[] args) throws Exception {
        final String whp = "127.0.0.1:" + args[0];
        WebDavClient cl = new WebDavClient("http://127.0.0.1:" + args[1] + "/dav/", "a@b.c", "secretpw", true, null);
        List<String> parent = WebDavClient.splitFolder("我的坚果云/myime");
        List<String> folder = new ArrayList<>(parent);
        folder.add(ClipDavCore.SUBFOLDER);
        byte[] master = BackupCrypto.deriveMaster("same-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);

        Phone A = new Phone("aaaa1", whp, master), B = new Phone("bbbb2", whp, master);
        Phone C = new Phone("cccc3", "127.0.0.1:1", master);   // C 连不上 Worker(被屏蔽),只能靠坚果云

        // ---- 1. A、B 两条通道都开:先各自加入
        A.copy("one"); A.copy("two");
        check(A.engine.syncNow().get(30, TimeUnit.SECONDS), "A:主通道(Worker)第一次同步成功");
        A.davSync(cl, folder, true);
        check(B.engine.syncNow().get(30, TimeUnit.SECONDS), "B:主通道第一次同步成功");
        B.davSync(cl, folder, true);
        check(B.clipsCopy().equals(L("two", "one")), "B:加入后拿到 A 的内容");

        // ---- 2. 两条通道各记各的待发送
        A.copy("x1");
        A.engine.onLocalChange();
        check(ClipRelayE2E.waitFor(() -> B.clipsCopy().get(0).equals("x1"), 8000), "A 复制 x1:通过 Worker 秒到 B");
        check(ClipRelayE2E.waitFor(() -> { synchronized (A) { return !A.session.model().hasDirty() && A.session.model().hasDirty2(); } }, 5000),
            "Worker 发完以后:主通道没有待发送了,但坚果云这条通道还有待传的(两条通道各记各的)");
        ClipDavCore.Result r = A.davSync(cl, folder, true);
        check(r.pushed.equals("delta"), "A:坚果云照样把 x1 传上去了(增量)");
        check(ClipRelayE2E.waitFor(() -> { synchronized (A) { return !A.session.model().hasDirty2(); } }, 2000), "传完以后坚果云这条通道也清空了");

        // ---- 3. 同一条改动从两条通道都收到,不重复
        String before = B.state();
        r = B.davSync(cl, folder, false);
        check(r.filesRead >= 1 && r.changed == 0 && B.state().equals(before), "B:坚果云上又读到 x1 的增量:合并 0 条,列表没变(不重复)");

        // ---- 4. C 连不上 Worker,靠坚果云加入并和别的设备同步
        check(!C.engine.syncNow().get(30, TimeUnit.SECONDS), "C:连不上 Worker,主通道同步失败(不影响下面的备用通道)");
        r = C.davSync(cl, folder, true);
        check(C.clipsCopy().equals(L("x1", "two", "one")), "C:靠坚果云加入,拿到 A、B 的内容");
        C.copy("c1");
        r = C.davSync(cl, folder, true);
        check(r.pushed.equals("delta") || r.pushed.equals("snap"), "C:新复制的 c1 传到了坚果云");
        A.davSync(cl, folder, false);
        B.davSync(cl, folder, false);
        check(A.clipsCopy().get(0).equals("c1") && B.clipsCopy().get(0).equals("c1"), "A、B:从坚果云收到了 C 的 c1(C 根本没连上 Worker)");

        // ---- 5. 删除、上锁只走坚果云也联动
        C.delete("two");
        C.lock("one");
        C.davSync(cl, folder, true);
        A.davSync(cl, folder, false);
        B.davSync(cl, folder, false);
        check(A.trashCopy().contains("two") && B.trashCopy().contains("two") && A.lockedCopy().contains("one") && B.lockedCopy().contains("one"),
            "C 在坚果云上删除 two、锁定 one:A、B 也都照做");

        // ---- 6. 同一条改动两条通道都送到 B:结果一致
        A.lock("x1");
        A.engine.onLocalChange();
        check(ClipRelayE2E.waitFor(() -> B.lockedCopy().contains("x1"), 8000), "A 锁定 x1:Worker 秒到 B");
        A.davSync(cl, folder, true);
        B.davSync(cl, folder, false);
        C.davSync(cl, folder, false);
        check(B.lockedCopy().contains("x1") && C.lockedCopy().contains("x1"), "坚果云再送一遍:B 不变,C 也锁定了 x1");

        // ---- 7. 最后三台一致
        for (int i = 0; i < 2; i++) {
            A.davSync(cl, folder, true);
            B.davSync(cl, folder, true);
            C.davSync(cl, folder, true);
        }
        boolean fin = ClipRelayE2E.waitFor(() -> same(A, B) && same(B, C), 8000);
        if (!fin) for (Phone p : new Phone[]{A, B, C}) System.out.println(p.id + ": " + p.state());
        check(fin, "最后:A、B、C 三台完全一致");

        for (Phone p : new Phone[]{A, B, C}) { p.engine.close(); p.exec.shutdownNow(); }
        System.out.println(fails == 0 ? "ALL PASS (" + passes + " 项)" : "FAILED " + fails + "(通过 " + passes + " 项)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
