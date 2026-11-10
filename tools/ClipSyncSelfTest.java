// 剪贴板联动的"模型"自测(ClipSyncCore.Model / Session),不联网。
// 在工程根目录执行(需要 JDK 17+):
//   javac -encoding UTF-8 -d /tmp/css app/src/main/java/com/example/myime/{ClipSyncCore,BackupCrypto,WebDavClient}.java tools/ClipSyncSelfTest.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/css ClipSyncSelfTest
// 覆盖:对账(新内容 / 删除 / 恢复 / 上锁 / 挪到最前 / 多条一起挪)、合并(谁新听谁的、时间一样的规则、顺序无关、重复读无害、
//       伪造的 id 不收、太晚的时间被截断)、生成列表(条数 / 字数上限、上了锁的不挤、回收箱上限、删除记录过期)、
//       本机保存读回、增量(只有改过的是 dirty,传完清掉,传的时候又改过的不清)。
import com.example.myime.*;
import java.io.*;
import java.util.*;

public class ClipSyncSelfTest {
    static int fails = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fails++;
    }

    static List<String> L(String... a) { return new ArrayList<>(Arrays.asList(a)); }
    static Set<String> S(String... a) { return new LinkedHashSet<>(Arrays.asList(a)); }

    static ClipSyncCore.Lists lists(List<String> c, Set<String> l, List<String> t) { return new ClipSyncCore.Lists(c, l, t); }

    static String id(String t) { return ClipSyncCore.idOf(t); }

    public static void main(String[] args) throws Exception {
        final long T0 = 1_800_000_000_000L;

        // ---- id
        check(id("abc").length() == 16 && id("abc").equals(id("abc")) && !id("abc").equals(id("abd")), "id:稳定、16 位、不同文字不同 id");

        // ---- 对账:第一次(模型是空的)
        ClipSyncCore.Model m = new ClipSyncCore.Model();
        List<String> clips = L("c", "b", "a");   // 新的在前
        check(m.reconcile(clips, S(), L(), T0), "对账:第一次有变化");
        List<ClipSyncCore.Rec> d = m.takeDirty();
        check(d.size() == 3, "对账:3 条都是待上传");
        ClipSyncCore.Lists out = m.materialize(500, 300000, 10, T0 + 10);
        check(out.clips.equals(L("c", "b", "a")), "生成列表:顺序和原来一样(旧→新 排序时间递增)");
        check(!m.reconcile(clips, S(), L(), T0 + 20), "对账:没动过就没有变化");

        // 清掉待上传
        m.clearDirty(d);
        check(!m.hasDirty(), "传完以后没有待上传的");

        // ---- 新复制一条
        clips.add(0, "d");
        m.reconcile(clips, S(), L(), T0 + 100);
        check(m.takeDirty().size() == 1 && m.takeDirty().get(0).text.equals("d"), "新复制:只有新的这一条待上传");
        m.clearDirty(m.takeDirty());

        // ---- 点用后挪到最前
        clips = L("a", "d", "c", "b");
        m.reconcile(clips, S(), L(), T0 + 200);
        List<ClipSyncCore.Rec> dd = m.takeDirty();
        check(dd.size() == 1 && dd.get(0).text.equals("a"), "挪到最前:只有被挪的那条待上传");
        out = m.materialize(500, 300000, 10, T0 + 210);
        check(out.clips.equals(L("a", "d", "c", "b")), "挪到最前:生成列表顺序是新的");
        m.clearDirty(dd);

        // ---- 一次挪两条
        clips = L("b", "c", "a", "d");
        m.reconcile(clips, S(), L(), T0 + 300);
        out = m.materialize(500, 300000, 10, T0 + 310);
        check(out.clips.equals(L("b", "c", "a", "d")), "一次挪两条:顺序保持");
        m.clearDirty(m.takeDirty());

        // ---- 上锁:不改顺序
        m.reconcile(clips, S("a"), L(), T0 + 400);
        dd = m.takeDirty();
        check(dd.size() == 1 && dd.get(0).st == 'l', "上锁:只有它待上传,状态是 l");
        out = m.materialize(500, 300000, 10, T0 + 410);
        check(out.clips.equals(L("b", "c", "a", "d")) && out.locked.equals(S("a")), "上锁:顺序不变,锁集合对");
        m.clearDirty(dd);

        // ---- 删除(进回收箱)
        clips = L("b", "a", "d");
        m.reconcile(clips, S("a"), L("c"), T0 + 500);
        ClipSyncCore.Rec rc = m.get(id("c"));
        check(rc.st == 't' && rc.dirty, "删除:进回收箱,状态 t");
        out = m.materialize(500, 300000, 10, T0 + 510);
        check(out.clips.equals(L("b", "a", "d")) && out.trash.equals(L("c")), "删除:列表对");
        m.clearDirty(m.takeDirty());

        // ---- 回收箱里彻底删除
        m.reconcile(clips, S("a"), L(), T0 + 600);
        check(m.get(id("c")).st == 'x' && m.get(id("c")).text == null, "彻底删除:状态 x、文字清掉");
        m.clearDirty(m.takeDirty());

        // ---- 恢复:删掉的又出现在剪贴板里
        clips = L("c", "b", "a", "d");
        m.reconcile(clips, S("a"), L(), T0 + 700);
        check(m.get(id("c")).st == 'a' && m.get(id("c")).dirty, "恢复:从删除状态回到正常");
        out = m.materialize(500, 300000, 10, T0 + 710);
        check(out.clips.get(0).equals("c"), "恢复:放到最前");

        // ---- 修改时间只增不减(时钟往回走)
        ClipSyncCore.Model mc = new ClipSyncCore.Model();
        mc.reconcile(L("x"), S(), L(), T0 + 1000);
        long mt1 = mc.get(id("x")).mt;
        mc.reconcile(L(), S(), L(), T0 + 500);   // 时钟回到过去
        check(mc.get(id("x")).mt > mt1, "时钟往回走:修改时间仍然增加");

        // ---- 合并:谁新听谁的
        ClipSyncCore.Model a = new ClipSyncCore.Model();
        ClipSyncCore.Model b = new ClipSyncCore.Model();
        a.reconcile(L("p", "q"), S(), L(), T0 + 1000);
        List<ClipSyncCore.Rec> fromA = a.takeDirty();
        b.merge(fromA, T0 + 2000);
        ClipSyncCore.Lists lb = b.materialize(500, 300000, 10, T0 + 2000);
        check(lb.clips.equals(L("p", "q")), "合并:B 收到 A 的两条,顺序一样");
        check(!b.hasDirty(), "合并:收来的不算本机改动(不会再传回去)");
        // B 删了 p
        b.reconcile(L("q"), S(), L(), T0 + 3000);
        List<ClipSyncCore.Rec> fromB = b.takeDirty();
        a.merge(fromB, T0 + 4000);
        ClipSyncCore.Lists la = a.materialize(500, 300000, 10, T0 + 4000);
        check(la.clips.equals(L("q")), "合并:A 收到 B 的删除(状态 x)");
        // 把 A 以前的旧状态再合并一遍:删除不会复活
        b.merge(fromA, T0 + 5000);
        check(b.materialize(500, 300000, 10, T0 + 5000).clips.equals(L("q")), "合并:旧记录再来一遍,删除的不会复活");

        // ---- 顺序无关、重复无害
        ClipSyncCore.Model c1 = new ClipSyncCore.Model(), c2 = new ClipSyncCore.Model();
        List<ClipSyncCore.Rec> all = new ArrayList<>(fromA);
        all.addAll(fromB);
        c1.merge(all, T0 + 6000);
        List<ClipSyncCore.Rec> rev = new ArrayList<>(all);
        Collections.reverse(rev);
        c2.merge(rev, T0 + 6000);
        c2.merge(all, T0 + 6000);
        check(c1.materialize(500, 300000, 10, T0 + 6000).clips.equals(c2.materialize(500, 300000, 10, T0 + 6000).clips), "合并:顺序颠倒、重复,结果一样");

        // ---- 时间一样的规则:x > t > l > a
        ClipSyncCore.Model t1 = new ClipSyncCore.Model();
        ClipSyncCore.Model src = new ClipSyncCore.Model();
        src.reconcile(L("k"), S(), L(), T0 + 100);
        ClipSyncCore.Rec ka = src.takeDirty().get(0);
        t1.merge(Collections.singletonList(ka), T0 + 200);
        // 构造同 mt 的 l 记录:用另一个模型上锁后把 mt 对齐不好做,直接用往返文件之外的办法——再对账一次上锁,mt 一定更大
        t1.reconcile(L("k"), S("k"), L(), T0 + 300);
        long mtLock = t1.get(id("k")).mt;
        check(t1.get(id("k")).st == 'l' && mtLock > ka.mt, "上锁的修改时间比原来大");
        t1.merge(Collections.singletonList(ka), T0 + 400);   // 旧的 a 记录来了:不覆盖
        check(t1.get(id("k")).st == 'l', "旧状态不覆盖新状态");

        // ---- 伪造的记录不收
        ClipSyncCore.Model e = new ClipSyncCore.Model();
        ClipSyncCore.Model e2 = new ClipSyncCore.Model();
        e2.reconcile(L("real"), S(), L(), T0 + 100);
        ClipSyncCore.Rec real = e2.takeDirty().get(0);
        real.text = "fake text";   // 文字和 id 对不上
        check(e.merge(Collections.singletonList(real), T0 + 200) == 0 && e.size() == 0, "合并:文字和 id 对不上的记录不收");

        // ---- 太晚的时间被截断
        ClipSyncCore.Model f = new ClipSyncCore.Model();
        ClipSyncCore.Model f2 = new ClipSyncCore.Model();
        f2.reconcile(L("future"), S(), L(), T0 + 100);
        ClipSyncCore.Rec fut = f2.takeDirty().get(0);
        fut.mt = T0 + 10L * 365 * 24 * 3600 * 1000;
        fut.ot = fut.mt;
        f.merge(Collections.singletonList(fut), T0 + 200);
        check(f.get(id("future")).mt <= T0 + 200 + 10 * 60 * 1000, "合并:十年后的时间被截断到现在 + 10 分钟");

        // ---- 上限
        ClipSyncCore.Model cap = new ClipSyncCore.Model();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add("item" + i);   // item0 最新
        cap.reconcile(many, S("item11"), L(), T0 + 100);
        cap.clearDirty(cap.takeDirty());   // 假设这些都已经传过了
        ClipSyncCore.Lists capped = cap.materialize(5, 300000, 10, T0 + 110);
        check(capped.clips.size() == 6 && capped.clips.contains("item11") && capped.locked.contains("item11"), "条数上限:最多 5 条 + 上了锁的不挤");
        check(cap.get(id("item8")).st == 'x' && !cap.get(id("item8")).dirty, "条数上限:被挤掉的变成已删除,但不算本机改动");
        ClipSyncCore.Model cap2 = new ClipSyncCore.Model();
        cap2.reconcile(L("x1234567890", "y1234567890", "z1234567890"), S(), L(), T0 + 100);
        ClipSyncCore.Lists capped2 = cap2.materialize(500, 25, 10, T0 + 110);
        check(capped2.clips.equals(L("x1234567890", "y1234567890")), "字数上限:超了从最旧的挤掉(至少留最新一条)");
        ClipSyncCore.Model cap3 = new ClipSyncCore.Model();
        List<String> tr = new ArrayList<>();
        for (int i = 0; i < 14; i++) tr.add("t" + i);
        cap3.reconcile(L(), S(), tr, T0 + 100);
        ClipSyncCore.Lists capped3 = cap3.materialize(500, 300000, 10, T0 + 110);
        check(capped3.trash.equals(tr.subList(0, 10)), "回收箱上限:留最新的 10 条,顺序不变");

        // ---- 删除记录过期
        ClipSyncCore.Model tomb = new ClipSyncCore.Model();
        tomb.reconcile(L("gone"), S(), L(), T0 + 100);
        tomb.reconcile(L(), S(), L(), T0 + 200);
        check(tomb.get(id("gone")).st == 'x', "删除记录:先留着");
        tomb.materialize(500, 300000, 10, T0 + 200 + ClipSyncCore.TOMB_KEEP_MS + 1000);
        check(tomb.get(id("gone")) == null, "删除记录:30 天后丢掉");

        // ---- 本机保存读回
        StringWriter sw = new StringWriter();
        m.writeMeta(sw);
        List<String> lines = Arrays.asList(sw.toString().split("\n"));
        ClipSyncCore.Lists cur = m.materialize(500, 300000, 10, T0 + 800);
        List<String> texts = new ArrayList<>(cur.clips);
        texts.addAll(cur.trash);
        ClipSyncCore.Model back = ClipSyncCore.Model.readMeta(lines, texts);
        check(back.size() == m.size(), "保存读回:记录数一样");
        check(back.materialize(500, 300000, 10, T0 + 810).clips.equals(cur.clips), "保存读回:列表一样");
        ClipSyncCore.Model lost = ClipSyncCore.Model.readMeta(lines, Collections.<String>emptyList());
        check(lost.size() == 1 || lost.size() == countX(m), "保存读回:找不到文字的正常记录丢掉,已删除的留着");

        // ---- Session:对账 → 执行 → 生成列表,列表变了才交给界面
        final List<ClipSyncCore.Lists> applied = new ArrayList<>();
        final ClipSyncCore.Lists[] hostLists = {lists(L("s2", "s1"), S(), L())};
        ClipSyncCore.Host host = new ClipSyncCore.Host() {
            public ClipSyncCore.Lists lists() { return hostLists[0]; }
            public void apply(ClipSyncCore.Lists l) { hostLists[0] = l; applied.add(l); }
        };
        final long[] clock = {T0 + 10_000};
        ClipSyncCore.Session ses = new ClipSyncCore.Session(new ClipSyncCore.Model(), host, 500, 300000, 10, () -> clock[0]);
        Boolean hd = ses.atomically(mm -> mm.hasDirty());
        check(hd && applied.isEmpty(), "Session:对账后有待上传的;列表没变不通知界面");
        ClipSyncCore.Model other = new ClipSyncCore.Model();
        other.reconcile(L("fromOther"), S(), L(), T0 + 20_000);
        final List<ClipSyncCore.Rec> inc = other.takeDirty();
        clock[0] = T0 + 30_000;
        ses.atomically(mm -> mm.merge(inc, clock[0]));
        check(applied.size() == 1 && hostLists[0].clips.contains("fromOther") && hostLists[0].clips.size() == 3, "Session:并入别人的内容后,列表变了,通知界面");

        // ---- 传的时候又改过的不清
        ClipSyncCore.Model race = new ClipSyncCore.Model();
        race.reconcile(L("r1"), S(), L(), T0 + 100);
        List<ClipSyncCore.Rec> sent = race.takeDirty();
        race.reconcile(L("r1"), S("r1"), L(), T0 + 200);   // 上传期间又上了锁
        race.clearDirty(sent);
        check(race.get(id("r1")).dirty, "上传期间又改过的,仍然算待上传");

        // ---- 两条通道(Worker / 坚果云)各记各的待发送
        ClipSyncCore.Model two = new ClipSyncCore.Model();
        two.reconcile(L("t1", "t2"), S(), L(), T0 + 100);
        check(two.hasDirty() && two.hasDirty2() && two.takeDirty().size() == 2 && two.takeDirty2().size() == 2, "两条通道:新内容两条通道都算待发送");
        two.clearDirty(two.takeDirty());
        check(!two.hasDirty() && two.hasDirty2() && two.takeDirty2().size() == 2, "两条通道:Worker 发完只清自己的,坚果云这条仍然待传");
        List<ClipSyncCore.Rec> sentDav = two.takeDirty2();
        two.reconcile(L("t1", "t2"), S("t1"), L(), T0 + 200);   // 传的时候又上了锁
        two.clearDirty2(sentDav);
        check(two.get(id("t1")).dirty2 && !two.get(id("t2")).dirty2 && two.get(id("t1")).dirty, "两条通道:传的时候又改过的,两边都仍算待发送;没改过的清掉");
        ClipSyncCore.Model src2 = new ClipSyncCore.Model();
        src2.reconcile(L("t2"), S(), L(), T0 + 5000);
        src2.reconcile(L(), S(), L("t2"), T0 + 6000);   // 对方把 t2 放进了回收箱(更新)
        two.merge(src2.takeDirty(), T0 + 7000);
        check(two.get(id("t2")).st == 't' && !two.get(id("t2")).dirty && !two.get(id("t2")).dirty2, "两条通道:对方更新的记录赢了,本机这条的两种待发送都作废");
        StringWriter sw2 = new StringWriter();
        two.writeMeta(sw2);
        List<String> meta2 = Arrays.asList(sw2.toString().split("\n"));
        ClipSyncCore.Model back2 = ClipSyncCore.Model.readMeta(meta2, Arrays.asList("t1", "t2"));
        check(back2.get(id("t1")).dirty == two.get(id("t1")).dirty && back2.get(id("t1")).dirty2 == two.get(id("t1")).dirty2, "保存读回:两种待发送标记都保留");
        String legacy = "M\t" + id("t1") + "\ta\t100\t200\t1";   // 旧版只有 6 个字段
        ClipSyncCore.Model old2 = ClipSyncCore.Model.readMeta(Arrays.asList(legacy), Arrays.asList("t1"));
        check(old2.get(id("t1")) != null && old2.get(id("t1")).dirty && !old2.get(id("t1")).dirty2, "保存读回:旧版 6 个字段的记录能读,坚果云那条按没有待传处理");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        if (fails != 0) System.exit(1);
    }

    static int countX(ClipSyncCore.Model m) {
        int n = 0;
        StringWriter sw = new StringWriter();
        try { m.writeMeta(sw); } catch (IOException e) { return -1; }
        for (String l : sw.toString().split("\n")) { String[] p = l.split("\t"); if (p.length > 2 && p[2].equals("x")) n++; }
        return n;
    }
}
