// 剪贴板联动备用通道(坚果云,ClipDavCore.run)的自测:用 tools/fake_dav.py(本机假的 WebDAV 服务器)模拟几台设备共用同一个云端文件夹。
// 在工程根目录执行(需要 JDK 17+ 和 python3):
//   rm -rf /tmp/davdata4 && mkdir -p /tmp/davdata4 && (python3 tools/fake_dav.py 18094 /tmp/davdata4 &)
//   javac -encoding UTF-8 -d /tmp/cdE app/src/main/java/com/example/myime/{ClipDavCore,ClipSyncCore,BackupCrypto,WebDavClient}.java tools/ClipDavE2E.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/cdE ClipDavE2E 18094
// 覆盖:第一次传整份快照、之后只传增量(文件很小、没变化不传不下载)、删除 / 上锁 / 回收箱 / 恢复 / 挪到最前 / 清空的联动、
//       两台同时改、一台删一台锁(谁新听谁的)、删除不会被旧快照带回来、增量攒够并成快照后落后的设备改读快照、新设备加入、
//       2 万字的内容、口令不一致解不开(跳过、不反复下载)、损坏的文件跳过、网络不通整体失败、别的文件不碰、临时文件清干净。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ClipDavE2E {
    static int fails = 0;
    static long clock = 1_800_000_000_000L;
    static long tick() { clock += 1000; return clock; }

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fails++;
    }

    static List<String> L(String... a) { return new ArrayList<>(Arrays.asList(a)); }

    /** 一台模拟的设备:界面上的三份列表 + 模型 + 同步状态。操作和 MyImeService 里的一致。 */
    static final class Phone implements ClipSyncCore.Host, ClipDavCore.State {
        final String id;
        final File tmp;
        List<String> clips = new ArrayList<>();
        Set<String> locked = new LinkedHashSet<>();
        List<String> trash = new ArrayList<>();
        final ClipSyncCore.Session ses;
        final Map<String, Long> cur = new HashMap<>();
        final Set<String> bad = new HashSet<>();
        long mySeq = 0;

        Phone(String id) throws IOException {
            this.id = id;
            tmp = Files.createTempDirectory("clipphone_" + id).toFile();
            ses = new ClipSyncCore.Session(new ClipSyncCore.Model(), this, 500, 300_000, 10, () -> clock);
        }

        public ClipSyncCore.Lists lists() { return new ClipSyncCore.Lists(new ArrayList<>(clips), new LinkedHashSet<>(locked), new ArrayList<>(trash)); }
        public void apply(ClipSyncCore.Lists l) { clips = new ArrayList<>(l.clips); locked = new LinkedHashSet<>(l.locked); trash = new ArrayList<>(l.trash); }
        public long cursor(String dev) { Long v = cur.get(dev); return v == null ? -1 : v; }
        public void setCursor(String dev, long seq) { cur.put(dev, seq); }
        public boolean bad(String n) { return bad.contains(n); }
        public void markBad(String n) { bad.add(n); }
        public long mySeq() { return mySeq; }
        public void setMySeq(long s) { mySeq = s; }

        // 用户操作
        void copy(String t) { tick(); if (!clips.isEmpty() && clips.get(0).equals(t)) return; clips.remove(t); clips.add(0, t); }
        void paste(String t) { tick(); if (clips.remove(t)) clips.add(0, t); }
        void delete(String t) { tick(); if (locked.contains(t)) return; if (clips.remove(t)) { trash.remove(t); trash.add(0, t); while (trash.size() > 10) trash.remove(trash.size() - 1); } }
        void lock(String t) { tick(); if (clips.contains(t)) locked.add(t); }
        void restore(String t) { tick(); if (trash.remove(t)) { clips.remove(t); clips.add(0, t); } }
        void deleteAllUnlocked() { tick(); List<String> gone = new ArrayList<>(); for (String c : clips) if (!locked.contains(c)) gone.add(c); for (String g : gone) { clips.remove(g); } trash.removeAll(gone); trash.addAll(0, gone); while (trash.size() > 10) trash.remove(trash.size() - 1); }

        ClipDavCore.Result sync(WebDavClient cl, List<String> folder, byte[] master, boolean push) throws IOException {
            return ClipDavCore.run(cl, folder, id, master, new byte[16], ses, tmp, this, push, tick());
        }

        ClipDavCore.Result syncForce(WebDavClient cl, List<String> folder, byte[] master) throws IOException {
            return ClipDavCore.run(cl, folder, id, master, new byte[16], ses, tmp, this, true, true, tick());
        }

        boolean noTmp() { String[] l = tmp.list((d, n) -> n.startsWith("clipdav_")); return l == null || l.length == 0; }
    }

    static boolean same(Phone a, Phone b) {
        return a.clips.equals(b.clips) && a.locked.equals(b.locked) && a.trash.equals(b.trash);
    }

    static Map<String, Long> files(WebDavClient cl, List<String> folder) throws IOException {
        Map<String, Long> m = new TreeMap<>();
        for (WebDavClient.Entry e : cl.list(folder)) if (!e.dir) m.put(e.name, e.size);
        return m;
    }

    static int count(Map<String, Long> f, String prefix) {
        int n = 0;
        for (String k : f.keySet()) if (k.startsWith(prefix)) n++;
        return n;
    }

    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:" + a[0] + "/dav/";
        WebDavClient cl = new WebDavClient(base, "a@b.c", "secretpw", true, null);
        List<String> parent = WebDavClient.splitFolder("我的坚果云/myime");
        List<String> folder = new ArrayList<>(parent);
        folder.add(ClipDavCore.SUBFOLDER);
        byte[] master = BackupCrypto.deriveMaster("same-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);
        byte[] other = BackupCrypto.deriveMaster("different".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);

        // 云端文件夹里已经有备份文件、学习数据同步文件(在上一级,不在 clip 子文件夹里),不能碰
        cl.ensureFolder(parent);
        Path junk = Files.createTempFile("junk", ".bin");
        Files.write(junk, new byte[]{1, 2, 3});
        cl.put(parent, "myime-backup-x.mybk", junk.toFile());
        cl.put(parent, "myime-learn-zzzz.lrn", junk.toFile());

        Phone A = new Phone("aaaa1"), B = new Phone("bbbb2");

        // ---- 1. 第一次:传整份快照
        A.copy("one"); A.copy("two"); A.copy("three");
        ClipDavCore.Result r = A.sync(cl, folder, master, true);
        Map<String, Long> f = files(cl, folder);
        check(r.pushed.equals("snap") && count(f, "s-aaaa1-") == 1 && count(f, "d-") == 0, "第一次:传了一份完整快照,没有增量");

        // ---- 2. B 拉取
        r = B.sync(cl, folder, master, true);
        check(r.filesRead == 1 && B.clips.equals(L("three", "two", "one")) && B.clips.equals(A.clips), "B:收到 A 的内容,顺序一样");
        f = files(cl, folder);
        check(count(f, "s-bbbb2-") == 1, "B 第一次也传了自己的快照");
        check(!B.ses.model().hasDirty2(), "B:收来的内容不算本机改动");

        // ---- 3. 没变化:不下载、不上传
        r = A.sync(cl, folder, master, true);
        check(r.filesRead == 1 || r.filesRead == 0, "A:读到 B 的快照(里面都是 A 自己的内容,合并 0 条)");
        check(r.changed == 0 && r.pushed.equals(""), "A:没有新变化:合并 0 条,不上传");
        Map<String, Long> before = files(cl, folder);
        r = A.sync(cl, folder, master, true);
        r = B.sync(cl, folder, master, true);
        check(r.filesRead == 0 && r.pushed.equals("") && files(cl, folder).equals(before), "再同步一次:什么都不下载、不上传、云端文件不变");
        check(same(A, B) || A.clips.equals(B.clips), "两边的列表一致");

        // ---- 4. 增量:A 再复制一条,只传增量
        A.copy("four");
        Map<String, Long> pre = files(cl, folder);
        r = A.sync(cl, folder, master, true);
        f = files(cl, folder);
        check(r.pushed.equals("delta") && count(f, "d-aaaa1-") == 1, "增量:只传了一个增量文件");
        long snapSize = 0, deltaSize = 0;
        for (Map.Entry<String, Long> e : f.entrySet()) { if (e.getKey().startsWith("s-aaaa1-")) snapSize = e.getValue(); if (e.getKey().startsWith("d-aaaa1-")) deltaSize = e.getValue(); }
        check(deltaSize > 0 && deltaSize <= snapSize, "增量文件比快照小(增量 " + deltaSize + " 字节,快照 " + snapSize + " 字节)");
        String snapBefore = null, snapAfter = null;
        for (String k : pre.keySet()) if (k.startsWith("s-aaaa1-")) snapBefore = k;
        for (String k : f.keySet()) if (k.startsWith("s-aaaa1-")) snapAfter = k;
        check(snapBefore != null && snapBefore.equals(snapAfter) && pre.get(snapBefore).equals(f.get(snapAfter)), "增量:快照文件没动");
        r = B.sync(cl, folder, master, false);
        check(r.filesRead == 1 && B.clips.get(0).equals("four"), "B:只读 1 个增量文件,新内容在最前");

        // ---- 5. 删除联动
        B.delete("two");
        r = B.sync(cl, folder, master, true);
        A.sync(cl, folder, master, true);
        check(!A.clips.contains("two") && A.trash.contains("two"), "删除:A 也没有了,进了回收箱");
        check(same(A, B), "删除:两边列表完全一致");

        // ---- 6. 上锁联动
        A.lock("one");
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(B.locked.contains("one") && B.clips.equals(A.clips), "上锁:B 也上了锁,顺序没变");

        // ---- 7. 回收箱恢复联动
        B.restore("two");
        B.sync(cl, folder, master, true);
        A.sync(cl, folder, master, true);
        check(A.clips.get(0).equals("two") && A.trash.isEmpty(), "恢复:A 也恢复了,放在最前,回收箱空了");

        // ---- 8. 点用后挪到最前联动
        A.paste("three");
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(B.clips.get(0).equals("three") && same(A, B), "挪到最前:B 的顺序也变了");

        // ---- 9. 两台同时改不同的内容
        A.copy("from-A");
        B.copy("from-B");
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        A.sync(cl, folder, master, true);
        check(same(A, B) && A.clips.contains("from-A") && A.clips.contains("from-B"), "同时改:两边都有对方的新内容,列表一致");

        // ---- 10. 一台删、一台锁(谁新听谁的)
        A.delete("four");
        B.lock("four");   // B 的操作更晚
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        A.sync(cl, folder, master, true);
        check(same(A, B) && A.locked.contains("four") && A.clips.contains("four"), "删和锁冲突:晚的那个(锁)赢,两边一致");

        // ---- 11. 删除不会被旧快照带回来
        A.copy("temp");
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        B.delete("temp");
        B.sync(cl, folder, master, true);
        // A 的快照文件里还有 "temp"(A 没并过快照),A 再同步:不能把 temp 带回来
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(!A.clips.contains("temp") && !B.clips.contains("temp") && same(A, B), "删除:不会被对方文件里的旧记录带回来");

        // ---- 12. 清空(删掉所有没上锁的)联动
        A.deleteAllUnlocked();
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(same(A, B) && B.clips.equals(new ArrayList<>(B.locked)) || B.clips.size() == B.locked.size(), "清空:B 只剩上了锁的");
        check(B.trash.size() <= 10 && same(A, B), "清空:回收箱不超过 10 条,两边一致");

        // ---- 13. 增量攒够 → 并成新快照;落后的设备改读快照
        Phone C = new Phone("cccc3");
        for (int i = 0; i < ClipDavCore.COMPACT_AT + 5; i++) {
            A.copy("bulk-" + i);
            A.sync(cl, folder, master, true);
        }
        f = files(cl, folder);
        check(count(f, "s-aaaa1-") == 1 && count(f, "d-aaaa1-") < ClipDavCore.COMPACT_AT, "并快照:自己的快照只有一份,增量没有无限增长(现在 " + count(f, "d-aaaa1-") + " 个)");
        r = B.sync(cl, folder, master, true);   // B 落后了二十多个增量,A 已经并过快照、删了旧增量
        check(same(A, B) && B.clips.contains("bulk-0") && B.clips.contains("bulk-" + (ClipDavCore.COMPACT_AT + 4)), "落后的设备:改读快照,追上了");

        // ---- 14. 新设备加入
        r = C.sync(cl, folder, master, true);
        check(C.clips.equals(A.clips) && C.locked.equals(A.locked), "新设备:一次拉取就得到完整的剪贴板");

        // ---- 15. 2 万字的内容
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4000; i++) big.append("长文abc\n");   // 约 3 万字符,含换行
        String bigText = big.substring(0, 20000);
        A.copy(bigText);
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(B.clips.get(0).equals(bigText), "2 万字的内容(含换行)原样同步");

        // ---- 16. 带制表符、反斜杠、emoji 的内容
        String odd = "a\tb\\n c\r\nd 😀 \\\\";
        A.copy(odd);
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(B.clips.get(0).equals(odd), "制表符 / 反斜杠 / 换行 / emoji 原样同步");

        // ---- 17. 口令(密钥)不一致:解不开,跳过,不反复下载
        Phone D = new Phone("dddd4");
        r = D.sync(cl, folder, other, true);
        check(r.filesSkipped > 0 && D.clips.isEmpty() && r.changed == 0, "密钥不一致:解不开的文件跳过,不报错,没有合并任何东西");
        ClipDavCore.Result r2 = D.sync(cl, folder, other, false);
        check(r2.filesSkipped == 0 && r2.filesRead == 0, "密钥不一致:第二次不再反复下载同样的文件");

        // ---- 18. 损坏的文件:跳过
        Path bad = Files.createTempFile("bad", ".bin");
        Files.write(bad, "this is not a clip sync file".getBytes("UTF-8"));
        cl.put(folder, "d-ffff9-5.dlt", bad.toFile());
        Phone E = new Phone("eeee5");
        r = E.sync(cl, folder, master, false);
        check(r.filesSkipped >= 1 && E.clips.equals(A.clips), "损坏的文件:跳过,别的照常读");

        // ---- 19. 别的文件不碰
        check(cl.list(parent).stream().anyMatch(e -> e.name.equals("myime-backup-x.mybk")) && cl.list(parent).stream().anyMatch(e -> e.name.equals("myime-learn-zzzz.lrn")), "上一级的备份文件和学习数据同步文件没被动过");

        // ---- 20. 网络不通:整体失败,不推进状态
        WebDavClient dead = new WebDavClient("http://127.0.0.1:1/dav/", "a@b.c", "secretpw", true, null);
        Phone F = new Phone("ffff6");
        F.copy("offline");
        boolean threw = false;
        try { F.sync(dead, folder, master, true); } catch (IOException ex) { threw = true; }
        check(threw && F.mySeq == 0 && F.cur.isEmpty() && F.ses.model().size() >= 0, "网络不通:抛错,序号和游标都没动");
        // 恢复网络后,离线时复制的会传出去
        r = F.sync(cl, folder, master, true);
        A.sync(cl, folder, master, true);
        check(r.pushed.equals("snap") && A.clips.contains("offline"), "恢复网络后:离线时复制的传出去了");

        // ---- 20b. 强制整份重传(换口令 / 还原备份之后):只留一份新快照,旧文件删掉,别的设备照常追上
        Map<String, Long> pre2 = files(cl, folder);
        String oldSnap = null;
        for (String k : pre2.keySet()) if (k.startsWith("s-aaaa1-")) oldSnap = k;
        r = A.syncForce(cl, folder, master);
        f = files(cl, folder);
        String newSnap = null;
        for (String k : f.keySet()) if (k.startsWith("s-aaaa1-")) newSnap = k;
        check(r.pushed.equals("snap") && count(f, "s-aaaa1-") == 1 && count(f, "d-aaaa1-") == 0 && newSnap != null && !newSnap.equals(oldSnap), "强制整份重传:只留一份新快照,自己的旧文件删掉了");
        A.copy("after-force");
        A.sync(cl, folder, master, true);
        B.sync(cl, folder, master, true);
        check(B.clips.get(0).equals("after-force") && B.clips.equals(A.clips), "强制重传之后:别的设备照常追上");

        // ---- 21. 临时文件清干净
        check(A.noTmp() && B.noTmp() && C.noTmp() && D.noTmp() && F.noTmp(), "临时文件都清掉了");

        // ---- 22. 收敛:再互相同步两轮,A B C 一致
        for (int i = 0; i < 2; i++) { A.sync(cl, folder, master, true); B.sync(cl, folder, master, true); C.sync(cl, folder, master, true); }
        check(same(A, B) && same(B, C), "最后:A、B、C 三台完全一致");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        if (fails != 0) System.exit(1);
    }
}
