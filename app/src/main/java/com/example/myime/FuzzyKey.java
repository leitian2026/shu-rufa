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

    /** 输入和改正后的写法逐字对应的结果:每个字母属于第几处错(从 0 起,从左到右数),没错是 -1。 */
    public static final class Align {
        public final int[] typed;   // 用户打的每个字母
        public final int[] fix;     // 改正后的每个字母
        Align(int[] typed, int[] fix) { this.typed = typed; this.fix = fix; }
    }

    /**
     * 把用户打的 typed 和改正后的 fix 一个字母一个字母对上,标出每一处错在哪(预览里 / 提示里用不同颜色画,看得出谁改成了谁)。
     * 一处错 = 写反(两个字母算一处)、按到别的键(换成另一个字母)、多打(打的有、改正里没有)、漏打(改正里有、打的没有)。
     * 代价和 find 用的一样,所以对出来的就是 find 认为的那种改法。输入很短(最多几十个字母),开销可以忽略。
     */
    public static Align align(String typed, String fix) {
        int n = typed.length();
        int m = fix.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i * COST_INDEL;
        for (int j = 0; j <= m; j++) dp[0][j] = j * COST_INDEL;
        for (int i = 1; i <= n; i++) {
            char t = typed.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char f = fix.charAt(j - 1);
                int v = dp[i - 1][j - 1] + (t == f ? 0 : (Typo.near(t, f) ? COST_NEAR : COST_SUB));
                v = Math.min(v, dp[i - 1][j] + COST_INDEL);
                v = Math.min(v, dp[i][j - 1] + COST_INDEL);
                if (i >= 2 && j >= 2 && t != f && t == fix.charAt(j - 2) && typed.charAt(i - 2) == f) {
                    v = Math.min(v, dp[i - 2][j - 2] + COST_SWAP);
                }
                dp[i][j] = v;
            }
        }
        // 从右往左回溯,先记下每个字母属于哪一步,再从左到右给各处错编号
        int[] tg = new int[n];
        int[] fg = new int[m];
        int[] stepT = new int[n];   // 临时:步号(从右往左数),0 = 没错
        int[] stepF = new int[m];
        int step = 0;
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            char t = i > 0 ? typed.charAt(i - 1) : 0;
            char f = j > 0 ? fix.charAt(j - 1) : 0;
            if (i > 0 && j > 0 && t == f && dp[i][j] == dp[i - 1][j - 1]) {   // 没错
                i--; j--;
            } else if (i >= 2 && j >= 2 && t != f && t == fix.charAt(j - 2) && typed.charAt(i - 2) == f
                    && dp[i][j] == dp[i - 2][j - 2] + COST_SWAP) {              // 写反
                step++;
                stepT[i - 1] = step; stepT[i - 2] = step;
                stepF[j - 1] = step; stepF[j - 2] = step;
                i -= 2; j -= 2;
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + COST_INDEL) {        // 多打
                step++; stepT[i - 1] = step;
                i--;
            } else if (j > 0 && dp[i][j] == dp[i][j - 1] + COST_INDEL) {        // 漏打
                step++; stepF[j - 1] = step;
                j--;
            } else {                                                            // 按成了别的键
                step++; stepT[i - 1] = step; stepF[j - 1] = step;
                i--; j--;
            }
        }
        for (int k = 0; k < n; k++) tg[k] = stepT[k] == 0 ? -1 : step - stepT[k];
        for (int k = 0; k < m; k++) fg[k] = stepF[k] == 0 ? -1 : step - stepF[k];
        return new Align(tg, fg);
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
