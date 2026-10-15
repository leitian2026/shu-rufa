package com.example.myime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 拼错容错:只在"整串怎么也解释不了"时才用(见 PinyinEngine),所以平时一点开销都没有。
 * 纯 JDK,可脱离手机测试,见 tools/TypoSelfTest.java。
 *
 * 做法:找到输入里第一个出错的位置(Syllabary.validPrefixLen),只在它附近几个字母的范围内试改一处:
 *   ① 相邻两个字母写反了(nihoa → nihao)
 *   ② 按错成键盘上相邻的键(womwn → women)
 *   ③ 多打了一个字母(nihaao → nihao)
 *   ④ 漏打了一个字母(nhao → nihao)
 * 改完必须是合法的拼音写法(能切成音节,末尾可以没打完)才留下;按上面的顺序、离出错位置近的优先,最多给 max 个。
 * 只改一处:两处以上都错的不处理。
 */
public final class Typo {
    private Typo() {}

    /** 每个字母在标准键盘上相邻的键(左右、上下斜对)。 */
    private static final String[] NEAR = {
        "qwsz",    // a
        "vghn",    // b
        "xdfv",    // c
        "serfcx",  // d
        "wsdr",    // e
        "drtgvc",  // f
        "ftyhbv",  // g
        "gyujnb",  // h
        "ujko",    // i
        "huikmn",  // j
        "jiolm",   // k
        "kop",     // l
        "njk",     // m
        "bhjm",    // n
        "iklp",    // o
        "ol",      // p
        "wa",      // q
        "edft",    // r
        "awedxz",  // s
        "rfgy",    // t
        "yhji",    // u
        "cfgb",    // v
        "qase",    // w
        "zsdc",    // x
        "tghu",    // y
        "asx",     // z
    };

    private static final int WINDOW_BEFORE = 2;   // 出错位置往前看几个字母
    private static final int WINDOW_AFTER = 1;    // 往后看几个字母
    private static final int MAX_LEN = 24;        // 太长不处理

    /**
     * @param p   输入(只含 a-z,没有分词符)
     * @param max 最多返回几个写法
     * @return 只差一处、且是合法拼音写法的写法,按可能性从高到低;输入本身就合法或太短太长返回空
     */
    public static List<String> variants(String p, int max) {
        int n = p.length();
        List<String> out = new ArrayList<>();
        if (n < 3 || n > MAX_LEN || max <= 0) return out;
        for (int i = 0; i < n; i++) {
            char c = p.charAt(i);
            if (c < 'a' || c > 'z') return out;
        }
        int bad = Syllabary.validPrefixLen(p);
        int e = bad >= n ? n - 1 : bad;       // 出错的字母位置;整串合法时看最后几个
        int from = Math.max(0, e - WINDOW_BEFORE);
        int to = Math.min(n - 1, e + WINDOW_AFTER);
        HashSet<String> seen = new HashSet<>();
        seen.add(p);
        StringBuilder sb = new StringBuilder(n + 1);

        // 离出错位置近的优先:按到 e 的距离从小到大排位置
        int[] order = positions(from, to, e);
        // ① 写反
        for (int i : order) {
            if (i + 1 >= n || p.charAt(i) == p.charAt(i + 1)) continue;
            sb.setLength(0);
            sb.append(p, 0, i).append(p.charAt(i + 1)).append(p.charAt(i)).append(p, i + 2, n);
            if (!add(out, seen, sb, max)) return out;
        }
        // ② 按到相邻的键
        for (int i : order) {
            String near = NEAR[p.charAt(i) - 'a'];
            for (int k = 0; k < near.length(); k++) {
                sb.setLength(0);
                sb.append(p, 0, i).append(near.charAt(k)).append(p, i + 1, n);
                if (!add(out, seen, sb, max)) return out;
            }
        }
        // ③ 多打了一个
        for (int i : order) {
            sb.setLength(0);
            sb.append(p, 0, i).append(p, i + 1, n);
            if (sb.length() >= 2 && !add(out, seen, sb, max)) return out;
        }
        // ④ 漏打了一个:插在 i 前面(i 可以是 n,也就是末尾)
        int[] ins = positions(Math.max(0, e - WINDOW_BEFORE), Math.min(n, e + WINDOW_BEFORE), e);
        for (int i : ins) {
            for (char c = 'a'; c <= 'z'; c++) {
                sb.setLength(0);
                sb.append(p, 0, i).append(c).append(p, i, n);
                if (!add(out, seen, sb, max)) return out;
            }
        }
        return out;
    }

    /** 合法的、没出现过的就加进去;返回还能不能继续加。 */
    private static boolean add(List<String> out, HashSet<String> seen, StringBuilder sb, int max) {
        String v = sb.toString();
        if (!seen.add(v)) return true;
        if (Syllabary.validPrefixLen(v) != v.length()) return true;
        out.add(v);
        return out.size() < max;
    }

    /** from..to 里的位置,按离 center 的距离从近到远(同样远的靠前的先)。 */
    private static int[] positions(int from, int to, int center) {
        int cnt = to - from + 1;
        if (cnt <= 0) return new int[0];
        int[] r = new int[cnt];
        int k = 0;
        for (int d = 0; k < cnt; d++) {
            int a = center - d;
            int b = center + d;
            if (a >= from && a <= to) r[k++] = a;
            if (d > 0 && b >= from && b <= to && k < cnt) r[k++] = b;
            if (d > cnt + 30) break;
        }
        return r;
    }
}
