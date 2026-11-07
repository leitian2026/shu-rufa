package com.example.myime;

import java.util.function.BooleanSupplier;

/**
 * 整串拼音改正(「是不是 xxx」提示用):输入里有好几处打错、词库里又没有对应的整词(一句话)时,
 * 把整串切成一个个音节,每个音节允许有几处错,找总代价最小的改法,结果一定是合法的拼音写法。
 * 纯 JDK,可脱离手机测试,见 tools/SyllableFixSelfTest.java。
 *
 * 各种错的代价和 FuzzyKey 一样:相邻两个字母写反 2、按到相邻的键 2、多打 / 漏打一个 3、按到别的键 4。
 * 另外:输入里第一个切不下去的字母(出错位置)只是症状,真正打错的多半在它前面,
 *   所以「在出错位置那两个字母上」做的写反 / 换键 / 补字母要多加 4,这样 guiquij 先改成 guiqiu(前面 ui 写反、j 多打),
 *   而不是 guiquji(i 和后面的 j 对调);多打一个字母(直接删掉出错的那个)不加。后面再出现的错照常算。
 *
 * 做法:动态规划,dp[i] = 前 i 个字母改成合法音节序列的最小代价,每一段最多 MAX_CHUNK 个字母、对着约 410 个音节算编辑距离,
 * 差得太多的直接跳过;最后一段如果正好是没打完的音节前缀(正在打),不算错。
 */
public final class SyllableFix {
    private SyllableFix() {}

    // 代价是 FuzzyKey 的两倍,这样「写反」(3)比「按到相邻的键」(4)便宜一点,同样要改两个字母时先猜写反
    private static final int C_SWAP = 3;
    private static final int C_NEAR = 4;
    private static final int C_INDEL = 6;
    private static final int C_DUP = 4;        // 多打的字母和前一个一样(连按两下):更像手抖
    private static final int C_SUB = 8;
    private static final int PEN = 8;          // 出错位置上的改动多加的「分」(只影响选哪种改法,不算进代价上限)
    private static final int MAX_CHUNK = 7;    // 一个音节最多 6 个字母,多打一个
    public static final int MAX_LEN = 24;

    /**
     * @param p      用户打的拼音(小写字母,没有分词符)
     * @param budget 总代价上限,单位和 FuzzyKey 一样(多打 / 漏打一个 = 3),只算真正的改动代价,不含 PEN
     * @param cancelled 有更新的按键了就返回 true,马上放弃
     * @return 改正后的整串拼音;输入本身合法、改不了、或代价超过 budget 返回 null
     */
    public static String repair(String p, int budget, BooleanSupplier cancelled) {
        int n = p.length();
        if (n < 3 || n > MAX_LEN || budget <= 0) return null;
        budget *= 2;   // 内部代价是 FuzzyKey 的两倍
        for (int i = 0; i < n; i++) {
            char c = p.charAt(i);
            if (c < 'a' || c > 'z') return null;
        }
        int bad = Syllabary.validPrefixLen(p);
        if (bad >= n) return null;   // 没有明确打错的地方
        String[] syl = Syllabary.syllables();
        final int INF = 1 << 20;
        final int scoreCap = budget + 2 * PEN;
        int[][] dA = new int[MAX_CHUNK + 1][MAX_CHUNK + 1];   // edit 用的临时表,整次共用
        int[][] rA = new int[MAX_CHUNK + 1][MAX_CHUNK + 1];
        // 两层:层 0 = 前 i 个字母已经全部变成完整音节;层 1 = 变成完整音节 + 一个还在打的音节前缀(后面只能再接要删掉的多打字母)
        // [层][i]:分、真正代价、输出字母数、首字母对上的段数、上一步位置、上一步所在层、这一步产出的音节(null = 删掉一个字母)
        int[][] dp = new int[2][n + 1];
        int[][] real = new int[2][n + 1];
        int[][] olen = new int[2][n + 1];
        int[][] fm = new int[2][n + 1];
        int[][] from = new int[2][n + 1];
        int[][] fromLayer = new int[2][n + 1];
        String[][] pick = new String[2][n + 1];
        java.util.Arrays.fill(dp[0], INF);
        java.util.Arrays.fill(dp[1], INF);
        dp[0][0] = 0;
        for (int i = 1; i <= n; i++) {
            if (cancelled.getAsBoolean()) return null;
            for (int j = Math.max(0, i - MAX_CHUNK); j < i; j++) {
                if (dp[0][j] >= INF) continue;
                int rem = scoreCap - dp[0][j];
                if (rem < 0) continue;
                String chunk = p.substring(j, i);
                int m = chunk.length();
                if (Syllabary.isPrefix(chunk)) {   // 末尾还在打的音节前缀,不算错(后面如果还有多打的字母,只能删掉)
                    if (better(dp[0][j], real[0][j], olen[0][j] + m, fm[0][j], dp[1][i], real[1][i], olen[1][i], fm[1][i])) {
                        set(dp, real, olen, fm, from, fromLayer, pick, 1, i, dp[0][j], real[0][j], olen[0][j] + m, fm[0][j], j, 0, chunk);
                    }
                }
                for (String s : syl) {
                    if (Math.abs(s.length() - m) * C_DUP > rem) continue;
                    long e = edit(chunk, s, j, bad, rem, dA, rA);
                    if (e < 0) continue;
                    int sc = dp[0][j] + (int) (e >> 16);
                    int rl = real[0][j] + (int) (e & 0xFFFF);
                    int ol = olen[0][j] + s.length();
                    int f = fm[0][j] + (s.charAt(0) == chunk.charAt(0) ? 1 : 0);
                    if (better(sc, rl, ol, f, dp[0][i], real[0][i], olen[0][i], fm[0][i])) {
                        set(dp, real, olen, fm, from, fromLayer, pick, 0, i, sc, rl, ol, f, j, 0, s);
                    }
                }
            }
            // 多打:这个字母直接删掉(两层都可以)。放在最后、要严格更小才用,所以同样代价时「补一个字母」排在「删一个字母」前面
            int c = (i >= 2 && p.charAt(i - 1) == p.charAt(i - 2)) ? C_DUP : C_INDEL;
            for (int L = 0; L < 2; L++) {
                if (dp[L][i - 1] < INF && dp[L][i - 1] + c < dp[L][i]) {
                    set(dp, real, olen, fm, from, fromLayer, pick, L, i, dp[L][i - 1] + c, real[L][i - 1] + c,
                        olen[L][i - 1], fm[L][i - 1], i - 1, L, null);
                }
            }
        }
        int L = 0;
        if (dp[1][n] < dp[0][n] || (dp[1][n] == dp[0][n] && dp[1][n] < INF && real[1][n] < real[0][n])) L = 1;
        if (dp[L][n] >= INF || real[L][n] > budget || real[L][n] == 0) return null;
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        int i = n;
        while (i > 0) {
            String pk = pick[L][i];
            if (pk != null) parts.add(pk);
            int pi = from[L][i];
            L = fromLayer[L][i];
            i = pi;
        }
        StringBuilder sb = new StringBuilder(n + 4);
        for (int k = parts.size() - 1; k >= 0; k--) sb.append(parts.get(k));
        String r = sb.toString();
        return r.equals(p) || r.isEmpty() ? null : r;
    }

