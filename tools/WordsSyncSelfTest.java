// 造的词多机同步(UserWords 的导出 / 合并,以及 LearnShare 带 @words 区块)的自测:不需要 Android,JDK 17+ 即可。
// 在工程根目录执行:
//   D=app/src/main/java/com/example/myime
//   javac -encoding UTF-8 -d /tmp/wstest $D/Syllabary.java $D/SafeFile.java $D/LearnShare.java $D/UserFreq.java $D/UserChain.java \
//         $D/UserWords.java tools/stub/com/example/myime/ErrorHint.java tools/WordsSyncSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/wstest WordsSyncSelfTest
// 覆盖:只导出可用的词、不合格的词不导出;合并(新增 / 取较大的次数和时间 / 幂等 / 交换律);清除时间之前的不收;
//       时间戳截断;坏行跳过;LearnShare 往返;老读法(不带 words)能读新文件、新读法能读老文件;learnedCount 不含合并进来的。
import com.example.myime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class WordsSyncSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static String[] S(String... a) { return a; }

    static File dir;
    static int n = 0;
    static File tmp(String name) { return new File(dir, (n++) + "_" + name); }

    static String export(UserWords w) throws IOException {
        StringWriter sw = new StringWriter();
        w.exportShared(sw);
        return sw.toString();
    }
    static List<String> lines(String s) {
        List<String> l = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.isEmpty()) l.add(x);
        return l;
    }
    static String dump(UserWords w) {
        UserWords.Snapshot s = w.snapshot();
        if (s == null) return "";
        List<String> l = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) l.add(s.texts[i] + "|" + s.keys[i] + "|" + s.counts[i]);
        Collections.sort(l);
        return String.join(",", l);
    }

    public static void main(String[] a) throws Exception {
        dir = Files.createTempDirectory("wstest").toFile();
        UserWords a1 = new UserWords(tmp("a.txt"));
        a1.add("张伟", S("zhang", "wei"), true);
        a1.add("我们明天", S("wo", "men", "ming", "tian"), false);   // 次数 1,不可用
        a1.add("第1名", S("di", "yi", "ming"), true);               // 含数字,不共享
        check(a1.learnedCount() == 3, "learnedCount 记自己学的次数");

        // 导出
        List<String> ex = lines(export(a1));
        check(ex.size() == 1 && ex.get(0).startsWith("W\t张伟\tzhang wei\t2\t"), "只导出可用且合格的词 " + ex);

        // 合并进空的
        UserWords b = new UserWords(tmp("b.txt"));
        long now = System.currentTimeMillis();
        int ch = b.mergeRows(ex, now);
        check(ch == 1 && dump(b).equals("张伟|zhangwei|2"), "合并进空表 " + dump(b));
        check(b.learnedCount() == 0, "合并进来的不算自己学的");
        check(b.mergeRows(ex, now) == 0, "重复合并不变(幂等)");

        // 取较大的次数和时间
        UserWords c1 = new UserWords(tmp("c1.txt"));
        UserWords c2 = new UserWords(tmp("c2.txt"));
        for (int i = 0; i < 4; i++) c1.add("王芳", S("wang", "fang"), true);   // 次数 5
        c2.add("王芳", S("wang", "fang"), true);                              // 次数 2
        c2.add("李娜", S("li", "na"), true);
        String e1 = export(c1), e2 = export(c2);
        UserWords m1 = new UserWords(tmp("m1.txt")), m2 = new UserWords(tmp("m2.txt"));
        m1.mergeRows(lines(e1), now); m1.mergeRows(lines(e2), now);
        m2.mergeRows(lines(e2), now); m2.mergeRows(lines(e1), now);
        check(dump(m1).equals(dump(m2)), "合并顺序无关 " + dump(m1) + " / " + dump(m2));
        check(dump(m1).contains("王芳|wangfang|5") && dump(m1).contains("李娜|lina|2"), "同一个词取较大的次数");
        // 本机次数比对方大时,对方的合并不会改小
        int again = m1.mergeRows(lines(e2), now);
        check(again == 0 && dump(m1).contains("王芳|wangfang|5"), "对方更小的不改本机");

        // 清除时间之前的不收
        UserWords f = new UserWords(tmp("f.txt"));
        long future = now + 1000;
        check(f.mergeRows(lines(e1), now, Long.MAX_VALUE / 2) == 0 && f.snapshot() == null, "早于清除时间的不收");
        // 时间戳截断:远将来的时间被截成 now
        List<String> far = new ArrayList<>();
        far.add("W\t赵云\tzhao yun\t3\t" + (now + 10_000_000_000L));
        UserWords g = new UserWords(tmp("g.txt"));
        check(g.mergeRows(far, now) == 1, "远将来的时间戳也能合并");
        g.saveIfDirty();
        String saved = new String(Files.readAllBytes(new File(dir, (n - 1) + "_g.txt").toPath()), StandardCharsets.UTF_8);
        check(saved.contains("\t" + now + "\n"), "时间戳被截成 now");

        // 坏行跳过
        List<String> bad = new ArrayList<>(Arrays.asList(
            "W\t好人\thao ren\t2\t100",                 // 好
            "W\t坏\thuai\t2\t100",                      // 单字
            "W\t好友\thao\t2\t100",                     // 音节数对不上
            "W\t好友\tHao You\t2\t100",                 // 大写
            "W\t好友\thao you\t1\t100",                 // 次数 1
            "W\t好友\thao you\tx\t100",                 // 次数不是数
            "W\t好友\thao you\t2\t0",                   // 时间 0
            "W\t好1友\thao yi you\t2\t100",             // 含数字的词
            "W\t一二三四五六七八九\tyi er san si wu liu qi ba jiu\t2\t100",   // 太长
            "W\t好友\thaoyouhaoyou you\t2\t100",        // 音节太长
            "X\t好人\thao ren\t2\t100", "乱七八糟", ""));
        UserWords h = new UserWords(tmp("h.txt"));
        check(h.mergeRows(bad, now) == 1 && dump(h).equals("好人|haoren|2"), "坏行都被跳过 " + dump(h));

        // LearnShare 往返:带 words
        UserFreq p1 = new UserFreq(tmp("p1.txt")), s1 = new UserFreq(tmp("s1.txt"));
        UserChain k1 = new UserChain(tmp("k1.txt"));
        p1.learn("zhang", "张");
        StringWriter sw = new StringWriter();
        LearnShare.write(sw, p1, s1, k1, a1);
        String text = sw.toString();
        check(text.contains("@words\n") && text.contains("W\t张伟\tzhang wei\t2\t") && !text.contains("第1名"), "带 @words 区块,不含不合格的词");
        UserFreq p2 = new UserFreq(tmp("p2.txt")), s2 = new UserFreq(tmp("s2.txt"));
        UserChain k2 = new UserChain(tmp("k2.txt"));
        UserWords w2 = new UserWords(tmp("w2.txt"));
        int merged = LearnShare.merge(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), p2, s2, k2, w2, now, 0L);
        check(merged >= 2 && dump(w2).equals("张伟|zhangwei|2") && p2.hasLearned(), "往返:词和选词记录都合并进来 merged=" + merged);
        // 老读法(不传 words):不报错,别的区块照常合并,@words 区块被忽略
        UserFreq p3 = new UserFreq(tmp("p3.txt")), s3 = new UserFreq(tmp("s3.txt"));
        UserChain k3 = new UserChain(tmp("k3.txt"));
        int old = LearnShare.merge(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), p3, s3, k3, now, 0L);
        check(old >= 1 && p3.hasLearned(), "老读法能读带 @words 的新文件");
        // 新读法读不带 words 的老文件
        StringWriter sw2 = new StringWriter();
        LearnShare.write(sw2, p1, s1, k1);
        check(!sw2.toString().contains("@words"), "不传 words 就不写 @words 区块");
        UserWords w4 = new UserWords(tmp("w4.txt"));
        UserFreq p4 = new UserFreq(tmp("p4.txt")), s4 = new UserFreq(tmp("s4.txt"));
        UserChain k4 = new UserChain(tmp("k4.txt"));
        LearnShare.merge(new ByteArrayInputStream(sw2.toString().getBytes(StandardCharsets.UTF_8)), p4, s4, k4, w4, now, 0L);
        check(w4.snapshot() == null && p4.hasLearned(), "新读法能读不带 @words 的老文件");
        // 被截断(没有 #end)仍然报错
        boolean threw = false;
        try {
            String cut = text.substring(0, text.indexOf("#end"));
            LearnShare.merge(new ByteArrayInputStream(cut.getBytes(StandardCharsets.UTF_8)), p4, s4, k4, w4, now, 0L);
        } catch (IOException e) { threw = true; }
        check(threw, "没有 #end 仍判为不完整");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
