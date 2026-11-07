// 整串拼音改正(SyllableFix)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/sfixtest app/src/main/java/com/example/myime/{Syllabary,Typo,SyllableFix}.java tools/SyllableFixSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/sfixtest SyllableFixSelfTest
// 覆盖:多处写反(guiquiqui);出错的字母前面写反优先(guiquij、guiquijuming);多打 / 漏打;合法输入不改;太差不改;结果都是合法拼音;速度。
import com.example.myime.*;

public class SyllableFixSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static String fix(String p) { int k = Math.min(5, Math.max(1, p.length() / 3)); return SyllableFix.repair(p, k * 3, () -> false); }

    public static void main(String[] a) {
        check("guiqiuqiu".equals(fix("guiquiqui")), "两处写反 guiquiqui → guiqiuqiu: " + fix("guiquiqui"));
        check("guiqiujuming".equals(fix("guiquijuming")), "guiquijuming → guiqiujuming(不是 guiqujiuming): " + fix("guiquijuming"));
        check("guiqiuj".equals(fix("guiquij")), "写反 guiquij → guiqiuj(j 可以是下一个音节的开头;不是 guiquji): " + fix("guiquij"));
        check("nihao".equals(fix("nihoa")), "nihoa → nihao: " + fix("nihoa"));
        check("nihaox".equals(fix("nihaoxx")), "连按两下 nihaoxx → nihaox: " + fix("nihaoxx"));
        String nh = fix("nhao");
        check(nh != null && nh.length() == 5 && nh.startsWith("n") && Syllabary.validPrefixLen(nh) == 5, "漏打 nhao → 补一个字母(补哪个要查词库才分得出): " + nh);
        check("women".equals(fix("womwn")) || "woman".equals(fix("womwn")), "按错相邻键 womwn → women / woman(同样代价,不查词库分不出): " + fix("womwn"));
        check(fix("nihao") == null, "合法输入不改");
        check(fix("nihaozhon") == null, "最后一个音节还在打,不算错");
        check(fix("xyzxyz") == null, "差太多不改: " + fix("xyzxyz"));
        check(fix("ni") == null, "太短不改");
        String[] ins = {"guiquiqui", "guiquijuming", "woxiangqunazhongguouu", "nihoawomwn", "zhogguorenmin"};
        boolean allValid = true;
        for (String s : ins) { String r = fix(s); if (r != null && Syllabary.validPrefixLen(r) != r.length()) allValid = false; }
        check(allValid, "结果都是合法拼音");
        long t0 = System.nanoTime();
        for (int k = 0; k < 20; k++) fix("qwertyuiopasdfghjklzxcv");
        long ms = (System.nanoTime() - t0) / 20_000_000;
        check(ms < 40, "23 个乱字母(最坏)每次 " + ms + " ms(上限 40)");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails != 0) System.exit(1);
    }
}
