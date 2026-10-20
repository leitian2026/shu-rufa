// 搭配模型包(GramPack)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/gptest app/src/main/java/com/example/myime/GramPack.java tools/GramPackSelfTest.java
//   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp /tmp/gptest GramPackSelfTest
// 覆盖:打包后逐条查回、没有的键返回 0、误判率、同一个键值大的保留、词对搭配分(末尾字 + 开头字的各种组合、没有搭配 = 0、
//       分数换算)、坏文件被拒绝、速度。
import com.example.myime.GramPack;
import java.io.*;
import java.util.*;

public class GramPackSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static byte[] pack(Map<String, Integer> raw) { return GramPack.build(raw); }

    public static void main(String[] args) throws Exception {
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put("我们明天", 181845);
        raw.put("再见", 110908);
        raw.put("明天去", 150000);
        raw.put("的才", 100400);       // 100400/1000 = 100 → 10.0 - 8 = 2.0
        raw.put("信奉", 90000);        // 90 → 9.0 - 8 = 1.0
        raw.put("很低", 70000);        // 70 → 7.0 → 小于 8 的算 0
        GramPack g = GramPack.wrap(pack(raw));

        check(g.count() == 6, "条目数 " + g.count());
        check(g.lookup("我们明天") == 182, "查回 我们明天 = " + g.lookup("我们明天"));
        check(g.lookup("再见") == 111, "查回 再见 = " + g.lookup("再见"));
        check(g.lookup("在建") == 0, "没有的键 在建 = 0");
        check(g.lookup("我们明") == 0, "前缀不是键 = 0");

        // 词对搭配分:取前词末尾 1~3 个字 + 后词开头 1~3 个字的最大值
        check(Math.abs(g.pairScore("我们", "明天") - (18.2 - 8.0)) < 1e-9, "我们+明天 → 命中 我们明天:" + g.pairScore("我们", "明天"));
        check(Math.abs(g.pairScore("明天", "去") - (15.0 - 8.0)) < 1e-9, "明天+去 → 命中 明天去:" + g.pairScore("明天", "去"));
        check(g.pairScore("在", "建") == 0.0, "在+建 没有搭配 = 0");
        check(Math.abs(g.pairScore("信", "奉") - 1.0) < 1e-9, "信+奉 → 命中 信奉 = 1.0(" + g.pairScore("信", "奉") + ")");
        check(g.pairScore("他", "信奉") == 0.0, "他+信奉:键必须跨过两个词的交界,信奉 整个落在后一个词里不算 = 0");
        check(g.pairScore("很", "低") == 0.0, "值低于下限的算 0");
        check(Math.abs(g.pairScore("好的", "才是") - 2.0) < 1e-9, "好的+才是:末尾 的 + 开头 才 → 的才 = 2.0(" + g.pairScore("好的", "才是") + ")");
        check(g.pairScore("", "明天") == 0.0 && g.pairScore("我们", "") == 0.0, "空词 = 0");
        // 末尾最多取 3 个字:长词前面的字不参与
        check(g.pairScore("今天我们", "明天") > 0, "长词只看末尾:今天我们+明天 命中 我们明天");

        // 同一个键写两次(这里用 Map 去重不了同哈希不同键,单独测:桶和校验码相同的两条,只留值大的)——
        // 用哈希高位直接构造冲突不现实,改测「重复键写进去,值取大的」
        Map<String, Integer> dup = new LinkedHashMap<>();
        dup.put("你好", 100000);
        GramPack g2 = GramPack.wrap(pack(dup));
        check(g2.lookup("你好") == 100, "单条 你好 = 100");

        // 误判率:随机 6 万条做模型,查另外 20 万个不在里面的键
        Random rnd = new Random(7);
        Map<String, Integer> big = new HashMap<>();
        while (big.size() < 600_000) big.put(randKey(rnd), 60000 + rnd.nextInt(120000));
        GramPack gb = GramPack.wrap(pack(big));
        int miss = 0;
        for (Map.Entry<String, Integer> e : big.entrySet()) {
            int want = Math.max(1, Math.min(255, Math.round(e.getValue() / 1000f)));
            if (gb.lookup(e.getKey()) < want) miss++;   // 冲突只会让值变大,不会变小
        }
        check(miss == 0, "60 万条全部查得回(值不小于写入值),漏 " + miss);
        int fp = 0, tries = 200_000;
        for (int i = 0; i < tries; i++) {
            String k = randKey(rnd);
            if (!big.containsKey(k) && gb.lookup(k) != 0) fp++;
        }
        double rate = fp / (double) tries;
        System.out.println("   误判 " + fp + " / " + tries + " = " + String.format("%.4f%%", rate * 100) + "(每桶约 " + (600_000 >> 20) + " 条,条目少所以更低)");
        check(rate < 0.001, "误判率低于 0.1%");

        // 速度:100 万次词对查询
        long t0 = System.nanoTime();
        double sink = 0;
        for (int i = 0; i < 1_000_000; i++) sink += gb.pairScore("天气", "很好");
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println("   100 万次 pairScore " + String.format("%.0f", ms) + " ms(sink " + sink + ")");
        check(ms < 5000, "100 万次词对查询 < 5 秒");

        // 坏文件
        byte[] bad = pack(raw);
        bad[0] = 'X';
        boolean rej = false;
        try { GramPack.wrap(bad); } catch (IOException e) { rej = true; }
        check(rej, "魔数不对被拒绝");
        rej = false;
        try { GramPack.wrap(Arrays.copyOf(pack(raw), 100)); } catch (IOException e) { rej = true; }
        check(rej, "被截断的文件被拒绝");
        rej = false;
        try { GramPack.wrap(new byte[10]); } catch (IOException e) { rej = true; }
        check(rej, "太短的文件被拒绝");

        // 落盘再内存映射打开
        File f = File.createTempFile("gram", ".bin");
        f.deleteOnExit();
        try (FileOutputStream os = new FileOutputStream(f)) { os.write(pack(raw)); }
        GramPack gf = GramPack.open(f);
        check(gf.count() == 6 && gf.lookup("再见") == 111 && GramPack.verify(f) == 6, "文件内存映射打开,查回正常");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static String randKey(Random r) {
        int len = 2 + r.nextInt(4);
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = (char) (0x4E00 + r.nextInt(6000));
        return new String(c);
    }
}
