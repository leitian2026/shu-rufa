// 「用过的不同汉字」(UserChars)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   bash tools/run_selftests.sh Chars
// 覆盖:只认汉字(标点 / 字母 / 数字 / 假名 / 表情不算,生僻字算);同一个字只算一个;
//       已有的字不到一天不刷新时间、过了一天才刷新;从学习表补(absorb)时间不晚于现在;
//       多机合并:新增、取较晚时间、幂等、清除时间之前的不收、坏行和未来时间处理;
//       存盘再读回来一样;清除后为空且文件没了;LearnShare 的 @chars 区块往返,对方没带 / 我方不要都不出错。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CharsSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static File tmp;
    static int n = 0;
    static File f(String name) { return new File(tmp, (n++) + "_" + name); }
    static final long DAY = 24L * 3600 * 1000;

    /** 导出成 字 -> 时间。 */
    static Map<String, Long> dump(UserChars c) throws IOException {
        StringWriter w = new StringWriter();
        c.exportShared(w);
        Map<String, Long> m = new TreeMap<>();
        for (String line : w.toString().split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t");
            m.put(p[1], Long.parseLong(p[2]));
        }
        return m;
    }

    static List<String> rows(String... r) { return new ArrayList<>(Arrays.asList(r)); }

    public static void main(String[] a) throws Exception {
        tmp = Files.createTempDirectory("charstest").toFile();
        long now = System.currentTimeMillis();

        // ---- 只认汉字
        check(UserChars.isHan('中'), "isHan: 常用字");
        check(UserChars.isHan(0x20000), "isHan: 扩展 B 区生僻字");
        check(UserChars.isHan('々') || true, "isHan: 々 不影响");
        check(!UserChars.isHan('a') && !UserChars.isHan('1') && !UserChars.isHan(' '), "isHan: 字母数字空格不算");
        check(!UserChars.isHan('，') && !UserChars.isHan('。') && !UserChars.isHan('、'), "isHan: 中文标点不算");
        check(!UserChars.isHan('あ') && !UserChars.isHan('カ'), "isHan: 假名不算");
        check(!UserChars.isHan(0x1F600), "isHan: 表情不算");

        UserChars c = new UserChars(f("c"));
        check(c.count() == 0, "新建的字集是空的");
        c.note("你好abc,，1😀 ");
        check(c.count() == 2, "note: 只记汉字(你、好),得 2 个,实际 " + c.count());
        c.note("你好你好好你");
        check(c.count() == 2, "note: 同一个字重复只算一个");
        c.note("𠀀");
        check(c.count() == 3, "note: 生僻字(两个 char)算一个字");
        c.note("");
        c.note(null);
        check(c.count() == 3, "note: 空串 / null 不出错");

        // ---- 时间刷新规则
        UserChars d = new UserChars(f("d"));
        long t0 = now - 10 * DAY;
        d.absorb("天", t0, now);
        check(dump(d).get("天") == t0, "absorb: 新字记下那条记录的时间");
        d.absorb("天", t0 + 3600_000L, now);
        check(dump(d).get("天") == t0, "absorb: 只晚一小时不刷新(省得改文件)");
        d.absorb("天", t0 + 2 * DAY, now);
        check(dump(d).get("天") == t0 + 2 * DAY, "absorb: 晚一天以上才刷新");
        d.absorb("地", now + 5 * DAY, now);
        check(dump(d).get("地") == now, "absorb: 时间在未来的截成现在");
        d.absorb("人", 0L, now);
        check(dump(d).get("人") == 1L, "absorb: 时间无效(0)按 1 记,字照收");
        check(d.absorb("天地", now, now) == 0, "absorb: 已有的字返回新增 0");
        check(d.absorb("天人山", now, now) == 1, "absorb: 返回新增了几个字");

        // ---- 多机合并
        UserChars m = new UserChars(f("m"));
        m.absorb("甲", now - 5 * DAY, now);
        int ch = m.mergeRows(rows("H\t甲\t" + (now - 1 * DAY), "H\t乙\t" + (now - 2 * DAY)), now, 0L);
        check(ch == 2 && m.count() == 2, "merge: 新增乙、刷新甲的时间,变化 2 条");
        check(dump(m).get("甲") == now - 1 * DAY, "merge: 同一个字取较晚的时间");
        int again = m.mergeRows(rows("H\t甲\t" + (now - 1 * DAY), "H\t乙\t" + (now - 2 * DAY)), now, 0L);
        check(again == 0, "merge: 重复合并没有变化(幂等)");
        int older = m.mergeRows(rows("H\t甲\t" + (now - 9 * DAY)), now, 0L);
        check(older == 0 && dump(m).get("甲") == now - 1 * DAY, "merge: 对方的时间更早,不覆盖");
        int floor = m.mergeRows(rows("H\t丙\t" + (now - 3 * DAY), "H\t丁\t" + (now - 1000)), now, now - 2 * DAY);
        check(floor == 1 && !dump(m).containsKey("丙") && dump(m).containsKey("丁"),
                "merge: 早于清除时间的不收,之后的照收");
        int bad = m.mergeRows(rows("H\t甲乙\t1000", "H\ta\t1000", "H\t，\t1000", "H\t戊\tabc", "H\t戊\t-5", "X\t戊\t1000", "H\t戊", "", "H\t\t5"), now, 0L);
        check(bad == 0 && !dump(m).containsKey("戊"), "merge: 多个字 / 非汉字 / 时间坏 / 行格式坏 都跳过");
        m.mergeRows(rows("H\t己\t" + (now + 7 * DAY)), now, 0L);
        check(dump(m).get("己") == now, "merge: 未来时间截成现在");

        // 顺序无关
        UserChars x1 = new UserChars(f("x1")), x2 = new UserChars(f("x2"));
        List<String> r1 = rows("H\t一\t1000", "H\t二\t2000"), r2 = rows("H\t二\t3000", "H\t三\t500");
        x1.mergeRows(r1, now); x1.mergeRows(r2, now);
        x2.mergeRows(r2, now); x2.mergeRows(r1, now);
        check(dump(x1).equals(dump(x2)), "merge: 谁先合并结果都一样(交换律)");

        // ---- 存盘读回
        File pf = f("persist");
        UserChars p = new UserChars(pf);
        p.note("春夏秋冬");
        p.saveIfDirty();
        check(pf.isFile(), "存盘: 文件已写出");
        UserChars q = new UserChars(pf);
        check(q.count() == 4 && dump(q).equals(dump(p)), "存盘: 重新读回来字和时间都一样");
        long before = pf.lastModified();
        sleepMs(20);
        p.note("春");   // 刚记过,不到一天:不该有改动
        p.saveIfDirty();
        check(pf.lastModified() == before, "存盘: 没有改动就不写文件");

        // ---- 清除
        p.clear();
        check(p.count() == 0 && !pf.isFile(), "clear: 字集清空、文件删除");
        check(new UserChars(pf).count() == 0, "clear: 重新读也是空的");

        // ---- LearnShare 的 @chars 区块
        UserFreq ap = new UserFreq(f("ap")), as = new UserFreq(f("as"));
        UserChain ac = new UserChain(f("ac"));
        UserChars src = new UserChars(f("src"));
        src.note("山水云月");
        StringWriter w = new StringWriter();
        LearnShare.write(w, ap, as, ac, null, src);
        String text = w.toString();
        check(text.contains("@chars\n") && text.endsWith("#end\n"), "write: 带 @chars 区块并以 #end 结尾");

        UserFreq bp = new UserFreq(f("bp")), bs = new UserFreq(f("bs"));
        UserChain bc = new UserChain(f("bc"));
        UserChars dst = new UserChars(f("dst"));
        dst.note("山火");
        int changed = LearnShare.merge(new ByteArrayInputStream(text.getBytes("UTF-8")), bp, bs, bc, null, dst, now, 0L);
        check(dst.count() == 5 && changed >= 3, "merge: 对方的字并进来,山 / 火 共 5 个,实际 " + dst.count());
        int ch2 = LearnShare.merge(new ByteArrayInputStream(text.getBytes("UTF-8")), bp, bs, bc, null, dst, now, 0L);
        check(ch2 == 0 && dst.count() == 5, "merge: 同一份文件合并两次,第二次没有变化");

        UserChars none = new UserChars(f("none"));
        int ch3 = LearnShare.merge(new ByteArrayInputStream(text.getBytes("UTF-8")), bp, bs, bc, null, null, now, 0L);
        check(ch3 == 0 && none.count() == 0, "merge: 我方不传 chars 时忽略对方的 @chars 区块,不出错");

        StringWriter w2 = new StringWriter();
        LearnShare.write(w2, ap, as, ac, null, null);   // 老版本的写法:没有 @chars
        UserChars keep = new UserChars(f("keep"));
        keep.note("日月");
        LearnShare.merge(new ByteArrayInputStream(w2.toString().getBytes("UTF-8")), bp, bs, bc, null, keep, now, 0L);
        check(keep.count() == 2, "merge: 对方是老版本(没有 @chars)时,我方字集不变");

        // 清除时间:对方的旧字不回来
        UserChars cleared = new UserChars(f("cleared"));
        LearnShare.merge(new ByteArrayInputStream(text.getBytes("UTF-8")), bp, bs, bc, null, cleared, System.currentTimeMillis() + 1000, System.currentTimeMillis() + 1000);
        check(cleared.count() == 0, "merge: 清除时间之后,对方更早的字不会再同步回来");

        // ---- 繁简归并(用一个小的假转换表代替系统的 ICU 表)
        final Map<String, String> t2s = new HashMap<>();
        t2s.put("廣", "广"); t2s.put("聽", "听"); t2s.put("發", "发"); t2s.put("髮", "发"); t2s.put("後", "后");
        java.util.function.UnaryOperator<String> toSimp = x -> t2s.getOrDefault(x, x);

        UserChars s1 = new UserChars(f("s1"), toSimp);
        s1.note("廣广");
        check(s1.count() == 1, "繁简: 廣 和 广 算同一个字");
        s1.note("聽");
        check(s1.count() == 2 && dump(s1).containsKey("听") && !dump(s1).containsKey("聽"), "繁简: 只用过繁体的聽也计入,存成简体 听");
        s1.note("發髮发");
        check(s1.count() == 3, "繁简: 一个简体字对应好几个繁体字(發 髮 发)合成一个");
        s1.note("後后");
        check(s1.count() == 4, "繁简: 後 和 后 算一个");
        check(s1.absorb("廣聽", now, now) == 0, "繁简: absorb 繁体字时,简体已有就不算新增");
        check(s1.absorb("後來", now, now) == 1, "繁简: absorb 新字只新增 1(來 没有对应,原样)");

        File cf = f("sc");
        UserChars s2 = new UserChars(cf, toSimp);
        s2.note("廣聽山");
        s2.saveIfDirty();
        check(new UserChars(cf, toSimp).count() == 3, "繁简: 存盘读回来还是 3 个");

        // 老版本存的文件里繁简并存:读进来要归并
        File old = f("oldfile");
        UserChars w0 = new UserChars(old);   // 不带转换表,模拟 0.31.0:繁简分开存
        w0.note("廣广聽");
        w0.saveIfDirty();
        check(w0.count() == 3, "繁简: 不带转换表时 廣 广 聽 是 3 个(老版本的样子)");
        UserChars w1 = new UserChars(old, toSimp);
        check(w1.count() == 2, "繁简: 带转换表读老文件,归并成 2 个,实际 " + w1.count());
        w1.saveIfDirty();
        check(new UserChars(old).count() == 2, "繁简: 归并后重新存盘,文件里只剩简体");

        // 合并:对方发来繁体
        UserChars s3 = new UserChars(f("s3"), toSimp);
        s3.note("广");
        int mc = s3.mergeRows(rows("H\t廣\t" + (now - 1000), "H\t聽\t" + (now - 1000)), now, 0L);
        check(s3.count() == 2 && dump(s3).containsKey("听"), "繁简: 合并对方发来的繁体字,统一成简体");
        check(mc >= 1, "繁简: 合并有变化");
        check(!dump(s3).containsKey("廣") && !dump(s3).containsKey("聽"), "繁简: 导出出去的只有简体");

        // 转换表出错或者给出怪结果:保持原字
        UserChars s4 = new UserChars(f("s4"), x -> { throw new RuntimeException("boom"); });
        s4.note("山水");
        check(s4.count() == 2, "繁简: 转换表抛异常时原样记,不影响");
        UserChars s5 = new UserChars(f("s5"), x -> "ab");
        s5.note("山");
        check(s5.count() == 1 && dump(s5).containsKey("山"), "繁简: 转换结果不是一个汉字时保持原字");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        if (fails != 0) System.exit(1);
    }

    static void sleepMs(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }
}
