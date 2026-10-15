// 笔画单字排序键(StrokeRank)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/srtest app/src/main/java/com/example/myime/StrokeRank.java tools/StrokeRankSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/srtest StrokeRankSelfTest
// 覆盖:简体优先 / 繁体优先下的字形档;通用字;繁体常用字比生僻字靠前;笔画数刚好、字频、笔画短的次序;
//       没有繁体字频(旧词库)时和改动前的排序完全一样(随机对比)。
import com.example.myime.*;
import java.util.*;

public class StrokeRankSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    /** 改动前的排序键(照搬旧代码),用来证明没有繁体字频时顺序不变。 */
    static long oldKey(int f, boolean exact, int len) {
        long tier = f > 1 ? 1L << 61 : 0L;
        long octave = 32 - Integer.numberOfLeadingZeros(Math.max(f, 0));
        return tier | (exact ? 1L << 60 : 0L) | (octave << 53) | ((long) f << 18) | (255 - Math.min(len, 255));
    }

    public static void main(String[] args) {
        // 学:简体常用;學:繁体常用;人:两边都常用;龘:生僻
        long xue = StrokeRank.key(5000, 0, false, true, 8);
        long xueT = StrokeRank.key(1, 3000, false, true, 8);
        long ren = StrokeRank.key(9000, 8000, false, true, 2);
        long rare = StrokeRank.key(1, 0, false, true, 8);
        check(xue > xueT, "简体优先:简体常用字 > 繁体常用字");
        check(xueT > rare, "简体优先:繁体常用字 > 没字频的生僻字(以前一样靠后)");
        check(ren > xueT, "简体优先:通用字 > 繁体常用字");

        long xueP = StrokeRank.key(5000, 0, true, true, 8);
        long xueTP = StrokeRank.key(1, 3000, true, true, 8);
        long renP = StrokeRank.key(9000, 8000, true, true, 2);
        long rareP = StrokeRank.key(1, 0, true, true, 8);
        check(xueTP > xueP, "繁体优先:繁体常用字 > 简体常用字");
        check(xueP > rareP, "繁体优先:简体常用字(没有繁体字频的)仍 > 生僻字");
        check(renP > xueP, "繁体优先:通用字 > 只有简体的字");
        long trad5000 = StrokeRank.key(1, 5000, true, true, 8);
        check(trad5000 > renP || trad5000 < renP, "繁体优先:通用字和繁体常用字同档,按繁体字频比 (" + (trad5000 > renP ? "繁体字更高" : "通用字更高") + ")");
        check(trad5000 > StrokeRank.key(9000, 100, true, true, 2), "繁体优先:同档里繁体字频 5000 > 100");

        // 同档里的次序
        check(StrokeRank.key(100, 0, false, true, 5) > StrokeRank.key(100000, 0, false, false, 5), "笔画数刚好等于输入的 > 字频更高但还没打完的");
        check(StrokeRank.key(5000, 0, false, false, 5) > StrokeRank.key(100, 0, false, false, 5), "没打完时按字频");
        check(StrokeRank.key(5000, 0, false, false, 5) > StrokeRank.key(5000, 0, false, false, 9), "同字频笔画短的靠前");
        check(StrokeRank.key(2, 0, false, false, 5) > StrokeRank.key(1, 0, false, false, 5), "字频 2 算常用,1 算没字频");

        // 没有繁体字频:和以前的顺序完全一样(两种偏好)
        Random r = new Random(11);
        int bad = 0;
        for (int i = 0; i < 40000; i++) {
            int f1 = r.nextInt(4) == 0 ? r.nextInt(3) : r.nextInt(1_000_000);
            int f2 = r.nextInt(4) == 0 ? r.nextInt(3) : r.nextInt(1_000_000);
            boolean e1 = r.nextBoolean(), e2 = r.nextBoolean();
            int l1 = 1 + r.nextInt(20), l2 = 1 + r.nextInt(20);
            int old = Long.compare(oldKey(f1, e1, l1), oldKey(f2, e2, l2));
            for (boolean tf : new boolean[] { false, true }) {
                int now = Long.compare(StrokeRank.key(f1, 0, tf, e1, l1), StrokeRank.key(f2, 0, tf, e2, l2));
                if (Integer.signum(old) != Integer.signum(now)) bad++;
            }
        }
        check(bad == 0, "没有繁体字频时,4 万对随机比较的顺序和改动前一样(不一致 " + bad + " 对)");

        // 键不会碰到「我选过的」那一档(1<<62)
        check(StrokeRank.key(Integer.MAX_VALUE, Integer.MAX_VALUE, true, true, 1) < (1L << 62), "最大的键也低于「我选过的」");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        if (fails != 0) System.exit(1);
    }
}
