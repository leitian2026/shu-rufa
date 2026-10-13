// 多机同步学习数据(LearnShare / UserFreq.mergeRows / UserChain.mergeRows)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/lstest app/src/main/java/com/example/myime/SafeFile.java \
//         app/src/main/java/com/example/myime/UserFreq.java app/src/main/java/com/example/myime/UserChain.java \
//         app/src/main/java/com/example/myime/LearnShare.java app/src/main/java/com/example/myime/BackupCrypto.java \
//         tools/stub/com/example/myime/ErrorHint.java tools/LearnSyncSelfTest.java
//   java -cp /tmp/lstest LearnSyncSelfTest
// 覆盖:词的过滤;导出 → 合并后另一台手机能排到;重复合并不变(幂等);谁先合并结果都一样(交换律);
//       窗口取两边最新的 5 个;未来时间戳被截断;联想的两词上下文(\u0001)往返不丢;文件被截断 / 版本太新 / 格式不对会拒绝;
//       加密 + gzip 往返;错误的密钥解不开。
import com.example.myime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class LearnSyncSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static File tmp;
    static int n = 0;
    static File f(String name) { return new File(tmp, (n++) + "_" + name); }

    static String dump(UserFreq p, UserFreq s, UserChain c) throws IOException {
        StringWriter w = new StringWriter();
        LearnShare.write(w, p, s, c);
        List<String> lines = new ArrayList<>(Arrays.asList(w.toString().split("\n", -1)));
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    static byte[] plain(UserFreq p, UserFreq s, UserChain c) throws IOException {
        StringWriter w = new StringWriter();
        LearnShare.write(w, p, s, c);
        return w.toString().getBytes(StandardCharsets.UTF_8);
    }

    static int mergeInto(byte[] data, UserFreq p, UserFreq s, UserChain c, long now) throws IOException {
        return LearnShare.merge(new ByteArrayInputStream(data), p, s, c, now);
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }

    public static void main(String[] a) throws Exception {
        tmp = Files.createTempDirectory("lstest").toFile();
        long now = System.currentTimeMillis();

        // ---- 词的过滤
        check(LearnShare.okWord("苹果"), "okWord: 普通中文词");
        check(!LearnShare.okWord("13812345678"), "okWord: 手机号不共享");
        check(!LearnShare.okWord("第1名"), "okWord: 含数字不共享");
        check(!LearnShare.okWord("password"), "okWord: 长串字母不共享");
        check(LearnShare.okWord("OK"), "okWord: 短字母可以");
        check(!LearnShare.okWord(""), "okWord: 空串不行");
        check(!LearnShare.okWord("a\tb"), "okWord: 含制表符不行");

        // ---- A 手机学一些,B 手机学一些
        UserFreq ap = new UserFreq(f("ap")), as = new UserFreq(f("as"));
        UserChain ac = new UserChain(f("ac"));
        UserFreq bp = new UserFreq(f("bp")), bs = new UserFreq(f("bs"));
        UserChain bc = new UserChain(f("bc"));

        ap.learn("pingguo", "苹果");
        ap.learn("ni", "你");
        ap.learn("qq", "手机号13812345678");   // 含数字:不应该共享
        as.learn("12", "木");
        ac.learn("吃", "苹果");
        ac.learn("我" + UserChain.SEP + "吃", "饭");   // 两词上下文
        ac.learn("联系", "13800000000");            // 含数字:不应该共享
        sleep(5);
        bp.learn("ni", "尼");
        bp.learn("xiangguo", "香蕉");
        bc.learn("吃", "香蕉");

        byte[] fromA = plain(ap, as, ac);
        String s = new String(fromA, StandardCharsets.UTF_8);
        check(!s.contains("13812345678") && !s.contains("13800000000"), "导出不含手机号");
        check(s.contains("T\t苹果\t") && s.contains("@chain"), "导出含正常词和区块");

        int ch = mergeInto(fromA, bp, bs, bc, now + 100000);
        check(ch > 0, "B 合并 A:有变化");
        check(bp.textStamp("苹果") > 0, "B 现在知道 A 学的词(苹果)");
        check(bp.pairStamp("pingguo", "苹果") > 0, "B 现在知道 A 的(编码,词)");
        check(bs.textStamp("木") > 0, "B 笔画也合并了");
        check(bp.textStamp("手机号13812345678") == 0, "被过滤的词没有进 B");
        List<String> nx = bc.next(null, "吃");
        check(nx.contains("苹果") && nx.contains("香蕉"), "B 联想里 吃→苹果 和 吃→香蕉 都有");
        check(bc.next("我", "吃").contains("饭"), "两词上下文(\\u0001)往返不丢");

        // ---- 幂等:再合并一次不变
        String before = dump(bp, bs, bc);
        int again = mergeInto(fromA, bp, bs, bc, now + 100000);
        check(again == 0, "重复合并同一份:没有变化(幂等)");
        check(before.equals(dump(bp, bs, bc)), "重复合并后内容完全一样");

        // ---- 交换律:A 合 B 与 B 合 A,结果一样(各自用全新的对象)
        UserFreq xp = new UserFreq(f("xp")), xs = new UserFreq(f("xs"));
        UserChain xc = new UserChain(f("xc"));
        UserFreq yp = new UserFreq(f("yp")), ys = new UserFreq(f("ys"));
        UserChain yc = new UserChain(f("yc"));
        // 用同样的两份原始数据,以不同顺序合并进两个空对象
        UserFreq ap0 = new UserFreq(f("ap0")), as0 = new UserFreq(f("as0"));
        UserChain ac0 = new UserChain(f("ac0"));
        mergeInto(fromA, ap0, as0, ac0, now + 100000);
        UserFreq bp0 = new UserFreq(f("bp0")), bs0 = new UserFreq(f("bs0"));
        UserChain bc0 = new UserChain(f("bc0"));
        bp0.learn("ni", "尼"); bp0.learn("xiangguo", "香蕉"); bc0.learn("吃", "香蕉");
        byte[] fromB = plain(bp0, bs0, bc0);
        mergeInto(fromA, xp, xs, xc, now + 100000);
        mergeInto(fromB, xp, xs, xc, now + 100000);
        mergeInto(fromB, yp, ys, yc, now + 100000);
        mergeInto(fromA, yp, ys, yc, now + 100000);
        check(dump(xp, xs, xc).equals(dump(yp, ys, yc)), "先合 A 后合 B == 先合 B 后合 A(交换律)");

        // ---- 窗口:取两边最新的 5 个
        UserFreq wa = new UserFreq(f("wa")), wb = new UserFreq(f("wb"));
        for (int i = 0; i < 4; i++) { wa.learn("de", "的"); sleep(2); }
        wb.learn("de", "得"); sleep(2);
        wb.learn("de", "地"); sleep(2);
        wa.learn("de", "的"); sleep(2);   // 最新是 A 的 的
        UserFreq wsa = new UserFreq(f("wsa")); UserChain wca = new UserChain(f("wca"));
        UserFreq wsb = new UserFreq(f("wsb")); UserChain wcb = new UserChain(f("wcb"));
        byte[] wbData = plain(wb, wsb, wcb);
        mergeInto(wbData, wa, wsa, wca, now + 100000);
        String[] win = wa.recent("de");
        check(win != null && win.length == 5, "窗口合并后最多 5 个: " + Arrays.toString(win));
        check(win != null && win[0].equals("的"), "窗口里最新的在前");
        check(Arrays.asList(win).contains("地"), "B 手机的近期选择进了窗口");

        // ---- 未来时间戳被截断
        UserFreq fp = new UserFreq(f("fp")), fs = new UserFreq(f("fs"));
        UserChain fc = new UserChain(f("fc"));
        long future = now + 10L * 24 * 3600 * 1000;
        String fut = "#myime-learn v1\n@pinyin\nT\t未来\t" + future + "\nP\twl\t未来\t" + future + "\n@stroke\n@chain\n#end\n";
        long t0 = System.currentTimeMillis();
        mergeInto(fut.getBytes(StandardCharsets.UTF_8), fp, fs, fc, t0);
        check(fp.textStamp("未来") > 0 && fp.textStamp("未来") <= t0, "未来的时间戳被截成当前时间");
        fp.learn("wl", "本机");
        check(fp.textStamp("本机") > fp.textStamp("未来"), "合并后本机新学的词仍排在合并进来的前面");

        // ---- 坏文件
        UserFreq gp = new UserFreq(f("gp")), gs = new UserFreq(f("gs"));
        UserChain gc = new UserChain(f("gc"));
        boolean threw;
        try { mergeInto("#myime-learn v1\n@pinyin\nT\t甲\t5\n".getBytes(StandardCharsets.UTF_8), gp, gs, gc, now); threw = false; } catch (IOException e) { threw = true; }
        check(threw && gp.textStamp("甲") == 0, "没有 #end(被截断)的文件被拒绝,且什么都没合进去");
        try { mergeInto("#myime-learn v9\n#end\n".getBytes(StandardCharsets.UTF_8), gp, gs, gc, now); threw = false; } catch (IOException e) { threw = true; }
        check(threw, "版本太新被拒绝");
        try { mergeInto("hello\n".getBytes(StandardCharsets.UTF_8), gp, gs, gc, now); threw = false; } catch (IOException e) { threw = true; }
        check(threw, "格式不对被拒绝");
        String unknown = "#myime-learn v1\n@future\nX\t1\t2\n@pinyin\nT\t乙\t7\n#end\n";
        mergeInto(unknown.getBytes(StandardCharsets.UTF_8), gp, gs, gc, now);
        check(gp.textStamp("乙") == 7, "不认识的区块跳过,后面的正常合并");
        String junk = "#myime-learn v1\n@pinyin\nT\t丙\tabc\nT\t\t5\nP\t\t丁\t5\nT\t戊\t-3\nT\t己\t9\n#end\n";
        mergeInto(junk.getBytes(StandardCharsets.UTF_8), gp, gs, gc, now);
        check(gp.textStamp("己") == 9 && gp.textStamp("丙") == 0 && gp.textStamp("戊") == 0, "坏行跳过,好行照收");

        // ---- 清除时间下限:早于下限的记录不收
        UserFreq lp = new UserFreq(f("lp")), ls2 = new UserFreq(f("ls2"));
        UserChain lc = new UserChain(f("lc"));
        String old = "#myime-learn v1\n@pinyin\nT\t旧词\t1000\nT\t新词\t5000\nP\tjc\t旧词\t1000\n@stroke\n@chain\nC\t甲\t旧\t1000\nC\t甲\t新\t5000\n#end\n";
        LearnShare.merge(new ByteArrayInputStream(old.getBytes(StandardCharsets.UTF_8)), lp, ls2, lc, 10000L, 3000L);
        check(lp.textStamp("旧词") == 0 && lp.textStamp("新词") == 5000, "清除下限:早于下限的词不收,之后的照收");
        check(lp.pairStamp("jc", "旧词") == 0, "清除下限:(编码,词)同样");
        check(!lc.next(null, "甲").contains("旧") && lc.next(null, "甲").contains("新"), "清除下限:联想同样");

        // ---- 加密 + gzip 往返
        byte[] master = BackupCrypto.deriveMaster("test-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);
        byte[] salt = new byte[16];
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (OutputStream enc = BackupCrypto.encrypt(file, master, salt, BackupCrypto.MIN_ITERATIONS);
             GZIPOutputStream gz = new GZIPOutputStream(enc);
             Writer w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
            LearnShare.write(w, ap, as, ac);
        }
        UserFreq rp = new UserFreq(f("rp")), rs = new UserFreq(f("rs"));
        UserChain rc = new UserChain(f("rc"));
        try (InputStream dec = BackupCrypto.decryptWithMaster(new ByteArrayInputStream(file.toByteArray()), master);
             InputStream gz = new GZIPInputStream(dec)) {
            LearnShare.merge(gz, rp, rs, rc, now + 100000);
        }
        check(rp.textStamp("苹果") > 0 && rc.next("我", "吃").contains("饭"), "加密 + gzip 往返后能合并");
        byte[] wrong = BackupCrypto.deriveMaster("other-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);
        try {
            try (InputStream dec = BackupCrypto.decryptWithMaster(new ByteArrayInputStream(file.toByteArray()), wrong);
                 InputStream gz = new GZIPInputStream(dec)) {
                gz.read();
            }
            threw = false;
        } catch (IOException e) { threw = true; }
        check(threw, "密钥不对解不开");

        // ---- 学习计数:自己学的算,合并进来的不算
        check(ap.learnedCount() == 3 && bp.learnedCount() == 2, "learnedCount 只数本机自己学的次数");
        File cpFile = f("cp");
        UserFreq cp = new UserFreq(cpFile), cs = new UserFreq(f("cs"));
        UserChain cc = new UserChain(f("cc"));
        mergeInto(fromA, cp, cs, cc, now + 100000);
        check(cp.learnedCount() == 0 && cc.learnedCount() == 0, "合并进来的不计入 learnedCount");

        // ---- 合并后存盘重读
        cp.saveIfDirty(); cs.saveIfDirty(); cc.saveIfDirty();
        UserFreq cp2 = new UserFreq(cpFile);
        check(cp2.textStamp("苹果") > 0, "合并后存盘、重新读取还在");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
