// 多处拼错容错(FuzzyKey)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/fuzzytest app/src/main/java/com/example/myime/{Syllabary,Typo,FuzzyKey}.java tools/FuzzyKeySelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/fuzzytest FuzzyKeySelfTest
// 覆盖:写反;写反 + 末尾多打(guiquij → guiqiu);同样差的取词频高的;代价小的优先;太短 / 差太多不给;
//       合法键本身不给;取消;速度(大词库 + 最坏输入)。
import com.example.myime.*;
import java.util.*;

public class FuzzyKeySelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static String[] keys; static int[] ids; static int[] freqs;
    static void dict(String[] ws, int[] fr) {
        Integer[] o = new Integer[ws.length];
        for (int i = 0; i < o.length; i++) o[i] = i;
        Arrays.sort(o, Comparator.comparing(i -> ws[i]));
        keys = new String[ws.length]; ids = new int[ws.length]; freqs = fr;
        for (int i = 0; i < o.length; i++) { keys[i] = ws[o[i]]; ids[i] = o[i]; }
    }
    static String find(String p, int k) { return FuzzyKey.find(p, keys, ids, freqs, k * 3, () -> false); }

    public static void main(String[] a) {
        dict(new String[]{"guiqiu", "guiqu", "gui", "guiqiu", "nihao", "zhongguo", "wo", "women"},
             new int[]{50, 900, 800, 70, 1000, 1000, 5000, 3000});
        check("guiqiu".equals(find("guiqui", 2)), "写反 guiqui → guiqiu(不选更常用但代价更大的 guiqu): " + find("guiqui", 2));
        check("guiqiu".equals(find("guiquij", 2)), "写反 + 多打 guiquij → guiqiu: " + find("guiquij", 2));
        check("nihao".equals(find("nihoa", 1)), "nihoa → nihao");
        check("nihao".equals(find("nhao", 1)), "漏打 nhao → nihao");
        check("zhongguo".equals(find("zhonggou", 1)), "zhonggou → zhongguo");
        check(find("nihao", 2) == null, "输入本身就是键:不给");
        check(find("xyzxyz", 2) == null, "差太多:不给");
        check(find("ni", 1) == null, "太短:不给");
        check(find("guiqiuuuuu", 1) == null, "预算不够不给");
        check(FuzzyKey.find("guiqui", keys, ids, freqs, 6, () -> true) != null, "取消只在每 256 个节点检查一次,小词库不受影响");

        // 逐字对应(align):每一处错编号,写反算一处
        FuzzyKey.Align al = FuzzyKey.align("guiquij", "guiqiu");
        check(Arrays.toString(al.typed).equals("[-1, -1, -1, -1, 0, 0, 1]"), "guiquij 对应:ui 写反是第 0 处,多打的 j 是第 1 处: " + Arrays.toString(al.typed));
        check(Arrays.toString(al.fix).equals("[-1, -1, -1, -1, 0, 0]"), "guiqiu 对应:iu 是第 0 处: " + Arrays.toString(al.fix));
        al = FuzzyKey.align("nhao", "nihao");
        check(Arrays.toString(al.fix).equals("[-1, 0, -1, -1, -1]") && Arrays.toString(al.typed).equals("[-1, -1, -1, -1]"), "漏打 nhao → nihao:补上的 i 是第 0 处");
        al = FuzzyKey.align("womwn", "women");
        check(Arrays.toString(al.typed).equals("[-1, -1, -1, 0, -1]") && Arrays.toString(al.fix).equals("[-1, -1, -1, 0, -1]"), "按错相邻键 womwn → women");

        // 同样代价取词频高的
        dict(new String[]{"kanwa", "kanwo"}, new int[]{10, 99});
        check("kanwo".equals(find("kanwe", 2)), "同代价取词频高的 kanwe → kanwo: " + find("kanwe", 2));

        // 速度:20 万个键,最坏输入
        Random r = new Random(1);
        String[] ws = new String[200000]; int[] fr = new int[ws.length];
        for (int i = 0; i < ws.length; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 3 + r.nextInt(10);
            for (int k = 0; k < len; k++) sb.append((char) ('a' + r.nextInt(26)));
            ws[i] = sb.toString(); fr[i] = r.nextInt(1000);
        }
        dict(ws, fr);
        long t0 = System.nanoTime();
        for (int k = 0; k < 20; k++) find("abcdefghijkl", 4);
        long ms = (System.nanoTime() - t0) / 20_000_000;
        check(ms < 30, "20 万随机键、12 字母、改 4 处:每次 " + ms + " ms(上限 30)");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails != 0) System.exit(1);
    }
}
