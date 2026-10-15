// 拼音里「笔画筛选」用的反查索引(StrokeIndex)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/sftest app/src/main/java/com/example/myime/StrokeIndex.java tools/StrokeFilterSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/sftest StrokeFilterSelfTest
// 覆盖:按第一个字的笔顺前缀匹配;词只看第一个字;同一个字几种笔顺任意一种对得上就算;笔画表里没有的字不匹配;
//       扩展区(两个 char 的字)能查;不是单字的条目不收;空串 / null;速度(建索引 + 大量查询)。
import com.example.myime.*;
import java.util.*;

public class StrokeFilterSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    public static void main(String[] a) {
        String[] texts = { "一", "二", "三", "十", "土", "干", "田", "田", "木", "\uD840\uDC00", "十分", "" };
        String[] codes = { "h", "hh", "hhh", "hs", "hsh", "hhs", "sznhh", "szhnh", "hspn", "hsp", "hs", "h" };
        StrokeIndex ix = new StrokeIndex(texts, codes);

        check(ix.hasPrefix("一", "h"), "一 以 h 开头");
        check(!ix.hasPrefix("一", "s"), "一 不以 s 开头");
        check(ix.hasPrefix("二", "h") && ix.hasPrefix("二", "hh"), "二 以 h / hh 开头");
        check(!ix.hasPrefix("二", "hhh"), "二 只有两笔,hhh 不匹配");
        check(ix.hasPrefix("十", "hs") && !ix.hasPrefix("十", "hsp"), "十:hs 是,hsp 不是");
        check(ix.hasPrefix("土", "hs") && ix.hasPrefix("干", "hh") && !ix.hasPrefix("土", "hh"), "土 和 干 第二笔不同,能区分");

        // 词只看第一个字
        check(ix.hasPrefix("十分", "hs"), "十分:看第一个字十");
        check(!ix.hasPrefix("十分", "hh"), "十分:第一个字不是 hh");
        check(!ix.hasPrefix("分十", "hs"), "分十:第一个字分不在笔画表里,即使第二个字是十也不算");

        // 同一个字两种笔顺:任意一种对得上
        check(ix.hasPrefix("田", "sznhh") && ix.hasPrefix("田", "szhnh") && ix.hasPrefix("田", "sz"), "田 两种笔顺都能匹配");
        check(!ix.hasPrefix("田", "h"), "田 两种笔顺都不以 h 开头");

        // 笔画表里没有的字、空串、null
        check(!ix.hasPrefix("龘", "h"), "笔画表里没有的字不匹配");
        check(!ix.hasPrefix("", "h"), "空串不匹配");
        check(!ix.hasPrefix(null, "h"), "null 不匹配");
        check(ix.hasPrefix("木", ""), "前缀为空:字在表里就算");
        check(!ix.hasPrefix("龘", ""), "前缀为空:字不在表里不算");

        // 扩展区字(Java 里是两个 char)
        check(ix.hasPrefix("\uD840\uDC00", "hsp"), "扩展区字能查");
        check(ix.hasPrefix("\uD840\uDC00" + "字", "hs"), "扩展区字开头的词能查");

        // 空文本条目不收:8 个单字 + 扩展区字 = 10 条里的 田 两条也算,共 10;"十分" 和 "" 不收
        check(ix.size() == 10, "只收单字条目,共 10 条: " + ix.size());

        // 速度:15 万条、1 百万次查询
        int n = 150_000;
        String[] t = new String[n];
        String[] c = new String[n];
        Random r = new Random(7);
        String al = "hspnz";
        for (int i = 0; i < n; i++) {
            t[i] = new String(Character.toChars(0x4E00 + r.nextInt(90_000)));
            int len = 2 + r.nextInt(14);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < len; k++) sb.append(al.charAt(r.nextInt(5)));
            c[i] = sb.toString();
        }
        long t0 = System.nanoTime();
        StrokeIndex big = new StrokeIndex(t, c);
        long build = (System.nanoTime() - t0) / 1_000_000;
        check(build < 800, "15 万条建索引 " + build + " ms(预算 800 ms,手机会慢几倍,只建一次)");
        t0 = System.nanoTime();
        int hit = 0;
        String[] probes = new String[1000];
        for (int i = 0; i < probes.length; i++) probes[i] = t[r.nextInt(n)];
        for (int rep = 0; rep < 1000; rep++) {
            for (String p : probes) if (big.hasPrefix(p, "hs")) hit++;
        }
        long q = (System.nanoTime() - t0) / 1_000_000;
        check(q < 1500, "100 万次查询 " + q + " ms(预算 1500 ms)命中 " + hit);

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        if (fails != 0) System.exit(1);
    }
}
