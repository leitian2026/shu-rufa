// 拼错容错(Typo)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/typotest app/src/main/java/com/example/myime/{Syllabary,Typo}.java tools/TypoSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/typotest TypoSelfTest
// 覆盖:validPrefixLen;写反 / 按错相邻键 / 多打 / 漏打;合法输入不改;只改一处;结果都是合法拼音写法;
//       数量上限;太短太长非字母;速度(最坏输入)。
import com.example.myime.*;
import java.util.*;

public class TypoSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static boolean has(String p, String want) { return Typo.variants(p, 60).contains(want); }

    public static void main(String[] a) {
        // validPrefixLen
        check(Syllabary.validPrefixLen("nihao") == 5, "nihao 整串合法");
        check(Syllabary.validPrefixLen("nihaozhon") == 9, "尾巴没打完也合法");
        check(Syllabary.validPrefixLen("nihoa") == 4, "nihoa 在第 5 个字母出错(niho 还合法) " + Syllabary.validPrefixLen("nihoa"));
        check(Syllabary.validPrefixLen("nhao") == 1, "nhao 在第 2 个字母出错");
        check(Syllabary.validPrefixLen("xx") == 1, "xx");
        check(Syllabary.validPrefixLen("") == 0, "空串");

        // 四类错
        check(has("nihoa", "nihao"), "写反 nihoa → nihao");
        check(has("womwn", "women"), "按错相邻键 womwn → women");
        check(has("nihaao", "nihao"), "多打 nihaao → nihao");
        check(has("nhao", "nihao"), "漏打 nhao → nihao");
        check(has("zhongguoo", "zhongguo"), "末尾多打 zhongguoo");
        check(has("zhonggo", "zhongguo") || has("zhonggo", "zhonggou"), "漏打 zhonggo");
        check(has("xiexei", "xiexie") || has("xiexei", "xiexi"), "xiexei 能改");
        check(has("shijia", "shijie") || has("shijia", "shijian"), "shijia 本身合法也不乱改: 允许出现但不是必须");

        // 合法的输入:不在出错位置附近,但仍只改末尾几个字母;不能出现原串
        check(!Typo.variants("nihao", 60).contains("nihao"), "不返回原串");
        // 先后顺序:写反排在按错键、多打、漏打前面
        List<String> v = Typo.variants("nihoa", 60);
        check(v.indexOf("nihao") == 0, "写反排第一 " + v);

        // 都是合法写法
        boolean allValid = true;
        Random rnd = new Random(5);
        String abc = "abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 2000; i++) {
            int len = 3 + rnd.nextInt(14);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < len; k++) sb.append(abc.charAt(rnd.nextInt(26)));
            for (String x : Typo.variants(sb.toString(), 8)) {
                if (Syllabary.validPrefixLen(x) != x.length()) allValid = false;
                if (x.equals(sb.toString())) allValid = false;
            }
        }
        check(allValid, "随机 2000 串:结果都合法且不等于原串");

        // 只改一处:改一处后还是错的不返回(两处错)
        check(!has("nhoa", "nihao"), "两处错(漏打 + 写反)不处理");
        // 数量上限
        check(Typo.variants("nihoa", 3).size() <= 3, "max=3");
        check(Typo.variants("nihoa", 0).isEmpty(), "max=0");
        // 边界
        check(Typo.variants("ni", 5).isEmpty(), "太短");
        check(Typo.variants("abcdefghijklmnopqrstuvwxyz", 5).isEmpty(), "太长");
        check(Typo.variants("ni1hao", 5).isEmpty(), "非字母");

        // 速度:随机 3~24 字母的串各 2 万次(含最差的:出错位置在中间,漏打要试 26 个字母)
        List<String> ins = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int len = 8 + rnd.nextInt(17);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < len; k++) sb.append(abc.charAt(rnd.nextInt(26)));
            ins.add(sb.toString());
        }
        for (int r = 0; r < 20; r++) for (String s : ins) Typo.variants(s, 8);   // 预热
        long t0 = System.nanoTime();
        int calls = 0, produced = 0;
        for (int r = 0; r < 50; r++) for (String s : ins) { produced += Typo.variants(s, 8).size(); calls++; }
        double per = (System.nanoTime() - t0) / 1e6 / calls;
        System.out.println("INFO 随机 8~24 字母平均 " + String.format("%.3f", per) + " ms/次(平均产出 " + String.format("%.1f", produced / (double) calls) + " 个)");
        check(per < 3.0, "平均 < 3ms");
        // 最坏:全是不合法的 24 个字母(窗口里每个位置都要试完)
        String worst = "bbbbbbbbbbbbbbbbbbbbbbbb";
        t0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) Typo.variants(worst, 8);
        double pw = (System.nanoTime() - t0) / 1e6 / 2000;
        System.out.println("INFO 最坏 24 个 b 平均 " + String.format("%.3f", pw) + " ms/次");
        check(pw < 5.0, "最坏 < 5ms");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
