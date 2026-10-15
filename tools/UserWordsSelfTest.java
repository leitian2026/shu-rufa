// 造词(UserWords)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   D=app/src/main/java/com/example/myime
//   javac -encoding UTF-8 -d /tmp/uwtest $D/Syllabary.java $D/SafeFile.java $D/LearnShare.java $D/UserFreq.java $D/UserChain.java \
//         $D/UserWords.java $D/Fuzzy.java $D/SentenceDecoder.java tools/stub/com/example/myime/ErrorHint.java tools/UserWordsSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/uwtest UserWordsSelfTest
// 覆盖:单字拼成的词第一次可用、含多字词的第二次才可用;精确/补全查询与排序;保存再读;清除;
//       坏数据行;上限淘汰;非法参数;整句里用到我造的词(含分词符);速度。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class UserWordsSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static String[] S(String... a) { return a; }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("uwtest").toFile();
        File f = new File(dir, "words.txt");
        UserWords w = new UserWords(f);

        check(w.snapshot() == null && w.count() == 0, "一开始没有词");
        // 每段都是单字:第一次就可用
        check(w.add("张伟", S("zhang", "wei"), true), "单字拼成的词第一次可用");
        // 含多字词:第一次不可用,第二次可用
        check(!w.add("我们明天", S("wo", "men", "ming", "tian"), false), "含多字词的第一次不可用");
        UserWords.Snapshot s0 = w.snapshot();
        check(s0 != null && s0.size() == 1 && s0.texts[0].equals("张伟"), "快照里只有可用的");
        check(w.add("我们明天", S("wo", "men", "ming", "tian"), false), "第二次变可用");
        check(w.count() == 2, "可用的有 2 个");

        UserWords.Snapshot s = w.snapshot();
        check(Arrays.equals(s.exact("zhangwei"), new String[] {"张伟"}), "精确查询 zhangwei");
        check(s.exact("zhangwe").length == 0 && s.exact("zhang").length == 0, "精确查询不会命中前缀");
        check(Arrays.asList(s.prefix("zhangw", 5)).contains("张伟"), "补全 zhangw → 张伟");
        check(s.prefix("zhangwei", 5).length == 0, "补全不含正好相等的");
        check(Arrays.equals(s.lens[Arrays.asList(s.texts).indexOf("张伟")], new int[] {5, 3}), "音节长度 5,3");
        // 快照在没有改动时是同一个对象(不重复构建)
        check(w.snapshot() == s, "没改动返回同一个快照");

        // 同音不同字:次数多的在前
        w.add("章伟", S("zhang", "wei"), true);
        w.add("章伟", S("zhang", "wei"), true);
        w.add("章伟", S("zhang", "wei"), true);
        String[] ex = w.snapshot().exact("zhangwei");
        check(ex.length == 2 && ex[0].equals("章伟") && ex[1].equals("张伟"), "同音词按次数排序 " + Arrays.toString(ex));
        check(w.snapshot() != s, "改动后快照更新");

        // 保存再读
        w.saveIfDirty();
        UserWords w2 = new UserWords(f);
        UserWords.Snapshot r = w2.snapshot();
        check(r != null && r.size() == 3 && Arrays.equals(r.exact("zhangwei"), ex), "重新读出来一致 " + (r == null ? 0 : r.size()));
        // 不可用的(只见过一次)也被保存:再见一次就可用
        UserWords w3 = new UserWords(new File(dir, "w3.txt"));
        w3.add("你好吗", S("ni", "hao", "ma"), false);
        w3.saveIfDirty();
        UserWords w3b = new UserWords(new File(dir, "w3.txt"));
        check(w3b.snapshot() == null, "只见过一次的还不可用");
        check(w3b.add("你好吗", S("ni", "hao", "ma"), false), "重启后再见一次就可用");

        // 坏数据:跳过坏行,好行照常
        Path bad = dir.toPath().resolve("bad.txt");
        Files.write(bad, ("#v1\nW\t好人\thao ren\t2\t100\nW\t坏\n乱七八糟\nW\t空\t\t2\t100\nW\t数\tshu shu\tx\t100\nW\t好友\thao you\t2\t200\n#end\n").getBytes("UTF-8"));
        UserWords wb = new UserWords(bad.toFile());
        UserWords.Snapshot sb = wb.snapshot();
        check(sb != null && sb.size() == 2 && sb.exact("haoren").length == 1 && sb.exact("haoyou").length == 1, "坏行被跳过 " + (sb == null ? 0 : sb.size()));

        // 非法参数
        UserWords wi = new UserWords(new File(dir, "i.txt"));
        check(!wi.add("张", S("zhang"), true), "单字不算词");
        check(!wi.add("张伟", S("zhang"), true), "音节数和字数对不上");
        check(!wi.add("张伟", S("zhang", ""), true), "空音节");
        check(!wi.add("一二三四五六七八九", S("yi", "er", "san", "si", "wu", "liu", "qi", "ba", "jiu"), true), "太长");
        check(wi.snapshot() == null, "都没记进去");

        // 清除
        w.clear();
        check(w.snapshot() == null && w.count() == 0 && !f.exists(), "清除后什么都没有、文件也删了");

        // 上限:先丢没可用的,再丢次数少又旧的
        UserWords wm = new UserWords(new File(dir, "m.txt"));
        for (int i = 0; i < UserWords.MAX_WORDS; i++) wm.add("甲" + i, S("jia", "yi"), true);
        wm.add("重要", S("zhong", "yao"), true);
        wm.add("重要", S("zhong", "yao"), true);
        wm.add("重要", S("zhong", "yao"), true);
        for (int i = 0; i < 50; i++) wm.add("乙" + i, S("yi", "jia"), false);   // 没可用的
        int cnt = wm.count();
        check(cnt <= UserWords.MAX_WORDS, "不超过上限 " + cnt);
        check(wm.snapshot().exact("zhongyao").length == 1, "次数多的重要词没被丢");

        // ---------------- 整句里用到我造的词
        String[] DICT = {
            "我\two\t8000", "们\tmen\t3000", "我们\two men\t5000", "明天\tming tian\t4000", "去\tqu\t9000",
            "看\tkan\t7000", "书\tshu\t4000", "看书\tkan shu\t2000", "章\tzhang\t5000", "张\tzhang\t6000", "伟\twei\t4000",
            "来\tlai\t8000", "了\tle\t9000", "在\tzai\t9000", "很\then\t7000", "好\thao\t9000",
        };
        SentenceDecoder d = SentenceSelfTest.build(DICT);
        UserWords wz = new UserWords(new File(dir, "z.txt"));
        wz.add("张伟", S("zhang", "wei"), true);
        UserWords.Snapshot z = wz.snapshot();
        // 没有我的词:拼成 章/张 + 伟 两个词;有了:整句里直接用 张伟(一个词)
        List<SentenceDecoder.Result> plain = d.decodeTop("zhangweilaile", null, null, null, 3);
        check(!plain.isEmpty() && plain.get(0).words.length >= 3, "没有我造的词时 张 伟 来 了 各自成词 " + SentenceDecoder.show(plain.isEmpty() ? null : plain.get(0)));
        List<SentenceDecoder.Result> with = d.decodeTop("zhangweilaile", null, null, null, 3, z);
        check(!with.isEmpty() && with.get(0).words[0].equals("张伟") && with.get(0).text.equals("张伟来了"),
              "有我造的词时整句里直接用 → " + SentenceDecoder.show(with.isEmpty() ? null : with.get(0)));
        check(with.get(0).codes[0].equals("zhangwei"), "codes 是原输入 " + with.get(0).codes[0]);
        // 分词符 zhang'wei'lai'le:张伟 在 5 的位置有音节边界,可以用
        List<SentenceDecoder.Result> cutOk = d.decodeTop("zhangweilaile", new int[] {5, 8}, null, null, 3, z);
        check(!cutOk.isEmpty() && cutOk.get(0).text.equals("张伟来了"), "分词符在音节边界上,用我的词");
        // 分词符在词中间(zhan'gwei):我的词不能用
        List<SentenceDecoder.Result> cutBad = d.decodeTop("zhangweilaile", new int[] {4}, null, null, 3, z);
        check(cutBad.isEmpty() || !cutBad.get(0).text.startsWith("张伟"), "分词符落在我的词中间时不用它");
        // 用户词也能让原本拼不出的整句拼出来(词库里没有 wei 以外的字)
        SentenceDecoder d2 = SentenceSelfTest.build(new String[] {"来\tlai\t8000", "了\tle\t9000"});
        check(d2.decodeTop("zhangweilaile", null, null, null, 3).isEmpty(), "词库里没有 张伟 时拼不出");
        List<SentenceDecoder.Result> only = d2.decodeTop("zhangweilaile", null, null, null, 3, z);
        check(!only.isEmpty() && only.get(0).text.equals("张伟来了"), "靠我造的词拼出来 → " + SentenceDecoder.show(only.isEmpty() ? null : only.get(0)));
        // 次数为 1 的不可用词不参与整句
        UserWords wp = new UserWords(new File(dir, "p.txt"));
        wp.add("张伟", S("zhang", "wei"), false);
        check(wp.snapshot() == null, "次数 1 的不在快照里");

        // 速度:3000 个词的快照,整句用到它们
        UserWords big = new UserWords(new File(dir, "big.txt"));
        String[] syl = {"zhang", "wei", "li", "wang", "liu", "chen", "yang", "zhao", "huang", "zhou"};
        Random rnd = new Random(3);
        for (int i = 0; i < 3000; i++) {
            String text = "" + (char) (0x4e00 + i % 100) + (char) (0x4e00 + 100 + i / 100) + "名";
            big.add(text, S(syl[rnd.nextInt(syl.length)], syl[rnd.nextInt(syl.length)], syl[rnd.nextInt(syl.length)]), true);
        }
        UserWords.Snapshot bs = big.snapshot();
        String in = "zhangweiliwangliuchenyangzhao";
        for (int i = 0; i < 300; i++) d.decodeTop(in, null, null, null, 3, bs);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) d.decodeTop(in, null, null, null, 3, bs);
        double per = (System.nanoTime() - t0) / 1e6 / 1000;
        System.out.println("INFO " + (bs == null ? 0 : bs.size()) + " 个造的词时整句平均 " + String.format("%.3f", per) + " ms/次");
        check(per < 5.0, "整句 < 5ms");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
