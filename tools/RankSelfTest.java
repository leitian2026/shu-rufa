// 词字排序(UserFreq / UserChain)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/ranktest app/src/main/java/com/example/myime/SafeFile.java \
//         app/src/main/java/com/example/myime/UserFreq.java app/src/main/java/com/example/myime/UserChain.java \
//         tools/stub/com/example/myime/ErrorHint.java tools/RankSelfTest.java
//   java -cp /tmp/ranktest RankSelfTest
// 覆盖:选了就排第一;同音高频字(的/得)的窗口规则;层级(这个编码 > 两词接词 > 一词接词 > 别的编码 > 词频);
//       时间戳严格递增;存盘重读;0.11 以前按次数存的旧文件迁移;超过上限删最久没用的;接词记录同理;非法字符。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RankSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    /** 模拟引擎:候选按 学习排序键(大的在前),没学过的(0)按给定顺序(代表词频)。 */
    static List<String> order(UserFreq u, String code, String... dict) {
        String[] win = u.recent(code);
        List<String> ds = Arrays.asList(dict);
        List<String> out = new ArrayList<>(ds);
        out.sort((a, b) -> {
            long ra = UserFreq.rank(u, code, a, win, 0L), rb = UserFreq.rank(u, code, b, win, 0L);
            if (ra != rb) return Long.compare(rb, ra);
            return Integer.compare(ds.indexOf(a), ds.indexOf(b));
        });
        return out;
    }

    static File tmp(String name) throws IOException {
        Path d = Files.createTempDirectory("ranktest");
        d.toFile().deleteOnExit();
        return new File(d.toFile(), name);
    }

    public static void main(String[] a) throws Exception {
        // 1. 没学过:完全按词频(给定顺序)
        UserFreq u = new UserFreq(tmp("p.txt"));
        check(!u.hasLearned(), "新文件没学过");
        check(order(u, "ke", "可", "课", "刻", "科").equals(List.of("可", "课", "刻", "科")), "没学过时按词频");

        // 2. 选了就排第一,旧的往后挤一位
        u.learn("ke", "科");
        check(order(u, "ke", "可", "课", "刻", "科").equals(List.of("科", "可", "课", "刻")), "选 1 次就排第一");
        u.learn("ke", "刻");
        check(order(u, "ke", "可", "课", "刻", "科").equals(List.of("刻", "科", "可", "课")), "再选一个,新的第一、旧的第二");
        u.learn("ke", "科");
        check(order(u, "ke", "可", "课", "刻", "科").get(0).equals("科"), "选回旧的又排第一(窗口里各 1 次、2 比 1)");

        // 3. 不同编码互不影响;别的编码下选过的排在词频前面(第 1 层)
        check(order(u, "kan", "看", "刻", "砍").equals(List.of("刻", "看", "砍")), "别的编码下选过的,排在没选过的前面");
        check(UserFreq.rank(u, "kan", "看", null, 0L) == 0L, "没选过的排序键是 0");

        // 4. 同音高频字:的 ×4 然后偶尔选一次 得,第一位不跳
        UserFreq d = new UserFreq(tmp("d.txt"));
        for (int i = 0; i < 4; i++) d.learn("de", "的");
        d.learn("de", "得");
        check(order(d, "de", "的", "得", "地").equals(List.of("的", "得", "地")), "的 ×4 + 得 ×1:的仍第一,得第二");
        d.learn("de", "得");
        check(order(d, "de", "的", "得", "地").get(0).equals("的"), "窗口 [得 得 的 的 的]:的 3 次 > 得 2 次,仍是的");
        d.learn("de", "得");
        check(order(d, "de", "的", "得", "地").get(0).equals("得"), "窗口 [得 得 得 的 的]:得 3 次 > 的 2 次,换成得");
        d.learn("de", "地");
        check(order(d, "de", "的", "得", "地").equals(List.of("得", "地", "的")), "窗口 [地 得 得 得 的]:得 3 次第一;地、的各 1 次,平局时新的在前");
        check(d.recent("de").length == UserFreq.WINDOW, "窗口最多 " + UserFreq.WINDOW + " 次");

        // 5. 平局:都只出现 1 次时,最近的在前(纯最近优先)
        UserFreq t = new UserFreq(tmp("t.txt"));
        t.learn("shi", "是"); t.learn("shi", "时"); t.learn("shi", "事");
        check(order(t, "shi", "是", "时", "事", "市").equals(List.of("事", "时", "是", "市")), "各 1 次:越近越靠前");
        // 窗口之外的老选择:仍按最近排,排在窗口内的后面、没选过的前面
        for (int i = 0; i < 5; i++) t.learn("shi", "十");
        List<String> o = order(t, "shi", "是", "时", "事", "市", "十");
        check(o.get(0).equals("十") && o.subList(1, 4).equals(List.of("事", "时", "是")) && o.get(4).equals("市"), "窗口外的老选择按最近排,在没选过的前面");

        // 6. 层级:这个编码 > 两词接词 > 一词接词 > 别的编码 > 词频
        UserFreq h = new UserFreq(tmp("h.txt"));
        h.learn("x", "甲");      // 别的编码下选过
        h.learn("ni", "乙");     // 这个编码选过
        long ts = 1_000_000L;
        long rPair = UserFreq.rank(h, "ni", "乙", h.recent("ni"), 0L);
        long rCtx2 = UserFreq.rank(h, "ni", "丙", null, ts | UserFreq.TWO);
        long rCtx1 = UserFreq.rank(h, "ni", "丁", null, ts);
        long rText = UserFreq.rank(h, "ni", "甲", h.recent("ni"), 0L);
        long rNone = UserFreq.rank(h, "ni", "戊", null, 0L);
        check(rPair > rCtx2 && rCtx2 > rCtx1 && rCtx1 > rText && rText > rNone && rNone == 0L, "层级顺序 4>3>2>1>0");
        check(UserFreq.rank(h, "ni", "丁", null, ts + 5) > UserFreq.rank(h, "ni", "丁", null, ts), "同层级里时间新的在前");
        check(UserFreq.textRank(h, "甲") == rText && UserFreq.textRank(h, "戊") == 0L && UserFreq.textRank(null, "甲") == 0L, "textRank 只看这个词选过没有");

        // 7. 时间戳严格递增(连着学很多次,同一毫秒内也不会重复)
        UserFreq m = new UserFreq(tmp("m.txt"));
        long prev = 0; boolean inc = true;
        for (int i = 0; i < 2000; i++) {
            m.learn("c" + (i % 3), "w" + i);
            long s = m.textStamp("w" + i);
            if (s <= prev) inc = false;
            prev = s;
        }
        check(inc, "2000 次学习的时间戳严格递增");

        // 8. 存盘重读,顺序不变
        File f = tmp("save.txt");
        UserFreq s1 = new UserFreq(f);
        s1.learn("ke", "可"); s1.learn("ke", "科"); s1.learn("ke", "科"); s1.learn("kan", "看");
        s1.saveIfDirty();
        UserFreq s2 = new UserFreq(f);
        check(s2.count() == 3, "重读后词数一样");
        check(order(s2, "ke", "可", "科", "课").equals(order(s1, "ke", "可", "科", "课")), "重读后顺序一样");
        check(Arrays.equals(s2.recent("ke"), s1.recent("ke")), "重读后窗口一样");
        s2.learn("ke", "课"); // 新的时间戳要比读进来的都大
        check(order(s2, "ke", "可", "科", "课").get(0).equals("科") || order(s2, "ke", "可", "科", "课").get(0).equals("课"), "重读后继续学习正常");
        check(s2.textStamp("课") > s2.textStamp("科"), "重读后新时间戳比旧的大");

        // 9. 旧格式(0.11 以前:t 词 次数 / p 编码 词 次数)迁移:次数多的排前面,迁移后重写成新格式
        File old = tmp("old.txt");
        SafeFile.write(old, w -> {
            w.write("t\t可\t30\n"); w.write("t\t课\t2\n"); w.write("t\t科\t8\n");
            w.write("p\tke\t可\t30\n"); w.write("p\tke\t课\t2\n"); w.write("p\tke\t科\t8\n");
        });
        UserFreq o1 = new UserFreq(old);
        check(o1.count() == 3, "旧格式读得进来");
        check(order(o1, "ke", "课", "科", "可", "刻").equals(List.of("可", "科", "课", "刻")), "旧格式:次数多的排前面");
        o1.learn("ke", "刻");
        check(order(o1, "ke", "课", "科", "可", "刻").equals(List.of("可", "刻", "科", "课")), "迁移后:可 是主导,新选的 刻 排第二、排在老的 科 前面(真实时间比迁移的大)");
        o1.learn("ke", "刻"); o1.learn("ke", "刻");
        check(order(o1, "ke", "课", "科", "可", "刻").get(0).equals("刻"), "迁移后连选 刻,刻 取代 可");
        o1.saveIfDirty();
        String txt = new String(Files.readAllBytes(old.toPath()), "UTF-8");
        check(txt.contains("\nT\t") && !txt.contains("\nt\t") && !txt.contains("\np\t"), "迁移后已重写成新格式");
        UserFreq o2 = new UserFreq(old);
        check(order(o2, "ke", "课", "科", "可", "刻").equals(order(o1, "ke", "课", "科", "可", "刻")), "迁移后的文件重读,顺序不变");

        // 9b. 迁移时窗口填初值:的 100 次、得 3 次、地 2 次 → 升级后偶尔选 得,的 仍是第一
        File seed = tmp("seed.txt");
        SafeFile.write(seed, w -> {
            w.write("t\t的\t100\n"); w.write("t\t得\t3\n"); w.write("t\t地\t2\n");
            w.write("p\tde\t的\t100\n"); w.write("p\tde\t得\t3\n"); w.write("p\tde\t地\t2\n");
            w.write("t\t甲\t1\n"); w.write("p\tq\t甲\t1\n");                       // 稀疏:只选过 1 次
            w.write("t\t乙\t2\n"); w.write("t\t丙\t1\n"); w.write("p\tz\t乙\t2\n"); w.write("p\tz\t丙\t1\n");
            w.write("t\t丁\t1\n"); w.write("t\t戊\t1\n"); w.write("p\tk\t丁\t1\n"); w.write("p\tk\t戊\t1\n");
        });
        UserFreq sd = new UserFreq(seed);
        check(order(sd, "de", "地", "得", "的").equals(List.of("的", "得", "地")), "迁移后顺序按次数");
        check(sd.recent("de") != null && sd.recent("de").length == 4 && Arrays.stream(sd.recent("de")).allMatch(x -> x.equals("的")), "的 100 次:窗口里是 的 ×4");
        sd.learn("de", "得");
        check(order(sd, "de", "地", "得", "的").get(0).equals("的"), "升级后第一次选 得,的 仍第一");
        sd.learn("de", "得"); sd.learn("de", "得");
        check(order(sd, "de", "地", "得", "的").get(0).equals("得"), "连着选 得 之后,得 取代 的");
        check(sd.recent("q").length == 1, "只选过 1 次的词只占窗口 1 个位置");
        sd.learn("q", "乙");
        check(order(sd, "q", "甲", "乙").get(0).equals("乙"), "稀疏的编码:新选的词直接排第一");
        check(order(sd, "z", "丙", "乙", "己").equals(List.of("乙", "丙", "己")), "乙 2 次、丙 1 次:乙在前");
        sd.learn("z", "丙");
        check(order(sd, "z", "丙", "乙", "己").get(0).equals("丙"), "丙再选一次,窗口里 2 比 2 平局,新的在前");
        check(sd.recent("k").length == 2, "各 1 次的两个词:窗口里各 1 个,没有谁占优");
        sd.learn("k", "戊");
        check(order(sd, "k", "丁", "戊").get(0).equals("戊"), "平局时新选的排第一");
        sd.saveIfDirty();
        UserFreq sd2 = new UserFreq(seed);
        check(Arrays.equals(sd2.recent("de"), sd.recent("de")) && order(sd2, "de", "地", "得", "的").equals(order(sd, "de", "地", "得", "的")), "填了初值的窗口存盘重读后不变");

        // 10. 超过上限删最久没用的
        UserFreq p = new UserFreq(tmp("prune.txt"));
        for (int i = 0; i <= 20_000; i++) p.learn("c", "w" + i);
        check(p.count() <= 15_000 + 1, "超过 2 万条后收缩到约 1.5 万 (实际 " + p.count() + ")");
        check(p.textStamp("w0") == 0L, "最久没用的被删了");
        check(p.textStamp("w20000") != 0L && p.textStamp("w19000") != 0L, "最近的都在");
        check(p.recent("c") != null && p.recent("c")[0].equals("w20000"), "窗口里最新的在前");

        // 11. 非法字符不记;清除
        UserFreq b = new UserFreq(tmp("bad.txt"));
        b.learn("ke", "a\tb"); b.learn("ke", "a\nb"); b.learn("k\te", "x"); b.learn("", "x"); b.learn("ke", "");
        check(b.count() == 0, "含制表/换行/空的不记");
        b.learn("ke", "可"); b.clear();
        check(!b.hasLearned() && b.recent("ke") == null, "清除后什么都没有");

        // 12. 接词记录:最近接的在前;两词上下文的在一词上下文的前面
        UserChain c = new UserChain(tmp("chain.txt"));
        c.learn("吃", "苹果"); c.learn("吃", "饭"); c.learn("吃", "面");
        check(c.next(null, "吃").equals(List.of("面", "饭", "苹果")), "接词:最近接的排第一");
        c.learn("吃", "苹果");
        check(c.next(null, "吃").get(0).equals("苹果"), "再接一次苹果,苹果排第一");
        c.learn("我" + UserChain.SEP + "吃", "饭");
        check(c.next("我", "吃").get(0).equals("饭"), "两词上下文的命中排最前");
        check(c.next("你", "吃").get(0).equals("苹果"), "没有两词记录就用一词记录");
        Map<String, Long> row = c.ctxRow("我", "吃");
        check((row.get("饭") & UserFreq.TWO) != 0 && (row.get("苹果") & UserFreq.TWO) == 0, "两词命中带标记,一词命中不带");
        check(c.ctxRow(null, "没有") == null && c.ctxRow(null, null) == null, "没有记录返回 null");
        for (int i = 0; i < 20; i++) c.learn("甲", "乙" + i);
        check(c.next(null, "甲").size() == 8 && c.next(null, "甲").get(0).equals("乙19"), "最多 8 个,最近的在前");

        // 13. 接词存盘重读 + 旧格式迁移 + 超限
        File cf = tmp("c2.txt");
        UserChain c1 = new UserChain(cf);
        c1.learn("吃", "饭"); c1.learn("吃", "面"); c1.saveIfDirty();
        check(new UserChain(cf).next(null, "吃").equals(List.of("面", "饭")), "接词重读后顺序一样");
        File cold = tmp("c3.txt");
        SafeFile.write(cold, w -> { w.write("c\t吃\t饭\t9\n"); w.write("c\t吃\t面\t1\n"); w.write("c\t吃\t汤\t4\n"); });
        UserChain co = new UserChain(cold);
        check(co.next(null, "吃").equals(List.of("饭", "汤", "面")), "接词旧格式:次数多的排前面");
        co.learn("吃", "面");
        check(co.next(null, "吃").get(0).equals("面"), "接词迁移后新接的排第一");
        co.saveIfDirty();
        String ct = new String(Files.readAllBytes(cold.toPath()), "UTF-8");
        check(ct.contains("\nC\t") && !ct.contains("\nc\t"), "接词迁移后已重写成新格式");
        UserChain big = new UserChain(tmp("c4.txt"));
        for (int i = 0; i < 20_600; i++) big.learn("a" + (i % 50), "b" + i);
        check(big.count() < 20_600 && big.count() <= 15_100, "接词超过 2 万对后收缩 (实际 " + big.count() + ")");
        check(big.next(null, "a" + (20_599 % 50)).contains("b20599"), "接词:最近的还在");
        c1.clear();
        check(c1.count() == 0 && c1.next(null, "吃").isEmpty(), "接词清除");

        System.out.println(fails == 0 ? "\nALL PASS" : "\nFAILED: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
