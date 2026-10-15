// 笔画组词(StrokeWords + StrokeIndex.matchesCp)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/swtest app/src/main/java/com/example/myime/{StrokeIndex,StrokeWords,TextFilter,WordScore}.java tools/StrokeWordsSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/swtest StrokeWordsSelfTest
// 覆盖:每个字的笔画段核对(前缀、* 任意一笔、空段 = 任意字);词的字数不够;只挑以首字候选开头的词;
//       按分数留前 max 个;扫描上限;单字本身和多音字重复不收;多个首字;空输入;速度。
import com.example.myime.*;
import java.util.*;

public class StrokeWordsSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static String[] texts, textKeys;
    static int[] freqs, order;

    static void build(String[][] words) {
        int n = words.length;
        texts = new String[n];
        freqs = new int[n];
        for (int i = 0; i < n; i++) { texts[i] = words[i][0]; freqs[i] = Integer.parseInt(words[i][1]); }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> texts[x].compareTo(texts[y]));
        order = new int[n];
        textKeys = new String[n];
        for (int i = 0; i < n; i++) { order[i] = idx[i]; textKeys[i] = texts[idx[i]]; }
    }

    static String join(String[] a) { return String.join(",", a); }

    public static void main(String[] a) {
        // 字 → 笔画(随便编的,只要前缀关系清楚)
        String[] ct = { "中", "国", "人", "大", "文", "小", "中" };
        String[] cc = { "szhs", "szhhshhnh", "pn", "hpn", "nhpn", "sspn", "szhsh" };   // 中 有两种笔顺
        StrokeIndex ix = new StrokeIndex(ct, cc);

        build(new String[][] {
            {"中国", "1000"}, {"中文", "500"}, {"中大", "50"}, {"中国人", "300"}, {"中间", "200"},
            {"人大", "100"}, {"人民", "900"}, {"大人", "400"}, {"大国", "350"}, {"国人", "250"},
            {"中", "9999"}, {"中国", "10"},   // 单字本身;多音字同一个词的第二条
        });

        // matchesCp
        check(ix.matchesCp('中', "szhs") && ix.matchesCp('中', "szhsh") && ix.matchesCp('中', "sz"), "matchesCp 前缀 / 两种笔顺");
        check(!ix.matchesCp('中', "szhsha"), "笔画比字的笔顺还长不匹配");
        check(ix.matchesCp('文', "n*pn") && ix.matchesCp('文', "*h*n") && !ix.matchesCp('文', "h*pn"), "* 代表任意一笔");
        check(ix.matchesCp('龘', "") && !ix.matchesCp('龘', "h"), "空段:任意字(不看笔画表);非空段:表里没有的字不匹配");

        // accept
        TextFilter f = StrokeWords.accept(ix, new String[] {"szhs", "szhhshhnh"});
        check(f.accept("中国") && f.accept("中国人"), "中国、中国人:第二个字 国 对得上");
        check(!f.accept("中文") && !f.accept("中间"), "中文(第二字笔画不对)、中间(间不在表里)不要");
        check(!f.accept("中"), "字数不够不要");
        check(StrokeWords.accept(ix, new String[] {"szhs", "n*pn"}).accept("中文"), "第二段带 * 能匹配 文");
        TextFilter any2 = StrokeWords.accept(ix, new String[] {"szhs", ""});
        check(any2.accept("中文") && any2.accept("中间") && !any2.accept("中"), "尾巴是空段:第二位任意,但至少两个字");
        TextFilter three = StrokeWords.accept(ix, new String[] {"szhs", "szhhshhnh", "pn"});
        check(three.accept("中国人") && !three.accept("中国"), "三段:要三个字,第三个字是 人");

        // 整字(exact):每个字的笔画数必须刚好等于那一段的长度
        check(ix.matchesCp('国', "szhhshhnh", true) && !ix.matchesCp('国', "szhhshhn", true) && ix.matchesCp('国', "szhhshhn", false),
              "matchesCp exact:刚好 9 画才行,前缀不行");
        check(ix.matchesCp('文', "n*pn", true) && !ix.matchesCp('文', "n*p", true), "exact 和 * 一起用:n*pn 刚好 4 画");
        check(ix.matchesCp('中', "szhs", true) && ix.matchesCp('中', "szhsh", true), "两种笔顺各自按自己的长度算");
        check(StrokeWords.accept(ix, new String[] {"szhs", "szhhshhnh"}, true).accept("中国")
              && !StrokeWords.accept(ix, new String[] {"szhs", "szhhshh"}, true).accept("中国")
              && StrokeWords.accept(ix, new String[] {"szhs", "szhhshh"}, false).accept("中国"),
              "accept exact:第二个字笔画不够就不要,不 exact 时前缀可以");
        check(StrokeWords.accept(ix, new String[] {"szhs", ""}, true).accept("中间"), "exact 下空段仍是任意字");

        // 笔画数 / 笔顺
        check(ix.strokeCount("国") == 9 && ix.strokeCount("中国") == 13 && ix.strokeCount("人") == 2, "strokeCount 单字和词(取第一种笔顺)");
        check(ix.strokeCount("中间") == -1 && ix.strokeCount("") == -1 && ix.strokeCount(null) == -1, "有字不在表里 / 空 / null → -1");
        check("szhs".equals(ix.firstCode('中')) && ix.firstCode('龘') == null, "firstCode 取第一种笔顺;没有返回 null");

        // pick
        WordScore byFreq = (t, fr) -> fr;
        String[] r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), f, byFreq, 10, 100000);
        check(join(r).equals("中国,中国人"), "中 + [中, 国]:按词频 → " + join(r));

        r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), any2, byFreq, 2, 100000);
        check(join(r).equals("中国,中文"), "max=2 留词频最高的两个 → " + join(r));

        WordScore exactFirst = (t, fr) -> (t.codePointCount(0, t.length()) == 2 ? 1L << 40 : 0L) | fr;
        r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), any2, exactFirst, 10, 100000);
        check(r.length == 5 && r[0].equals("中国") && r[1].equals("中文") && r[4].equals("中国人") ? true : join(r).equals("中国,中文,中间,中大,中国人"),
              "字数刚好的排前面 → " + join(r));

        // 按词排序后以 中 开头的依次是:中(单字)、中国、中国(第二条)、中国人、中大、中文、中间;单字和重复也占扫描数
        r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), any2, byFreq, 10, 2);
        check(join(r).equals("中国"), "扫描上限 2:只看排在最前的两条 → " + join(r));
        r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), any2, byFreq, 10, 4);
        check(join(r).equals("中国,中国人"), "扫描上限 4:看到中国人为止,后面的中大、中文、中间没看 → " + join(r));

        check(!Arrays.asList(StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), any2, byFreq, 10, 100000)).contains("中"),
              "单字本身不收");
        int cnt = 0;
        for (String s : StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), any2, byFreq, 10, 100000)) if (s.equals("中国")) cnt++;
        check(cnt == 1, "多音字同一个词只出现一次");

        // 多个首字:人、大 开头,第二个字是 国
        TextFilter guo = StrokeWords.accept(ix, new String[] {"", "szhhshhnh"});
        r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("大", "人"), guo, byFreq, 10, 100000);
        check(join(r).equals("大国"), "首字 大、人 + 第二字 国 → " + join(r));
        r = StrokeWords.pick(textKeys, order, texts, freqs, List.of("国", "中"), guo, byFreq, 10, 100000);
        check(join(r).equals("中国,中国人") , "首字 国、中 + 第二字 国(国人 的第二字是 人,不要) → " + join(r));

        check(StrokeWords.pick(textKeys, order, texts, freqs, List.of(), f, byFreq, 10, 100).length == 0, "没有首字 → 空");
        check(StrokeWords.pick(textKeys, order, texts, freqs, List.of("中"), f, byFreq, 0, 100).length == 0, "max=0 → 空");
        check(StrokeWords.pick(textKeys, order, texts, freqs, List.of("龘"), any2, byFreq, 10, 100).length == 0, "没有以它开头的词 → 空");

        // 速度:20 万个词,常用首字下有几千个词,扫描上限 20000
        int n = 200_000;
        Random rnd = new Random(3);
        String[][] big = new String[n][];
        String[] firsts = new String[40];
        for (int i = 0; i < firsts.length; i++) firsts[i] = new String(Character.toChars(0x4E00 + i * 7));
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(rnd.nextInt(3) == 0 ? firsts[rnd.nextInt(firsts.length)] : new String(Character.toChars(0x4E00 + rnd.nextInt(20000))));
            int len = 1 + rnd.nextInt(3);
            for (int k = 0; k < len; k++) sb.append(new String(Character.toChars(0x4E00 + rnd.nextInt(3000))));
            big[i] = new String[] { sb.toString(), String.valueOf(1 + rnd.nextInt(100000)) };
        }
        build(big);
        String[] bt = new String[3000];
        String[] bc = new String[3000];
        String al = "hspnz";
        for (int i = 0; i < 3000; i++) {
            bt[i] = new String(Character.toChars(0x4E00 + i));
            StringBuilder sb = new StringBuilder();
            int len = 3 + rnd.nextInt(10);
            for (int k = 0; k < len; k++) sb.append(al.charAt(rnd.nextInt(5)));
            bc[i] = sb.toString();
        }
        StrokeIndex bix = new StrokeIndex(bt, bc);
        TextFilter bf = StrokeWords.accept(bix, new String[] {"", "h"});
        List<String> fl = Arrays.asList(firsts);
        long t0 = System.nanoTime();
        int total = 0;
        for (int rep = 0; rep < 50; rep++) total += StrokeWords.pick(textKeys, order, texts, freqs, fl, bf, byFreq, 40, 8000).length;
        long per = (System.nanoTime() - t0) / 50 / 1000;
        check(per < 15000, "40 个首字、20 万词库、扫描上限 8000:每次 " + per + " µs(预算 15 ms,手机会慢几倍;只在用了分字键时才跑)结果数 " + total / 50);

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        if (fails != 0) System.exit(1);
    }
}
