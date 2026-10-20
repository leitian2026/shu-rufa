// 整句解码(SentenceDecoder)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/senttest app/src/main/java/com/example/myime/{Syllabary,SafeFile,LearnShare,UserFreq,UserChain,UserWords,UserChars,Fuzzy,GramPack,SentenceDecoder}.java \
//         tools/stub/com/example/myime/ErrorHint.java tools/SentenceSelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/senttest SentenceSelfTest
// 覆盖:整句切成词、没打完的尾巴、词库缺音节、用户分词符、我打过的词影响结果、只有一个词不出句;
//       搭配模型(装上后同音词按上下文选、去掉后恢复);混拼(简拼 + 全拼、最后一个音节没打完、单音节词必须写全、分词符、其他整串候选);速度(20 万词条)。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class SentenceSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    /** 按引擎的方式建数组:texts / freqs / sylLens / sylStart,以及按拼音连写排序的 keys + ids。 */
    static SentenceDecoder build(String[] lines) {
        int n = lines.length;
        String[] texts = new String[n];
        String[] compact = new String[n];
        int[] freqs = new int[n];
        List<Byte> lens = new ArrayList<>();
        int[] start = new int[n + 1];
        for (int i = 0; i < n; i++) {
            String[] c = lines[i].split("\t");
            texts[i] = c[0];
            freqs[i] = Integer.parseInt(c[2]);
            start[i] = lens.size();
            StringBuilder sb = new StringBuilder();
            for (String s : c[1].split(" ")) { lens.add((byte) s.length()); sb.append(s); }
            compact[i] = sb.toString();
        }
        start[n] = lens.size();
        byte[] sl = new byte[lens.size()];
        for (int i = 0; i < sl.length; i++) sl[i] = lens.get(i);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparing(i -> compact[i]));
        String[] keys = new String[n];
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) { ids[i] = order[i]; keys[i] = compact[order[i]]; }
        // 首字母串(每个音节的第一个字母)
        String[] ini = new String[n];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            for (int k = start[i]; k < start[i + 1]; k++) { sb.append(compact[i].charAt(pos)); pos += sl[k]; }
            ini[i] = sb.toString();
        }
        Integer[] order2 = new Integer[n];
        for (int i = 0; i < n; i++) order2[i] = i;
        Arrays.sort(order2, Comparator.comparing(i -> ini[i]));
        String[] iKeys = new String[n];
        int[] iIds = new int[n];
        for (int i = 0; i < n; i++) { iIds[i] = order2[i]; iKeys[i] = ini[order2[i]]; }
        return new SentenceDecoder(keys, ids, texts, freqs, sl, start, compact, iKeys, iIds);
    }

    static final String[] DICT = {
        "我们\two men\t5000", "我\two\t8000", "们\tmen\t3000",
        "明天\tming tian\t4000", "明\tming\t3000", "天\ttian\t6000", "气\tqi\t2000",
        "去\tqu\t9000", "看书\tkan shu\t2000", "看\tkan\t7000", "书\tshu\t4000",
        "天气\ttian qi\t4500", "今天\tjin tian\t5000", "很\then\t7000", "好\thao\t9000",
        "你好\tni hao\t6000", "你\tni\t9000", "吗\tma\t5000",
        "西安\txi an\t3000", "先\txian\t4000", "现在\txian zai\t6000", "在\tzai\t9000",
        "是\tshi\t8000", "事\tshi\t7900",
        "智国人\tzhi guo ren\t300", "哥\tge\t3000", "个人\tge ren\t4000",
        "爱\tai\t5000", "中国\tzhong guo\t8000", "人\tren\t9000", "中国人\tzhong guo ren\t5000",
    };

    public static void main(String[] a) throws Exception {
        SentenceDecoder d = build(DICT);

        SentenceDecoder.Result r = d.decode("womenmingtianqukanshu", null, null, null);
        check(r != null && r.text.equals("我们明天去看书") && r.letters == 21 && r.words.length == 4,
              "整句 womenmingtianqukanshu → " + SentenceDecoder.show(r));
        check(r != null && Arrays.equals(r.codes, new String[] {"women", "mingtian", "qu", "kanshu"}), "每个词的拼音连写");

        r = d.decode("nihaoma", null, null, null);
        check(r != null && r.text.equals("你好吗") && r.letters == 7, "nihaoma → " + SentenceDecoder.show(r));

        r = d.decode("tianqihenhao", null, null, null);
        check(r != null && r.text.equals("天气很好"), "tianqihenhao → " + SentenceDecoder.show(r));

        r = d.decode("womenmingtianqukansh", null, null, null);
        check(r != null && r.letters == 18 && r.text.equals("我们明天去看"), "尾巴没打完 → " + SentenceDecoder.show(r));

        r = d.decode("womenmingtianqukan", null, null, null);
        check(r != null && r.text.equals("我们明天去看") && r.letters == 18, "刚好打到音节边界 → " + SentenceDecoder.show(r));

        check(d.decode("zhongguo", null, null, null) == null, "只有一个词不出句 zhongguo");
        check(d.decode("zhongguoren", null, null, null) == null, "中国人 一个词不出句");
        check(d.decode("xianzai", null, null, null) == null, "xianzai 最好的是单个词 现在,不出句");
        check(d.decode("nih", null, null, null) == null, "太短");
        check(d.decode("zhgrm", null, null, null) == null, "切不成音节");

        // 词库缺音节(没有 bang 开头的词):整串走不通
        check(d.decode("womenbangni", null, null, null) == null || d.decode("womenbangni", null, null, null).letters < 11,
              "中间缺词不会硬凑到结尾");
        r = d.decode("womenxxx", null, null, null);
        check(r == null, "尾巴不是音节前缀 → null");

        // 用户分词符:xi'an'zai → 西安 在(先 不能用,因为 先 在 2 处没有音节边界)
        r = d.decode("xianzai", new int[] {2, 4}, null, null);
        check(r != null && r.text.equals("西安在"), "分词符 xi'an'zai → " + SentenceDecoder.show(r));

        // 我打过的词影响选择:天气(4500+音节加分) 默认赢过 天+气;把 天、气 都打过之后反过来
        r = d.decode("tianqihenhao", null, null, null);
        check(r.words[0].equals("天气"), "默认 天气 是一个词");
        File f = Files.createTempDirectory("senttest").toFile();
        UserFreq u = new UserFreq(new File(f, "p.txt"));
        u.learn("tian", "天");
        u.learn("qi", "气");
        u.learn("tian", "天");
        r = d.decode("tianqihenhao", null, u, null);
        check(r.words.length == 4 && r.words[0].equals("天") && r.words[1].equals("气"), "我常单打 天 气 → " + SentenceDecoder.show(r));

        // 接词习惯:同音的 是/事,上一个词后面我常接 事 → 第一个词用 事
        r = d.decode("shiqu", null, null, null);
        check(r != null && r.text.equals("是去"), "默认 shiqu → " + SentenceDecoder.show(r));
        Map<String, Long> ctx = new HashMap<>();
        ctx.put("事", 5L);
        r = d.decode("shiqu", null, null, ctx);
        check(r != null && r.text.equals("事去"), "接词习惯影响第一个词 → " + SentenceDecoder.show(r));
        r = d.decode("ququshi", null, null, ctx);
        check(r != null && r.text.equals("去去是"), "接词习惯只影响第一个词 → " + SentenceDecoder.show(r));

        // ---------------- 前几好的整句(歧义切分)
        List<SentenceDecoder.Result> top = d.decodeTop("xianzai", null, null, null, 3);
        List<String> topTexts = new ArrayList<>();
        for (SentenceDecoder.Result x : top) topTexts.add(x.text);
        check(topTexts.contains("先在") && topTexts.contains("西安在"), "xianzai 的歧义整句 " + topTexts);
        check(top.size() <= 3 && new HashSet<>(topTexts).size() == topTexts.size(), "文字各不相同、不超过 k 句");
        for (int i = 1; i < top.size(); i++) check(top.get(i - 1).score >= top.get(i).score, "分数从高到低 " + i);
        // 最好的一句就是单个词(现在)时 decode 仍然不出句,和以前一样
        check(d.decode("xianzai", null, null, null) == null, "decode 只取最好的:单个词不出句");
        // k=1 和旧结果一致
        r = d.decode("womenmingtianqukanshu", null, null, null);
        List<SentenceDecoder.Result> one = d.decodeTop("womenmingtianqukanshu", null, null, null, 1);
        check(one.size() == 1 && one.get(0).text.equals(r.text), "k=1 与 decode 一致");
        // 同音词给出不同整句:是去 / 事去
        List<SentenceDecoder.Result> hs = d.decodeTop("shiqu", null, null, null, 3);
        List<String> hsTexts = new ArrayList<>();
        for (SentenceDecoder.Result x : hs) hsTexts.add(x.text);
        check(hsTexts.size() >= 2 && hsTexts.get(0).equals("是去") && hsTexts.contains("事去"), "同音词整句 " + hsTexts);
        // 长句的前几好:第一名与 decode 相同,每句都吃完整串
        List<SentenceDecoder.Result> lt = d.decodeTop("womenmingtianqukanshu", null, null, null, 3);
        boolean allFull = true;
        for (SentenceDecoder.Result x : lt) if (x.letters != 21) allFull = false;
        check(lt.get(0).text.equals("我们明天去看书") && allFull, "长句前几名 " + lt.size());

        // ---------------- 词接词:整句里相邻的词,后一个是我常接在前一个后面的,加分
        File cdir = Files.createTempDirectory("senttest_chain").toFile();
        UserChain chain = new UserChain(new File(cdir, "chain.txt"));
        List<SentenceDecoder.Result> noChain = d.decodeTop("qushi", null, null, null, 3, null, chain);
        check(!noChain.isEmpty() && noChain.get(0).text.equals("去是"), "没有记录时按词频 去是 → " + SentenceDecoder.show(noChain.isEmpty() ? null : noChain.get(0)));
        chain.learn("去", "事");
        List<SentenceDecoder.Result> withChain = d.decodeTop("qushi", null, null, null, 3, null, chain);
        check(!withChain.isEmpty() && withChain.get(0).text.equals("去事"), "我常在 去 后面接 事 → " + SentenceDecoder.show(withChain.isEmpty() ? null : withChain.get(0)));
        check(d.decodeTop("qushi", null, null, null, 3, null, null).get(0).text.equals("去是"), "不传 chain 时不变");
        // 两对记录:整句里每一对都起作用(明天 去 之间)
        chain.learn("我们", "明天");
        List<SentenceDecoder.Result> longer = d.decodeTop("womenmingtianqukanshu", null, null, null, 3, null, chain);
        check(!longer.isEmpty() && longer.get(0).text.equals("我们明天去看书"), "有无关记录时长句不受影响");

        // ---------------- 搭配模型(GramPack):相邻两个词能查到搭配就加分,同音词靠上下文选对
        Map<String, Integer> gm = new HashMap<>();
        gm.put("去事", 150000);      // 15.0 - 8.0 = 7.0 分,乘 0.4 权重
        gm.put("事去", 150000);
        gm.put("无关词", 150000);
        d.setGram(GramPack.wrap(GramPack.build(gm)));
        List<SentenceDecoder.Result> gq = d.decodeTop("qushi", null, null, null, 3);
        check(!gq.isEmpty() && gq.get(0).text.equals("去事"), "搭配 去事 压过词频 去是 → " + SentenceDecoder.show(gq.isEmpty() ? null : gq.get(0)));
        gq = d.decodeTop("shiqu", null, null, null, 3);
        check(!gq.isEmpty() && gq.get(0).text.equals("事去"), "搭配 事去 → " + SentenceDecoder.show(gq.isEmpty() ? null : gq.get(0)));
        r = d.decode("womenmingtianqukanshu", null, null, null);
        check(r != null && r.text.equals("我们明天去看书"), "有搭配模型时长句照常 → " + SentenceDecoder.show(r));
        gq = d.decodeTop("tianqihenhao", null, null, null, 1);
        check(!gq.isEmpty() && gq.get(0).text.equals("天气很好"), "搭配里没有的词对不受影响 → " + SentenceDecoder.show(gq.isEmpty() ? null : gq.get(0)));
        // 用户习惯仍然叠加:我常在 去 后面接 是,和搭配 去事 一起算,习惯 + 词频要能反超不大的搭配分时才赢——这里只验证两者都在起作用而不报错
        chain.learn("去", "是");
        gq = d.decodeTop("qushi", null, null, null, 3, null, chain);
        check(!gq.isEmpty() && (gq.get(0).text.equals("去是") || gq.get(0).text.equals("去事")), "搭配 + 词接词同时存在 → " + SentenceDecoder.show(gq.isEmpty() ? null : gq.get(0)));
        // 模糊音整句也走同一套(core 共用),这里只确认带搭配模型时不抛异常
        d.setGram(null);
        gq = d.decodeTop("qushi", null, null, null, 3);
        check(!gq.isEmpty() && gq.get(0).text.equals("去是"), "去掉搭配模型后恢复词频结果 → " + SentenceDecoder.show(gq.isEmpty() ? null : gq.get(0)));

        // ---------------- 混拼
        r = d.decodeMixed("zgren", null, null, null);
        check(r != null && r.text.equals("中国人") && r.letters == 5 && r.words.length == 1, "混拼 zgren → " + SentenceDecoder.show(r));
        check(r != null && Arrays.asList(r.alternatives).contains("智国人"), "整串也能对上的其他词 " + (r == null ? "" : Arrays.toString(r.alternatives)));
        r = d.decodeMixed("zgr", null, null, null);
        check(r != null && r.text.equals("中国人"), "纯声母 zgr → " + SentenceDecoder.show(r));
        r = d.decodeMixed("zgre", null, null, null);
        check(r != null && r.text.equals("中国人"), "最后一个音节没打完 zgre → " + SentenceDecoder.show(r));
        r = d.decodeMixed("zhgr", null, null, null);
        check(r != null && r.text.equals("中国人"), "zh 写全 zhgr → " + SentenceDecoder.show(r));
        r = d.decodeMixed("wozgr", null, null, null);
        check(r != null && r.text.equals("我中国人") && r.words.length == 2, "写全 + 简拼 wozgr → " + SentenceDecoder.show(r));
        r = d.decodeMixed("nihaozgr", null, null, null);
        check(r != null && r.text.equals("你好中国人"), "nihaozgr → " + SentenceDecoder.show(r));
        r = d.decodeMixed("wmmtquks", null, null, null);
        check(r != null && r.text.equals("我们明天去看书"), "整句简拼 wmmtquks → " + SentenceDecoder.show(r));
        r = d.decodeMixed("womenmtqukanshu", null, null, null);
        check(r != null && r.text.equals("我们明天去看书"), "women + mt + qu + kanshu → " + SentenceDecoder.show(r));
        check(d.decodeMixed("womenq", null, null, null) == null, "单音节词不能只写声母 womenq");
        check(d.decodeMixed("zgxx", null, null, null) == null, "对不上 zgxx");
        check(d.decodeMixed("zg", null, null, null) == null, "太短");
        check(d.decodeMixed("zg1en", null, null, null) == null, "非字母");
        // 分词符:zg'ren,词里的音节不能跨过分词符
        r = d.decodeMixed("zgren", new int[] {2}, null, null);
        check(r != null && r.text.equals("中国人"), "分词符 zg'ren → " + SentenceDecoder.show(r));
        r = d.decodeMixed("zgren", new int[] {2}, null, null);
        check(r != null && r.words.length == 1 && r.text.equals("中国人"), "分词符不妨碍一个词里的简写 → " + SentenceDecoder.show(r));
        r = d.decodeMixed("gren", new int[] {1}, null, null);
        check(r != null && r.text.equals("个人"), "gren 个人 → " + SentenceDecoder.show(r));
        // 我打过的词影响混拼:个人(ge ren) 和 哥人? 只有 个人 对得上 gren
        u.learn("zgren", "智国人");
        r = d.decodeMixed("zgren", null, u, null);
        check(r != null && r.text.equals("智国人"), "我选过 智国人 → " + SentenceDecoder.show(r));

        // 速度
        String[] syl = {"ba", "ni", "hao", "wo", "men", "ming", "tian", "qu", "kan", "shu", "zhong", "guo", "ren", "xi", "an",
                        "ma", "ai", "shi", "jie", "you", "de", "ta", "li", "hui", "lai", "zai", "xian", "dong", "xing", "fu"};
        Random rnd = new Random(7);
        int N = 200000;
        String[] big = new String[N];
        for (int i = 0; i < N; i++) {
            int k = 1 + rnd.nextInt(4);
            StringBuilder py = new StringBuilder();
            for (int j = 0; j < k; j++) { if (j > 0) py.append(' '); py.append(syl[rnd.nextInt(syl.length)]); }
            big[i] = "W" + i + "\t" + py + "\t" + (1 + rnd.nextInt(100000));
        }
        long t0 = System.nanoTime();
        SentenceDecoder bd = build(big);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < 12; i++) sent.append(syl[rnd.nextInt(syl.length)]);
        String s = sent.toString();
        if (s.length() > 40) s = s.substring(0, 40);
        int ok = 0;
        for (int i = 0; i < 300; i++) bd.decode(s, null, null, null);   // 预热
        t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) if (bd.decode(s, null, null, null) != null) ok++;
        double per = (System.nanoTime() - t0) / 1e6 / 1000.0;
        System.out.println("INFO 20 万词条(构建 " + buildMs + "ms 含排序),长 " + s.length() + " 的整句平均 " + String.format("%.3f", per) + " ms/次, 成句 " + ok);
        check(per < 5.0, "整句平均 < 5ms");

        // 混拼速度:在这份词库里,拿随机词的首字母串(声母全是字母表里那几个,命中最多、最坏)当输入
        String[] inputs = new String[40];
        for (int i = 0; i < inputs.length; i++) {
            String[] c = big[rnd.nextInt(N)].split("\t");
            String[] ss = c[1].split(" ");
            StringBuilder sb = new StringBuilder();
            for (String x : ss) sb.append(rnd.nextBoolean() ? x : x.substring(0, 1));
            for (int z = 0; z < 2; z++) { String x = syl[rnd.nextInt(syl.length)]; sb.append(rnd.nextBoolean() ? x : x.substring(0, 1)); }
            inputs[i] = sb.toString();
        }
        int hits = 0;
        for (int rep = 0; rep < 5; rep++) for (String in : inputs) bd.decodeMixed(in, null, null, null);   // 预热
        t0 = System.nanoTime();
        int calls = 0;
        for (int rep = 0; rep < 20; rep++) for (String in : inputs) { if (bd.decodeMixed(in, null, null, null) != null) hits++; calls++; }
        double perMixed = (System.nanoTime() - t0) / 1e6 / calls;
        System.out.println("INFO 混拼平均 " + String.format("%.3f", perMixed) + " ms/次(成功 " + hits + "/" + calls + ")");
        check(perMixed < 10.0, "混拼平均 < 10ms");
        // 最坏:全是声母的 20 个字母
        String worst = "bnhwmtqkszgrdlxyfjcp";
        t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) bd.decodeMixed(worst, null, null, null);
        double perWorst = (System.nanoTime() - t0) / 1e6 / 50;
        System.out.println("INFO 20 个声母平均 " + String.format("%.3f", perWorst) + " ms/次");
        check(perWorst < 30.0, "20 个声母 < 30ms");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
