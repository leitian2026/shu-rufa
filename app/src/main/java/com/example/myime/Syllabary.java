package com.example.myime;

import java.util.HashMap;
import java.util.HashSet;

/**
 * 拼音音节表和切分器(纯 JDK,可以脱离手机单独测试,见 tools/SyllabarySelfTest.java)。
 *
 * 做三件事:
 *   1. 知道哪些是合法音节(约 410 个),哪些是音节的前半截(输入没打完时)。
 *   2. segment():把一串字母切成音节,音节数最少的优先,同样少时前面的音节更长;
 *      最后一段允许是没打完的音节前缀(比如 zhon)。
 *   3. normalize():把 ü 的几种写法统一成词库里的写法。词库里是 nv lv lve nve,
 *      而 ju qu xu yu 系列就是 ju qu xu yu,所以 jv→ju、yve→yue、lue→lve、nue→nve,ü→v。
 *      替换前后长度完全一样,"消耗了几个字母"的换算不受影响。
 *
 * 全是静态只读表,第一次用到时建一次,查询时不分配多余对象。
 */
public final class Syllabary {
    private Syllabary() {}

    public static final int MAX_LEN = 6;   // zhuang / shuang / chuang
    private static final int MAX_NORMALIZE = 48;   // 太长的串不处理,免得白费 CPU

    private static final String TABLE =
        "b:a ai an ang ao ei en eng i ian iao ie in ing o u|" +
        "p:a ai an ang ao ei en eng i ian iao ie in ing o ou u|" +
        "m:a ai an ang ao e ei en eng i ian iao ie in ing iu o ou u|" +
        "f:a an ang ei en eng o ou u|" +
        "d:a ai an ang ao e ei en eng i ia ian iao ie ing iu ong ou u uan ui un uo|" +
        "t:a ai an ang ao e eng i ian iao ie ing ong ou u uan ui un uo|" +
        "n:a ai an ang ao e ei en eng i ian iang iao ie in ing iu ong ou u uan un uo v ve|" +
        "l:a ai an ang ao e ei eng i ia ian iang iao ie in ing iu o ong ou u uan un uo v ve|" +
        "g:a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo|" +
        "k:a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo|" +
        "h:a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo|" +
        "j:i ia ian iang iao ie in ing iong iu u uan ue un|" +
        "q:i ia ian iang iao ie in ing iong iu u uan ue un|" +
        "x:i ia ian iang iao ie in ing iong iu u uan ue un|" +
        "zh:a ai an ang ao e ei en eng i ong ou u ua uai uan uang ui un uo|" +
        "ch:a ai an ang ao e en eng i ong ou u ua uai uan uang ui un uo|" +
        "sh:a ai an ang ao e ei en eng i ou u ua uai uan uang ui un uo|" +
        "r:an ang ao e en eng i ong ou u ua uan ui un uo|" +
        "z:a ai an ang ao e ei en eng i ong ou u uan ui un uo|" +
        "c:a ai an ang ao e en eng i ong ou u uan ui un uo|" +
        "s:a ai an ang ao e en eng i ong ou u uan ui un uo|" +
        "y:a an ang ao e i in ing o ong ou u uan ue un|" +
        "w:a ai an ang ei en eng o u|" +
        ":a ai an ang ao e ei en eng er o ou";

    private static final HashSet<String> SYL = new HashSet<>();      // 标准音节
    private static final HashMap<String, String> ALIAS = new HashMap<>();   // 别写 → 标准写法
    private static final HashSet<String> PREFIX = new HashSet<>();   // 标准音节和别写的所有前缀(含自身)

    static {
        for (String group : TABLE.split("\\|")) {
            int colon = group.indexOf(':');
            String ini = group.substring(0, colon);
            for (String fin : group.substring(colon + 1).split(" ")) {
                SYL.add(ini + fin);
            }
        }
        // 别写:j q x y 后面的 ü 写成 v;lüe / nüe 写成 lue / nue
        for (String s : new String[] {"ju", "juan", "jue", "jun", "qu", "quan", "que", "qun",
                "xu", "xuan", "xue", "xun", "yu", "yuan", "yue", "yun"}) {
            ALIAS.put(s.charAt(0) + "v" + s.substring(2), s);
        }
        ALIAS.put("lue", "lve");
        ALIAS.put("nue", "nve");
        for (String s : SYL) addPrefixes(s);
        for (String s : ALIAS.keySet()) addPrefixes(s);
    }

    private static void addPrefixes(String s) {
        for (int i = 1; i <= s.length(); i++) PREFIX.add(s.substring(0, i));
    }

    /** 是不是完整音节(标准写法或别写)。 */
    public static boolean isSyllable(String s) {
        return SYL.contains(s) || ALIAS.containsKey(s);
    }

    /** 是不是某个音节的前半截(完整音节也算)。 */
    public static boolean isPrefix(String s) {
        return PREFIX.contains(s);
    }

    private static boolean isSyl(String p, int from, int to) {
        return isSyllable(p.substring(from, to));
    }

