// 模糊音(Fuzzy)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   D=app/src/main/java/com/example/myime
//   javac -encoding UTF-8 -d /tmp/fuzzytest $D/Syllabary.java $D/SafeFile.java $D/LearnShare.java $D/UserFreq.java $D/UserChain.java \
//         $D/Fuzzy.java $D/SentenceDecoder.java tools/stub/com/example/myime/ErrorHint.java tools/FuzzySelfTest.java
//   java -Dstdout.encoding=UTF-8 -cp /tmp/fuzzytest FuzzySelfTest
// 覆盖:单个音节的折叠规则;输入折叠(音节边界、没打完的尾巴);规范形索引(z/zh、an/ang 等能互相查到);
//       模糊音整句(写法错了也能拼出整句,codes 仍是原输入);没开规则时不改任何东西;建索引和查询的耗时。
import com.example.myime.*;
import java.util.*;

public class FuzzySelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static final class Dict {
        String[] texts, compact; int[] freqs, start; byte[] sl;
        SentenceDecoder dec;
    }

    static Dict build(String[] lines) {
        int n = lines.length;
        Dict d = new Dict();
        d.texts = new String[n]; d.compact = new String[n]; d.freqs = new int[n];
        List<Byte> lens = new ArrayList<>();
        d.start = new int[n + 1];
        for (int i = 0; i < n; i++) {
            String[] c = lines[i].split("\t");
            d.texts[i] = c[0];
            d.freqs[i] = Integer.parseInt(c[2]);
            d.start[i] = lens.size();
            StringBuilder sb = new StringBuilder();
            for (String s : c[1].split(" ")) { lens.add((byte) s.length()); sb.append(s); }
            d.compact[i] = sb.toString();
        }
        d.start[n] = lens.size();
        d.sl = new byte[lens.size()];
        for (int i = 0; i < d.sl.length; i++) d.sl[i] = lens.get(i);
        Integer[] o = new Integer[n];
        for (int i = 0; i < n; i++) o[i] = i;
        Arrays.sort(o, Comparator.comparing(i -> d.compact[i]));
        String[] keys = new String[n]; int[] ids = new int[n];
        for (int i = 0; i < n; i++) { ids[i] = o[i]; keys[i] = d.compact[o[i]]; }
        String[] ini = new String[n];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(); int pos = 0;
            for (int k = d.start[i]; k < d.start[i + 1]; k++) { sb.append(d.compact[i].charAt(pos)); pos += d.sl[k]; }
            ini[i] = sb.toString();
        }
        Integer[] o2 = new Integer[n];
        for (int i = 0; i < n; i++) o2[i] = i;
        Arrays.sort(o2, Comparator.comparing(i -> ini[i]));
        String[] ik = new String[n]; int[] ii = new int[n];
        for (int i = 0; i < n; i++) { ii[i] = o2[i]; ik[i] = ini[o2[i]]; }
        d.dec = new SentenceDecoder(keys, ids, d.texts, d.freqs, d.sl, d.start, d.compact, ik, ii);
        return d;
    }

    /** 在规范形索引里找 [key, key+\0) 里的词。 */
    static Set<String> exact(Dict d, Fuzzy.Index fi, String key) {
        int lo = lb(fi.keys, key), hi = lb(fi.keys, key + '\u0000');
        Set<String> out = new TreeSet<>();
        for (int i = lo; i < hi; i++) out.add(d.texts[fi.ids[i]]);
        return out;
    }
    static Set<String> prefix(Dict d, Fuzzy.Index fi, String key) {
        int lo = lb(fi.keys, key), hi = lb(fi.keys, key + '\uffff');
        Set<String> out = new TreeSet<>();
        for (int i = lo; i < hi; i++) out.add(d.texts[fi.ids[i]]);
        return out;
    }
    static int lb(String[] a, String t) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m].compareTo(t) < 0) lo = m + 1; else hi = m; }
        return lo;
    }

    static final String[] DICT = {
        "知\tzhi\t8000", "资\tzi\t7000", "是\tshi\t9000", "四\tsi\t6000", "吃\tchi\t6000", "词\tci\t5000",
        "想\txiang\t8000", "先\txian\t7000", "西安\txi an\t3000", "场\tchang\t6000", "蚕\tcan\t2000",
        "能\tneng\t8000", "冷\tleng\t6000", "女\tnv\t5000", "旅\tlv\t5000", "南\tnan\t5000", "蓝\tlan\t5000",
        "湖\thu\t6000", "福\tfu\t6000", "话\thua\t7000", "法\tfa\t7000", "风\tfeng\t6000", "分\tfen\t6000", "很\then\t7000",
        "星\txing\t6000", "心\txin\t8000", "情\tqing\t6000", "亲\tqin\t5000",
        "山\tshan\t6000", "三\tsan\t6000", "书\tshu\t4000", "我们\two men\t5000", "我\two\t8000", "们\tmen\t3000",
        "明天\tming tian\t4000", "天\ttian\t6000", "明\tming\t3000", "去\tqu\t9000", "看书\tkan shu\t2000", "看\tkan\t7000",
        "中国\tzhong guo\t8000", "重\tzhong\t5000", "国\tguo\t6000", "在\tzai\t9000",
    };

    public static void main(String[] a) throws Exception {
        int ALL = Fuzzy.ZH | Fuzzy.CH | Fuzzy.SH | Fuzzy.ANG | Fuzzy.ENG | Fuzzy.ING | Fuzzy.LN | Fuzzy.FH;

        // 音节折叠
        String[][] cs = {
            {"zhi", "zi"}, {"chi", "ci"}, {"shi", "si"}, {"zhang", "zan"}, {"chang", "can"}, {"shang", "san"},
            {"xiang", "xian"}, {"yang", "yan"}, {"ang", "an"}, {"zheng", "zen"}, {"feng", "fen"}, {"ying", "yin"},
            {"ming", "min"}, {"nv", "lv"}, {"nan", "lan"}, {"neng", "len"}, {"hu", "fu"}, {"huang", "fuan"},
            {"hong", "fong"}, {"ong", "ong"}, {"zhong", "zong"}, {"guo", "guo"}, {"lv", "lv"}, {"zh", "z"}, {"n", "l"}, {"xi", "xi"}, {"e", "e"},
        };
        for (String[] c : cs) {
            String r = Fuzzy.canonSyllable(c[0], ALL);
            check(r.equals(c[1]), "折叠 " + c[0] + " → " + c[1] + " (得到 " + r + ")");
        }
        check(Fuzzy.canonSyllable("zhang", 0).equals("zhang"), "没开规则不折叠");
        check(Fuzzy.canonSyllable("zhang", Fuzzy.ZH).equals("zang"), "只开 z/zh");
        check(Fuzzy.canonSyllable("zhang", Fuzzy.ANG).equals("zhan"), "只开 an/ang");
        check(Fuzzy.canonSyllable("shang", Fuzzy.CH | Fuzzy.ENG).equals("shang"), "开的规则不相关就不变");
        String same = "guo";
        check(Fuzzy.canonSyllable(same, ALL) == same, "没要折的返回原对象");
        check(Fuzzy.canonSyllable("hong", Fuzzy.FH).equals("fong"), "只开 f/h");

        // 输入折叠
        Fuzzy.Canon cn = Fuzzy.canon("zhongguo", ALL);
        check(cn != null && cn.canon.equals("zongguo") && Arrays.equals(cn.canonEnds, new int[] {4, 7})
              && Arrays.equals(cn.origEnds, new int[] {5, 8}) && cn.tailComplete, "canon zhongguo → zongguo");
        cn = Fuzzy.canon("xiangzhi", ALL);
        check(cn != null && cn.canon.equals("xianzi"), "canon xiangzhi → xianzi");
        cn = Fuzzy.canon("zhon", ALL);
        check(cn != null && cn.canon.equals("zon") && !cn.tailComplete, "没打完的尾巴 zhon → zon");
        check(Fuzzy.canon("zhz", ALL) == null, "切不开返回 null");
        check(Fuzzy.canon("zhongguo", 0) == null, "没开规则返回 null");

        // 规范形索引:互相查得到
        Dict d = build(DICT);
        Fuzzy.Index fi = Fuzzy.Index.build(d.compact, d.sl, d.start, ALL);
        check(exact(d, fi, "zi").containsAll(Arrays.asList("知", "资")), "zi 同时查到 知(zhi) 和 资(zi) " + exact(d, fi, "zi"));
        check(exact(d, fi, "si").containsAll(Arrays.asList("是", "四")), "si → 是 四 " + exact(d, fi, "si"));
        check(exact(d, fi, "ci").containsAll(Arrays.asList("吃", "词")), "ci → 吃 词");
        check(exact(d, fi, "xian").containsAll(Arrays.asList("想", "先", "西安")), "xian → 想(xiang) 先 西安 " + exact(d, fi, "xian"));
        check(exact(d, fi, "can").containsAll(Arrays.asList("场", "蚕")), "can → 场(chang) 蚕");
        check(exact(d, fi, "len").containsAll(Arrays.asList("能", "冷")), "len → 能(neng) 冷(leng) " + exact(d, fi, "len"));
        check(exact(d, fi, "lv").containsAll(Arrays.asList("女", "旅")), "lv → 女(nv) 旅(lv)");
        check(exact(d, fi, "lan").containsAll(Arrays.asList("南", "蓝")), "lan → 南(nan) 蓝(lan)");
        check(exact(d, fi, "fu").containsAll(Arrays.asList("湖", "福")), "fu → 湖(hu) 福(fu)");
        check(exact(d, fi, "fen").containsAll(Arrays.asList("风", "分", "很")), "fen → 风(feng) 分(fen) 很(hen) " + exact(d, fi, "fen"));
        check(exact(d, fi, "yan").isEmpty(), "没有的音查不到");
        check(prefix(d, fi, "xia").containsAll(Arrays.asList("想", "先", "西安")) || prefix(d, fi, "xi").contains("先"), "前缀 xi 能补全");
        // 只开一条规则:z/zh,不影响别的
        Fuzzy.Index fz = Fuzzy.Index.build(d.compact, d.sl, d.start, Fuzzy.ZH);
        check(exact(d, fz, "zi").containsAll(Arrays.asList("知", "资")), "只开 z/zh:zi → 知 资");
        check(!exact(d, fz, "si").contains("是"), "只开 z/zh:si 查不到 是");
        check(exact(d, fz, "xiang").contains("想") && !exact(d, fz, "xiang").contains("先"), "只开 z/zh:xiang 不带出 先");
        // 没受影响的词共用原来的字符串(不占新内存)
        boolean shared = false;
        for (int i = 0; i < fi.keys.length; i++) if (d.texts[fi.ids[i]].equals("国") && fi.keys[i] == d.compact[fi.ids[i]]) shared = true;
        check(shared, "没受影响的词共用原字符串");

        // 模糊音整句:写错了也能拼出来,letters/codes 仍是原输入
        String in = "womenmingtiansanshu";
        Fuzzy.Index fs = Fuzzy.Index.build(d.compact, d.sl, d.start, Fuzzy.SH);
        Fuzzy.Canon c2 = Fuzzy.canon(in, Fuzzy.SH);
        List<SentenceDecoder.Result> fr = d.dec.decodeFuzzyTop(in, c2, fs.keys, fs.ids, null, null, 3);
        check(!fr.isEmpty() && fr.get(0).text.startsWith("我们明天") && fr.get(0).text.endsWith("书") && fr.get(0).letters == in.length(),
              "模糊音整句 " + (fr.isEmpty() ? "[]" : fr.get(0).text));
        if (!fr.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String c : fr.get(0).codes) sb.append(c);
            check(sb.toString().equals(in), "codes 拼回去等于原输入 " + Arrays.toString(fr.get(0).codes));
        }
        // 打 san 想要 shan:模糊音下 山书 也在候选里
        Set<String> frTexts = new HashSet<>();
        for (SentenceDecoder.Result x : fr) frTexts.add(x.text);
        check(frTexts.contains("我们明天山书") || frTexts.contains("我们明天三书"), "san 既能是 三 也能是 山 " + frTexts);
        // 写对了的整句在没开规则时同样能出
        check(d.dec.decode("womenmingtianqukanshu", null, null, null) != null, "原来的整句不受影响");
        // 没打完的尾巴:模糊音整句不用
        Fuzzy.Canon c3 = Fuzzy.canon("womenmingtiansansh", Fuzzy.SH);
        check(d.dec.decodeFuzzyTop("womenmingtiansansh", c3, fs.keys, fs.ids, null, null, 3).isEmpty(), "尾巴没打完不出模糊音整句");
        // 单音节不成句
        Fuzzy.Canon c4 = Fuzzy.canon("zhongguo", ALL);
        Fuzzy.Index fa = Fuzzy.Index.build(d.compact, d.sl, d.start, ALL);
        check(d.dec.decodeFuzzyTop("zhongguo", c4, fa.keys, fa.ids, null, null, 3).stream().allMatch(x -> x.words.length >= 2), "整句至少两个词");

        // 速度:20 万词条建索引,折叠 + 查询
        String[] syl = {"zhi", "zi", "shi", "si", "chi", "ci", "xiang", "xian", "ni", "li", "hao", "wo", "men", "ming", "tian",
                        "qu", "kan", "shu", "zhong", "guo", "ren", "ang", "an", "ma", "ai", "feng", "fen", "hu", "fu", "neng"};
        Random rnd = new Random(11);
        int N = 200000;
        String[] big = new String[N];
        for (int i = 0; i < N; i++) {
            int k = 1 + rnd.nextInt(4);
            StringBuilder py = new StringBuilder();
            for (int j = 0; j < k; j++) { if (j > 0) py.append(' '); py.append(syl[rnd.nextInt(syl.length)]); }
            big[i] = "W" + i + "\t" + py + "\t" + (1 + rnd.nextInt(100000));
        }
        Dict bd = build(big);
        long t0 = System.nanoTime();
        Fuzzy.Index bi = Fuzzy.Index.build(bd.compact, bd.sl, bd.start, ALL);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("INFO 20 万词条建规范形索引 " + buildMs + "ms");
        check(buildMs < 5000, "建索引 < 5s");
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < 9; i++) sent.append(syl[rnd.nextInt(syl.length)]);
        String bs = sent.toString();
        Fuzzy.Canon bc = Fuzzy.canon(bs, ALL);
        for (int i = 0; i < 200; i++) bd.dec.decodeFuzzyTop(bs, bc, bi.keys, bi.ids, null, null, 2);
        t0 = System.nanoTime();
        int okc = 0;
        for (int i = 0; i < 500; i++) {
            Fuzzy.Canon c = Fuzzy.canon(bs, ALL);
            if (!bd.dec.decodeFuzzyTop(bs, c, bi.keys, bi.ids, null, null, 2).isEmpty()) okc++;
        }
        double per = (System.nanoTime() - t0) / 1e6 / 500;
        System.out.println("INFO 长 " + bs.length() + " 的模糊音整句平均 " + String.format("%.3f", per) + " ms/次(成句 " + okc + "/500)");
        check(per < 5.0, "模糊音整句 < 5ms");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