    private static void set(int[][] dp, int[][] real, int[][] olen, int[][] fm, int[][] from, int[][] fromLayer, String[][] pick,
                            int L, int i, int sc, int rl, int ol, int f, int fi, int fl, String pk) {
        dp[L][i] = sc;
        real[L][i] = rl;
        olen[L][i] = ol;
        fm[L][i] = f;
        from[L][i] = fi;
        fromLayer[L][i] = fl;
        pick[L][i] = pk;
    }

    /** 分小的好;分一样代价小的好;再一样输出的字母多的好;再一样首字母对得上的多的好。 */
    private static boolean better(int sc, int rl, int ol, int f, int bsc, int brl, int bol, int bf) {
        if (sc != bsc) return sc < bsc;
        if (rl != brl) return rl < brl;
        if (ol != bol) return ol > bol;
        return f > bf;   // 再一样:音节首字母和打的首字母对得上的多的好(nhao → ni hao,不是 an hao)
    }

    /**
     * 打的这段 chunk(在整串里从 off 开始)改成音节 s:返回 (分 << 16 | 真正代价);分超过 limit 返回 -1。
     * 分 = 代价 + 出错位置上改动的 PEN。
     */
    private static long edit(String chunk, String s, int off, int bad, int limit, int[][] d, int[][] r) {
        int m = chunk.length();
        int l = s.length();
        for (int a = 0; a <= m; a++) {
            for (int b = 0; b <= l; b++) {
                if (a == 0 && b == 0) continue;
                int bs = Integer.MAX_VALUE / 2;
                int br = 0;
                if (a > 0 && b > 0) {
                    char t = chunk.charAt(a - 1);
                    char f = s.charAt(b - 1);
                    int cost = t == f ? 0 : (Typo.near(t, f) ? C_NEAR : C_SUB);
                    int sc = d[a - 1][b - 1] + cost + (cost > 0 ? pen(off + a - 1, bad) : 0);
                    int rl = r[a - 1][b - 1] + cost;
                    if (sc < bs || (sc == bs && rl < br)) { bs = sc; br = rl; }
                    if (a > 1 && b > 1 && t != f && t == s.charAt(b - 2) && chunk.charAt(a - 2) == f) {
                        sc = d[a - 2][b - 2] + C_SWAP + pen(off + a - 2, bad);
                        rl = r[a - 2][b - 2] + C_SWAP;
                        if (sc < bs || (sc == bs && rl < br)) { bs = sc; br = rl; }
                    }
                }
                if (a > 0) {   // 多打
                    int c = (a > 1 && chunk.charAt(a - 1) == chunk.charAt(a - 2)) ? C_DUP : C_INDEL;
                    int sc = d[a - 1][b] + c;
                    int rl = r[a - 1][b] + c;
                    if (sc < bs || (sc == bs && rl < br)) { bs = sc; br = rl; }
                }
                if (b > 0) {   // 漏打:补在打的第 a 个字母前面,出错位置之后(再往后)才算「改在症状后面」
                    int sc = d[a][b - 1] + C_INDEL + (off + a > bad ? pen(off + a, bad + 1) : 0);
                    int rl = r[a][b - 1] + C_INDEL;
                    if (sc < bs || (sc == bs && rl < br)) { bs = sc; br = rl; }
                }
                d[a][b] = bs;
                r[a][b] = br;
            }
        }
        if (d[m][l] > limit) return -1;
        return ((long) d[m][l] << 16) | r[m][l];
    }

    private static int pen(int idx, int bad) {
        return idx >= bad && idx < bad + 2 ? PEN : 0;
    }
}
