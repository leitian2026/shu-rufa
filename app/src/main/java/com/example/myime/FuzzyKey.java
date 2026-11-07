package com.example.myime;

import java.util.function.BooleanSupplier;

/**
 * 「是不是 xxx」提示用的多处拼错容错:在词库的拼音键(已排好序的数组)里找和输入最接近的一个整词。
 * 纯 JDK,可脱离手机测试,见 tools/FuzzyKeySelfTest.java。
 *
 * 不是把各种改法都试一遍(改 5 处的组合多到没法算),而是顺着排好序的键往下走,边走边算编辑距离,
 * 已经差太多的分支整段跳过(键按前缀排,同一前缀的键连在一起,二分就能找到这一段的边界),
 * 所以只碰和输入长得像的那一小部分键。
 *
 * 各种错的代价(越小越像手滑):相邻两个字母写反 2、按到相邻的键 2、多打 / 漏打一个 3、按到别的键 4。
 * 同样差的几个键,词频高的优先。
 */
public final class FuzzyKey {
    private FuzzyKey() {}

    private static final int COST_SWAP = 2;
    private static final int COST_NEAR = 2;
    private static final int COST_INDEL = 3;
    private static final int COST_SUB = 4;
    private static final int MIN_KEY_LEN = 3;      // 太短的键(wo、ni)改什么都对得上,不当改正结果
    private static final int MAX_NODES = 30000;    // 最多走这么多个节点,走不完就放弃(省电,也防止长输入卡住)

    /**
     * @param p      用户打的拼音(小写字母,没有分词符)
     * @param keys   排好序的拼音键(同一个键可以重复出现,对应不同的词)
     * @param ids    keys[j] 对应的词条号
     * @param freqs  词条号 → 词频
     * @param budget 总代价上限(按上面的代价算)
     * @param cancelled 有更新的按键了就返回 true,马上放弃
     * @return 代价在 budget 以内、最接近的那个键;没有返回 null。输入本身就是一个键(代价 0)也返回 null
     */
    public static String find(String p, String[] keys, int[] ids, int[] freqs, int budget, BooleanSupplier cancelled) {
        int n = p.length();
        if (n < MIN_KEY_LEN || budget <= 0 || keys.length == 0) return null;
        Search s = new Search(p, keys, ids, freqs, budget, cancelled);
        s.dfs(0, keys.length, 0);
        return s.aborted ? null : s.bestKey;
    }

    private static final class Search {
        final String p;
        final int n;
        final String[] keys;
        final int[] ids;
        final int[] freqs;
        final int budget;
        final BooleanSupplier cancelled;
        final int maxDepth;
        final int[][] rows;
        final int[] rowMin;
        final char[] chars;
        int bestCost;
        int bestFreq = -1;
        String bestKey;
        int nodes;
        boolean aborted;

        Search(String p, String[] keys, int[] ids, int[] freqs, int budget, BooleanSupplier cancelled) {
            this.p = p;
            this.n = p.length();
            this.keys = keys;
            this.ids = ids;
            this.freqs = freqs;
            this.budget = budget;
            this.cancelled = cancelled;
            this.maxDepth = n + budget / COST_INDEL;
            this.rows = new int[maxDepth + 1][n + 1];
            this.rowMin = new int[maxDepth + 1];
            this.chars = new char[maxDepth + 1];
            this.bestCost = budget + 1;
            for (int j = 0; j <= n; j++) rows[0][j] = j * COST_INDEL;
            rowMin[0] = 0;
        }

        /** keys[lo, hi) 都以已走过的前缀(长度 d)开头;rows[d] 是这个前缀和输入各前缀的距离。 */
        void dfs(int lo, int hi, int d) {
            if (aborted) return;
            if (++nodes > MAX_NODES || ((nodes & 255) == 0 && cancelled.getAsBoolean())) {
                aborted = true;
                return;
            }
            // 正好在这里结束的键排在最前面
            int i = lo;
            while (i < hi && keys[i].length() == d) i++;
            if (i > lo && d >= MIN_KEY_LEN) {
                int cost = rows[d][n];
                if (cost > 0 && cost <= budget && cost <= bestCost) {
                    int f = 0;
                    for (int j = lo; j < i; j++) f = Math.max(f, freqs[ids[j]]);
                    if (cost < bestCost || f > bestFreq) {
                        bestCost = cost;
                        bestFreq = f;
                        bestKey = keys[lo];
                    }
                }
            }
            if (d >= maxDepth) return;
            int[] pr = rows[d];
            int[] r = rows[d + 1];
            while (i < hi) {
                char c = keys[i].charAt(d);
                int e = i + 1;   // 以 c 为下一个字母的这一段 [i, e)
                int h = hi;
                while (e < h) {
                    int m = (e + h) >>> 1;
                    if (keys[m].charAt(d) > c) h = m; else e = m + 1;
                }
                r[0] = pr[0] + COST_INDEL;
                int mn = r[0];
                for (int j = 1; j <= n; j++) {
                    char pj = p.charAt(j - 1);
                    int v = pr[j - 1] + (pj == c ? 0 : (Typo.near(c, pj) ? COST_NEAR : COST_SUB));
                    int a = pr[j] + COST_INDEL;          // 键里多出一个字母(用户漏打)
                    if (a < v) v = a;
                    int b = r[j - 1] + COST_INDEL;       // 输入里多出一个字母(用户多打)
                    if (b < v) v = b;
                    if (d >= 1 && j >= 2 && c != pj && c == p.charAt(j - 2) && chars[d - 1] == pj) {
                        int t = rows[d - 1][j - 2] + COST_SWAP;   // 相邻两个写反
                        if (t < v) v = t;
                    }
                    r[j] = v;
                    if (v < mn) mn = v;
                }
                rowMin[d + 1] = mn;
                chars[d] = c;
                // 写反会用到再前一行,所以最近两行都超了才能放弃这一段
                int limit = Math.min(budget, bestCost);
                if (Math.min(mn, rowMin[d]) <= limit) dfs(i, e, d + 1);
                if (aborted) return;
                i = e;
            }
        }
    }
}