    /**
     * 切分 p(只含 a-z,没有分词符)。返回每个音节的结束位置(升序,最后一个等于 p.length());
     * 切不开返回 null。最后一段可以是没打完的音节前缀。
     */
    public static int[] segment(String p) {
        int n = p.length();
        if (n == 0 || n > 64) return null;
        int INF = 1 << 20;
        // f[i] = 从 i 切到结尾的最小代价;nxt[i] = 第一段的结束位置。
        // 每个完整音节代价 2,没打完的尾巴代价 3:音节数最少优先,同样少时完整的比带半截尾巴的好(luen → lu en,而不是 lue n)。
        int[] f = new int[n + 1];
        int[] nxt = new int[n + 1];
        f[n] = 0;
        for (int i = n - 1; i >= 0; i--) {
            int best = INF;
            int bestEnd = -1;
            int maxEnd = Math.min(n, i + MAX_LEN);
            for (int e = maxEnd; e > i; e--) {   // 长的先试,代价相同时留下前面更长的
                if (f[e] >= INF) continue;
                int cost;
                if (isSyl(p, i, e)) cost = 2;
                else if (e == n && PREFIX.contains(p.substring(i, e))) cost = 3;
                else continue;
                if (f[e] + cost < best) {
                    best = f[e] + cost;
                    bestEnd = e;
                }
            }
            f[i] = best;
            nxt[i] = bestEnd;
        }
        if (f[0] >= INF) return null;
        int cnt = 0;
        for (int i = 0; i != n; i = nxt[i]) cnt++;
        int[] out = new int[cnt];
        int k = 0;
        for (int i = 0; i != n; i = nxt[i]) out[k++] = nxt[i];
        return out;
    }

    /**
     * 最长的 i,使得 p 的前 i 个字母能切成完整音节、末尾再带一个没打完的音节(也可以没有尾巴)。
     * 等于 p.length() 说明整串是合法的拼音写法;小于它,第 i 个字母(从 0 数)就是第一个出错的位置。
     */
    public static int validPrefixLen(String p) {
        int n = p.length();
        if (n > 64) n = 64;
        boolean[] pre = new boolean[n + 1];
        pre[0] = true;
        for (int j = 1; j <= n; j++) {
            for (int len = 1; len <= MAX_LEN && len <= j; len++) {
                if (pre[j - len] && isSyllable(p.substring(j - len, j))) {
                    pre[j] = true;
                    break;
                }
            }
        }
        for (int i = n; i >= 1; i--) {
            for (int len = 0; len <= MAX_LEN && len <= i; len++) {
                if (pre[i - len] && (len == 0 || PREFIX.contains(p.substring(i - len, i)))) return i;
            }
        }
        return 0;
    }

    /** 把一段(不含 ')里的 ü 别写统一成词库写法;长度不变,没有要改的就原样返回同一个对象。 */
    public static String normalize(String p) {
        int n = p.length();
        if (n == 0 || n > MAX_NORMALIZE) return p;
        // 快速判断:没有 v / ü,也没有 ue,就不可能有别写(绝大多数输入走这里)
        if (p.indexOf('v') < 0 && p.indexOf('\u00fc') < 0 && p.indexOf("ue") < 0) return p;
        if (p.indexOf('\u00fc') >= 0) p = p.replace('\u00fc', 'v');
        int[] ends = segment(p);
        if (ends == null) return p;
        StringBuilder sb = null;
        int from = 0;
        for (int end : ends) {
            String piece = p.substring(from, end);
            String fixed = canonicalPiece(piece);
            if (!fixed.equals(piece)) {
                if (sb == null) sb = new StringBuilder(p);
                sb.replace(from, end, fixed);
            }
            from = end;
        }
        return sb == null ? p : sb.toString();
    }

    private static String canonicalPiece(String piece) {
        String a = ALIAS.get(piece);
        if (a != null) return a;
        // 没打完的别写前缀,比如 jva → jua(只有 j q x y 后面的 v 要换)
        if (piece.length() >= 2 && piece.charAt(1) == 'v') {
            char c = piece.charAt(0);
            if (c == 'j' || c == 'q' || c == 'x' || c == 'y') {
                return c + "u" + piece.substring(2);
            }
        }
        return piece;
    }

    /**
     * 对带分词符 ' 的原始输入做 normalize:每一段分别处理,分词符位置不动,总长度不变。
     * 没有要改的就返回原对象。
     */
    public static String normalizeRaw(String raw) {
        if (raw.indexOf('\'') < 0) return normalize(raw);
        StringBuilder sb = null;
        int start = 0;
        int n = raw.length();
        while (start <= n) {
            int q = raw.indexOf('\'', start);
            int end = q < 0 ? n : q;
            String chunk = raw.substring(start, end);
            String fixed = normalize(chunk);
            if (!fixed.equals(chunk)) {
                if (sb == null) sb = new StringBuilder(raw);
                sb.replace(start, end, fixed);
            }
            if (q < 0) break;
            start = q + 1;
        }
        return sb == null ? raw : sb.toString();
    }

    /** 标准音节总数(自测用)。 */
    public static int standardCount() {
        return SYL.size();
    }
}
