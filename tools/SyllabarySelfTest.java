// 音节表/切分器(Syllabary)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/syltest app/src/main/java/com/example/myime/Syllabary.java tools/SyllabarySelfTest.java
//   java -cp /tmp/syltest SyllabarySelfTest
// 覆盖:音节总数;内置词库里的每个音节都合法;切分(最少音节优先、没打完的尾巴、切不开);
//       ü 别写(jv/yve/lue/nue/ü);分词符分段处理;长度不变;不误伤正常拼音;速度。
import com.example.myime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SyllabarySelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static String seg(String p) {
        int[] e = Syllabary.segment(p);
        if (e == null) return "null";
        StringBuilder sb = new StringBuilder();
        int from = 0;
        for (int x : e) { if (sb.length() > 0) sb.append(' '); sb.append(p, from, x); from = x; }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        int n = Syllabary.standardCount();
        check(n >= 400 && n <= 430, "标准音节数在 400~430 之间: " + n);
        for (String s : new String[] {"zhuang", "shuang", "chuang", "nv", "lve", "nve", "lo", "yo", "dei", "den", "nou", "er", "a", "o", "e", "dia"}) {
            check(Syllabary.isSyllable(s), "合法音节 " + s);
        }
        for (String s : new String[] {"zhn", "vv", "ii", "bue", "wv", "xx", "ng"}) {
            check(!Syllabary.isSyllable(s), "非法音节 " + s);
        }
        check(Syllabary.isPrefix("zhon") && Syllabary.isPrefix("zh") && Syllabary.isPrefix("sh"), "前缀 zhon zh sh");
        check(!Syllabary.isPrefix("zhz"), "非前缀 zhz");

        // 内置词库里每个音节都得是合法音节(查表漏了就会在这里报出来)
        Path dict = Paths.get("app/src/main/assets/pinyin.txt");
        if (Files.exists(dict)) {
            Set<String> bad = new TreeSet<>();
            for (String line : Files.readAllLines(dict, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] c = line.split("\t");
                if (c.length < 2) continue;
                for (String s : c[1].trim().split(" ")) if (!s.isEmpty() && !Syllabary.isSyllable(s)) bad.add(s);
            }
            check(bad.isEmpty(), "内置词库音节全部合法 " + bad);
        }

        // 切分
        check(seg("nihao").equals("ni hao"), "nihao → ni hao");
        check(seg("xian").equals("xian"), "xian 优先一个音节");
        check(seg("xianzai").equals("xian zai"), "xianzai");
        check(seg("womenmingtianqukanshu").equals("wo men ming tian qu kan shu"), "整句 womenmingtianqukanshu");
        check(seg("zhongguo").equals("zhong guo"), "zhongguo");
        check(seg("zhon").equals("zhon"), "没打完的尾巴 zhon");
        check(seg("nihaozhon").equals("ni hao zhon"), "尾巴 nihaozhon");
        check(seg("zhz").equals("null"), "切不开 zhz");
        check(seg("").equals("null"), "空串");
        check(seg("fangan").equals("fang an"), "fangan → fang an(同样少时前面更长)");
        check(seg("shuang").equals("shuang"), "shuang");
        check(seg("jvn").equals("jvn"), "jvn 一个音节(别写)");

        // ü 别写
        String[][] norm = {
            {"jv", "ju"}, {"qv", "qu"}, {"xv", "xu"}, {"yv", "yu"},
            {"jvan", "juan"}, {"yvan", "yuan"}, {"jve", "jue"}, {"yve", "yue"}, {"xvn", "xun"}, {"qvn", "qun"},
            {"lue", "lve"}, {"nue", "nve"}, {"nv", "nv"}, {"lv", "lv"}, {"lve", "lve"},
            {"nvhai", "nvhai"}, {"lvxing", "lvxing"}, {"xilue", "xilve"}, {"jvzi", "juzi"},
            {"l\u00fc", "lv"}, {"n\u00fce", "nve"}, {"j\u00fc", "ju"},
            {"jva", "jua"}, {"jvanx", "juanx"},
            {"luen", "luen"}, {"nihao", "nihao"}, {"xue", "xue"}, {"jue", "jue"}, {"yue", "yue"}, {"que", "que"},
            {"", ""}, {"abc", "abc"},
        };
        for (String[] t : norm) {
            String r = Syllabary.normalize(t[0]);
            check(r.equals(t[1]) && r.length() == t[0].length(), "normalize " + t[0] + " → " + t[1] + " (得到 " + r + ")");
        }
        // 没改动就返回同一个对象(不产生垃圾)
        String same = "nihaoma";
        check(Syllabary.normalize(same) == same, "没要改的返回原对象");

        // 带分词符
        check(Syllabary.normalizeRaw("jv'zi").equals("ju'zi"), "raw jv'zi → ju'zi");
        check(Syllabary.normalizeRaw("xi'lue").equals("xi'lve"), "raw xi'lue → xi'lve");
        check(Syllabary.normalizeRaw("lu'e").equals("lu'e"), "raw lu'e 用户明确分开,不当成 lue");
        check(Syllabary.normalizeRaw("'jv'").equals("'ju'"), "raw 首尾分词符");
        check(Syllabary.normalizeRaw("xian").equals("xian"), "raw 无分词符");
        String r2 = Syllabary.normalizeRaw("jv'zi'yve");
        check(r2.equals("ju'zi'yue") && r2.length() == 9, "raw 多段 " + r2);

        // 速度:一万次 normalize + segment(含 ü 别写的最坏情形),要在很短时间内完成
        long t0 = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < 10000; i++) {
            sink += Syllabary.normalize("xilueyvanjvzinvhai").length();
            sink += Syllabary.segment("womenmingtianqukanshu").length;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(ms < 1500, "1 万次 normalize+segment 耗时 " + ms + "ms (sink=" + sink + ")");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
