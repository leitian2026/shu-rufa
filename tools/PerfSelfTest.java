// 整条查询路径的耗时自测(只含 Java 部分:整句 / 模糊音整句 / 混拼 / 拼错容错):不需要 Android,JDK 17+ 即可。
// 模拟「一个键一个键地打一个长句」时每次按键要算多少,并且比较普通模式和省电模式。
// 在工程根目录执行:
//   D=app/src/main/java/com/example/myime
//   javac -encoding UTF-8 -d /tmp/perftest $D/Syllabary.java $D/SafeFile.java $D/LearnShare.java $D/UserFreq.java $D/UserChain.java \
//         $D/UserWords.java $D/Fuzzy.java $D/Typo.java $D/SentenceDecoder.java tools/stub/com/example/myime/ErrorHint.java \
//         tools/SentenceSelfTest.java tools/PerfSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/perftest PerfSelfTest
// 词库是 20 万条随机词条(每个位置都有很多同音词,比真实词库更难),数字是电脑上的,手机会慢几倍;
// 这里只检查「没有哪一步突然变得很贵」:每次按键的平均和最坏都要在预算内。
import com.example.myime.*;
import java.util.*;

public class PerfSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    public static void main(String[] a) {
        String[] syl = {"ba", "ni", "hao", "wo", "men", "ming", "tian", "qu", "kan", "shu", "zhong", "guo", "ren", "xi", "an",
                        "ma", "ai", "shi", "jie", "you", "de", "ta", "li", "hui", "lai", "zai", "xian", "dong", "xing", "fu"};
        Random rnd = new Random(21);
        int N = 200000;
        String[] big = new String[N];
        for (int i = 0; i < N; i++) {
            int k = 1 + rnd.nextInt(4);
            StringBuilder py = new StringBuilder();
            for (int j = 0; j < k; j++) { if (j > 0) py.append(' '); py.append(syl[rnd.nextInt(syl.length)]); }
            big[i] = "W" + i + "\t" + py + "\t" + (1 + rnd.nextInt(100000));
        }
        SentenceDecoder d = SentenceSelfTest.build(big);

        // 一个 24 个字母左右的长句
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 24) sb.append(syl[rnd.nextInt(syl.length)]);
        String full = sb.toString();
        // 带一个错字母的版本(第 10 个字母换成 q):走拼错容错
        String typo = full.substring(0, 9) + "q" + full.substring(10);

        // 预热
        for (int r = 0; r < 20; r++) for (int i = 4; i <= full.length(); i++) d.decodeTop(full.substring(0, i), null, null, null, 3, null, null);

        double[] normal = run(d, full, 3);
        double[] low = run(d, full, 1);
        System.out.println(String.format("INFO 打 %d 个字母的长句,每次按键:普通模式平均 %.3f p99 %.3f 最大 %.3f ms;省电模式平均 %.3f p99 %.3f 最大 %.3f ms",
                full.length(), normal[0], normal[1], normal[2], low[0], low[1], low[2]));
        check(normal[0] < 3.0 && normal[1] < 10.0, "普通模式:平均 < 3ms、p99 < 10ms");
        // 用中位数比(平均值容易被个别的垃圾回收 / JIT 抖动带偏):省电模式做的事是普通模式的子集
        check(low[3] <= normal[3] * 1.3 + 0.05, String.format("省电模式不比普通模式贵(中位数 %.3f / %.3f ms)", low[3], normal[3]));

        // 一个键一个键地打带错字母的句子:每次按键 = 整句(解释不了时返回空)+ 拼错容错(找写法 + 对最多 3 个写法拼整句)
        List<Double> samples = new ArrayList<>();
        for (int rep = 0; rep < 30; rep++) {
            for (int i = 4; i <= typo.length(); i++) {
                long k0 = System.nanoTime();
                String p = typo.substring(0, i);
                d.decodeTop(p, null, null, null, 3, null, null);
                int tried = 0;
                for (String v : Typo.variants(p, 8)) {
                    if (tried++ >= 3) break;
                    d.decode(v, null, null, null);
                }
                if (rep >= 5) samples.add((System.nanoTime() - k0) / 1e6);
            }
        }
        double[] st = stats(samples);
        System.out.println(String.format("INFO 带一个错字母的长句:每次按键平均 %.3f p99 %.3f 最大 %.3f ms(含拼错容错)", st[0], st[1], st[2]));
        check(st[0] < 5.0 && st[1] < 20.0, "带错字母:平均 < 5ms、p99 < 20ms");

        // 过期取消的意义:一次整句最贵多少(这就是取消能省下的上限)
        double one = 0;
        for (int r = 0; r < 200; r++) {
            long k0 = System.nanoTime();
            d.decodeTop(full, null, null, null, 3, null, null);
            one = Math.max(one, (System.nanoTime() - k0) / 1e6);
        }
        System.out.println(String.format("INFO 一次完整整句(k=3)最坏 %.3f ms", one));

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static double[] stats(List<Double> xs) {
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        double sum = 0;
        for (double x : sorted) sum += x;
        return new double[] {sum / sorted.size(), sorted.get((int) (sorted.size() * 0.99)), sorted.get(sorted.size() - 1), sorted.get(sorted.size() / 2)};
    }

    /** 逐个字母打 full:返回 {平均, p99, 最大, 中位数} 每次按键耗时(ms)。k = 整句最多几种切法。 */
    static double[] run(SentenceDecoder d, String full, int k) {
        List<Double> samples = new ArrayList<>();
        for (int rep = 0; rep < 60; rep++) {
            for (int i = 4; i <= full.length(); i++) {
                long k0 = System.nanoTime();
                d.decodeTop(full.substring(0, i), null, null, null, k, null, null);
                double ms = (System.nanoTime() - k0) / 1e6;
                if (rep >= 10) samples.add(ms);
            }
        }
        return stats(samples);
    }
}
