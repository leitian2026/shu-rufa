package com.example.myime;

import java.util.ArrayList;
import java.util.Map;

/**
 * 整句输入:把一长串拼音切成词库里的词,挑总分最高的一种组合(维特比,纯 JDK,可脱离手机测试,
 * 见 tools/SentenceSelfTest.java)。
 *
 * 做法:
 *   1. 用 Syllabary 算出哪些位置是音节边界(从头能切成完整音节的位置)。
 *   2. 词库按拼音连写(compact)排好序。从每个边界出发,往后一个字母一个字母地延长,
 *      每次二分找"连写正好等于这一段"的词;一旦词库里没有任何词以这一段开头就停,不再往后试。
 *   3. 每条边的分 = ln(词频 / 总词频) + 每多一个音节加一点(词库里的词比拆成单字更可信)
 *      + 我打过这个词(加)+ 第一个词是我常接在上一个词后面的(加)。
 *      整句分 = 各词分之和,所以词越少、越常用,总分越高。
 *   4. 输入末尾允许留一个没打完的音节(如 sh),句子只吃到它前面。
 *
 * 混拼(decodeMixed):输入里有没法当成完整音节的字母(如 zgren、wmmtquks)时,每个音节既可以写全,
 *   也可以只写声母(zh/ch/sh 可写 z/c/s 或 zh/ch/sh),最后一个音节可以只打了前半。
 *   从每个能走到的位置往后找"音节记号"(完整音节或声母),用声母串在词库里二分,再逐个对一遍,
 *   不用把词库展开成别的形式。只有一个音节的词必须写全(单独一个声母太含糊),至少有一个音节是简写才算混拼。
 *
 * 只在调用方认为需要时才跑(长度够、没有整串精确匹配的词),每次一遍 O(字母数 × 词长) 次二分,
 * 没有任何缓存和后台线程。
 */
public final class SentenceDecoder {
    public static final class Result {
        public final String text;       // 整句
        public final int letters;       // 吃掉了输入串(不含分词符)的前几个字母
        public final String[] words;    // 每个词
        public final String[] codes;    // 每个词对应的拼音连写
        public final double score;
        public final String[] alternatives;   // 混拼:整串也能对上的其他词(按分数从高到低,不含 text)

        Result(String text, int letters, String[] words, String[] codes, double score, String[] alternatives) {
            this.text = text;
            this.letters = letters;
            this.words = words;
            this.codes = codes;
            this.score = score;
            this.alternatives = alternatives;
        }
    }

    private static final int MIN_LETTERS = 4;
    private static final int MAX_LETTERS = 48;        // 再长不处理,免得白费 CPU
    private static final int MAX_WORD_LETTERS = 24;   // 单个词的拼音连写最长
    private static final double SYL_BONUS = 1.0;      // 词里每多一个音节加的分
    private static final double USER_BONUS = 3.0;     // 我打过的词
    private static final double CTX_BONUS = 2.0;      // 我常接在上一个词后面的词(只给第一个词)
    private static final double CHAIN_BONUS = 1.5;    // 整句里,后一个词是我常接在前一个词后面的
    private static final double GRAM_WEIGHT = 0.4;    // 搭配模型(GramPack)给相邻两个词的分,乘上这个权重再加进整句分
    private static final int GRAM_CAP = 12;           // 有搭配模型时,每个位置保留几条路径(上一个词不同,搭配分就不同)
    private static final int GRAM_PER = 4;            // 有搭配模型时,每条边保留同音的几个词(的/得/地 都要留着比)
    private static final double NEG = -1e18;
    private static final String[] NO_ALTS = new String[0];

    private static final int MIXED_MIN = 3;
    private static final int MIXED_MAX = 24;          // 混拼最长处理的字母数
    private static final int MAX_TOKENS = 8;          // 一个词最多几个音节记号
    private static final int MAX_ALTS = 7;
    private static final double MIX_PENALTY = 0.7;    // 每个简写的音节扣的分(写全的优先)

    private final String[] keys;     // 已排序的拼音连写
    private final int[] ids;         // keys[k] 对应的词条号
    private final String[] texts;
    private final int[] freqs;
    private final byte[] sylLens;
    private final int[] sylStart;
    private final double logTotal;
    private final String[] compact;       // 每个词条的拼音连写(按词条号)
    private final String[] initialKeys;   // 已排序的首字母串
    private final int[] initialIds;
    private volatile GramPack gram;       // 搭配模型(可没有),词库管理里装了才有;键盘服务在后台线程里换上

    public SentenceDecoder(String[] keys, int[] ids, String[] texts, int[] freqs, byte[] sylLens, int[] sylStart,
                           String[] compact, String[] initialKeys, int[] initialIds) {
        this.keys = keys;
        this.ids = ids;
        this.texts = texts;
        this.freqs = freqs;
        this.sylLens = sylLens;
        this.sylStart = sylStart;
        this.compact = compact;
        this.initialKeys = initialKeys;
        this.initialIds = initialIds;
        long total = 0L;
        for (int f : freqs) total += Math.max(f, 1);
        this.logTotal = Math.log(Math.max(total, 1L));
    }

    /** 装上 / 换掉 / 去掉(null)搭配模型。之后的整句解码会用它给相邻两个词的搭配加分。 */
    public void setGram(GramPack g) {
        this.gram = g;
    }

    private static int lowerBound(String[] a, String target) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid].compareTo(target) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** 词条 id 在偏移 off 处是不是正好一个音节的结束。 */
    private boolean boundaryAt(int id, int off) {
        int count = 0;
        for (int k = sylStart[id]; k < sylStart[id + 1]; k++) {
            count += sylLens[k];
            if (count == off) return true;
            if (count > off) return false;
        }
        return false;
    }

    /** 边 [i, j) 里面有用户用 ' 指定的边界,词必须在这些位置有音节分界。 */
    private boolean cutsOk(int id, int[] cuts, int i, int j) {
        if (cuts == null) return true;
        for (int c : cuts) {
            if (c > i && c < j && !boundaryAt(id, c - i)) return false;
        }
        return true;
    }

    private double wordScore(int id, UserFreq user, Map<String, Long> ctx, boolean first) {
        double s = Math.log(Math.max(freqs[id], 1)) - logTotal + SYL_BONUS * (sylStart[id + 1] - sylStart[id] - 1);
        if (user != null && UserFreq.textRank(user, texts[id]) != 0L) s += USER_BONUS;
        if (first && ctx != null && ctx.containsKey(texts[id])) s += CTX_BONUS;
        return s;
    }

    private static final java.util.List<Result> EMPTY = java.util.Collections.emptyList();

    /** 最好的一句(至少两个词);没有返回 null。 */
    public Result decode(String p, int[] cuts, UserFreq user, Map<String, Long> ctx) {
        java.util.List<Result> l = decodeTop(p, cuts, user, ctx, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * 最好的几句(文字各不相同,分数从高到低,每句至少两个词)。k 大于 1 时每条边保留同音的前两个词,
     * 每个位置保留前 k+3 条路径,所以 xian 这种可以切成 xian / xi an 的串能给出不同的整句(先在、西安在)。
     *
     * @param p    拼音(只含 a-z,小写,没有分词符)
     * @param cuts 用户用 ' 标的边界(p 里的字符偏移,升序),没有就 null 或空
     * @param user 我的选词记录(没学过任何词就传 null)
     * @param ctx  我常接在上一个词后面的词(没有就 null)
     */
    public java.util.List<Result> decodeTop(String p, int[] cuts, UserFreq user, Map<String, Long> ctx, int k) {
        return decodeTop(p, cuts, user, ctx, k, null);
    }

    /** 同上,另外把我自己造的词(UserWords.Snapshot,可为 null)也当作词库里的词一起拼。 */
    public java.util.List<Result> decodeTop(String p, int[] cuts, UserFreq user, Map<String, Long> ctx, int k,
                                            UserWords.Snapshot sn) {
        return decodeTop(p, cuts, user, ctx, k, sn, null);
    }

    /**
     * 同上,另外用我的「词接词」记录(UserChain,可为 null):整句里相邻的两个词,后一个是我常接在前一个后面的,加一点分。
     * 句子第一个词的上文用 ctx(上一次上屏的词)。
     */
    public java.util.List<Result> decodeTop(String p, int[] cuts, UserFreq user, Map<String, Long> ctx, int k,
                                            UserWords.Snapshot sn, UserChain chain) {
        int n = p.length();
        if (n < MIN_LETTERS || n > MAX_LETTERS) return EMPTY;
        if (cuts != null && cuts.length == 0) cuts = null;

        // pre[i]:p 的前 i 个字母能切成完整音节
        boolean[] pre = new boolean[n + 1];
        pre[0] = true;
        for (int j = 1; j <= n; j++) {
            for (int len = 1; len <= Syllabary.MAX_LEN && len <= j; len++) {
                if (pre[j - len] && Syllabary.isSyllable(p.substring(j - len, j))) {
                    pre[j] = true;
                    break;
                }
            }
        }
        // 终点:吃到结尾,或者吃到某个边界、剩下的是没打完的音节
        int end = -1;
        for (int j = n; j >= 1; j--) {
            if (pre[j] && (j == n || Syllabary.isPrefix(p.substring(j)))) {
                end = j;
                break;
            }
        }
        if (end < 0) return EMPTY;
        return core(p, cuts, user, ctx, k, keys, ids, pre, end, false, null, p, sn, chain);
    }

    /**
     * 模糊音下的整句:输入已经按 Fuzzy.canon 折叠,在规范形索引(fKeys/fIds)里找词。
     * 句子必须吃到结尾(最后一个音节没打完时不算),词的边界就是输入的音节边界。
     * Result 里的 letters / codes 仍然是原输入的位置和写法。
     */
    public java.util.List<Result> decodeFuzzyTop(String orig, Fuzzy.Canon cn, String[] fKeys, int[] fIds,
                                                 UserFreq user, Map<String, Long> ctx, int k) {
        if (cn == null || !cn.tailComplete) return EMPTY;
        int on = orig.length();
        if (on < MIN_LETTERS || on > MAX_LETTERS) return EMPTY;
        String q = cn.canon;
        int n = q.length();
        boolean[] pre = new boolean[n + 1];
        int[] posMap = new int[n + 1];
        java.util.Arrays.fill(posMap, -1);
        pre[0] = true;
        posMap[0] = 0;
        for (int m = 0; m < cn.canonEnds.length; m++) {
            pre[cn.canonEnds[m]] = true;
            posMap[cn.canonEnds[m]] = cn.origEnds[m];
        }
        return core(q, null, user, ctx, k, fKeys, fIds, pre, n, true, posMap, orig, null, null);
    }

    /** 把 (id, 分) 放进这条边的前 per 名里,返回现在有几个。 */
    private static int offer(int[] eid, double[] esc, int ec, int per, int id, double s) {
        if (ec == per && s <= esc[per - 1]) return ec;
        int pos = ec < per ? ec : per - 1;
        while (pos > 0 && esc[pos - 1] < s) {
            esc[pos] = esc[pos - 1];
            eid[pos] = eid[pos - 1];
            pos--;
        }
        esc[pos] = s;
        eid[pos] = id;
        return ec < per ? ec + 1 : ec;
    }

    /** 我自己造的词的分:次数越多越高,比词库里同样长的词高很多(这是我自己拼出来的)。 */
    private double userWordScore(UserWords.Snapshot sn, int x) {
        return Math.log(2000.0 * Math.min(sn.counts[x], 50)) - logTotal + SYL_BONUS * (sn.lens[x].length - 1) + USER_BONUS;
    }

    /** 同 cutsOk,词的音节长度直接给出(我自己造的词)。 */
    private static boolean lensCutsOk(int[] lens, int[] cuts, int i, int j) {
        if (cuts == null) return true;
        for (int c : cuts) {
            if (c <= i || c >= j) continue;
            int off = c - i;
            int sum = 0;
            boolean hit = false;
            for (int l : lens) {
                sum += l;
                if (sum == off) { hit = true; break; }
                if (sum > off) break;
            }
            if (!hit) return false;
        }
        return true;
    }

    private static void ins(int[] cnt, double[][] sc, int[][] fr, int[][] fk, int[][] wd, int cap,
                            int j, double s, int i, int r, int id) {
        int c = cnt[j];
        if (c == cap && s <= sc[j][cap - 1]) return;
        int pos = c < cap ? c : cap - 1;
        while (pos > 0 && sc[j][pos - 1] < s) {
            sc[j][pos] = sc[j][pos - 1];
            fr[j][pos] = fr[j][pos - 1];
            fk[j][pos] = fk[j][pos - 1];
            wd[j][pos] = wd[j][pos - 1];
            pos--;
        }
        sc[j][pos] = s;
        fr[j][pos] = i;
        fk[j][pos] = r;
        wd[j][pos] = id;
        if (c < cap) cnt[j] = c + 1;
    }

    /**
     * 前 k 好路径的维特比。q 是查词用的字符串(整句时就是输入,模糊音时是折叠后的),
     * pre[i] 表示 i 是音节边界,posMap(只有模糊音用)把 q 的位置换回原输入的位置。
     */
    private java.util.List<Result> core(String q, int[] cuts, UserFreq user, Map<String, Long> ctx, int k,
                                        String[] kArr, int[] iArr, boolean[] pre, int end, boolean fullOnly,
                                        int[] posMap, String orig, UserWords.Snapshot sn, UserChain chain) {
        int n = q.length();
        final int base = texts.length;   // 编号 >= base 的是我自己造的词(sn 里的第 id-base 个)
        int want = Math.max(k, 1);
        int cap = want == 1 ? 1 : want + 3;      // 每个位置保留几条路径
        int per = want == 1 ? 1 : 2;             // 每条边保留同音的几个词
        final GramPack gp = gram;
        if (gp != null) {
            // 搭配分取决于上一个词,所以每个位置要多留几条「上一个词不同」的路径,每条边也要多留几个同音词,才有得比
            cap = Math.max(cap, GRAM_CAP);
            per = Math.max(per, GRAM_PER);
        }
        int[] cnt = new int[n + 1];
        double[][] sc = new double[n + 1][cap];
        int[][] fr = new int[n + 1][cap];
        int[][] fk = new int[n + 1][cap];
        int[][] wd = new int[n + 1][cap];
        cnt[0] = 1;
        int[] eid = new int[per];
        double[] esc = new double[per];
        java.util.HashMap<String, Map<String, Long>> rows = chain == null ? null : new java.util.HashMap<>();

        for (int i = 0; i < end; i++) {
            if (!pre[i] || cnt[i] == 0) continue;
            int maxJ = Math.min(end, i + MAX_WORD_LETTERS);
            for (int j = i + 1; j <= maxJ; j++) {
                String sub = q.substring(i, j);
                int lo = lowerBound(kArr, sub);
                boolean dictHas = lo < kArr.length && kArr[lo].startsWith(sub);
                int ulo = 0;
                boolean userHas = false;
                if (sn != null) {
                    ulo = lowerBound(sn.keys, sub);
                    userHas = ulo < sn.keys.length && sn.keys[ulo].startsWith(sub);
                }
                // 词库和我造的词里都没有任何词以这一段开头:再往后延长也不会有
                if (!dictHas && !userHas) break;
                if (!pre[j]) continue;
                int ec = 0;
                if (dictHas) {
                    int hi = lowerBound(kArr, sub + '\u0000');
                    for (int x = lo; x < hi; x++) {
                        int id = iArr[x];
                        if (!cutsOk(id, cuts, i, j)) continue;
                        ec = offer(eid, esc, ec, per, id, wordScore(id, user, ctx, i == 0));
                    }
                }
                if (userHas) {
                    int uhi = lowerBound(sn.keys, sub + '\u0000');
                    for (int x = ulo; x < uhi; x++) {
                        if (!lensCutsOk(sn.lens[x], cuts, i, j)) continue;
                        ec = offer(eid, esc, ec, per, base + x, userWordScore(sn, x));
                    }
                }
                if (ec == 0) continue;
                for (int r = 0; r < cnt[i]; r++) {
                    // 这条路径上一个词的「后面我常接什么」(只在有记录时才有;每个上一个词只查一次)
                    Map<String, Long> row = null;
                    String prevText = null;
                    if ((chain != null || gp != null) && i > 0) {
                        prevText = wd[i][r] < base ? texts[wd[i][r]] : sn.texts[wd[i][r] - base];
                        if (chain != null) {
                            row = rows.get(prevText);
                            if (row == null && !rows.containsKey(prevText)) {
                                row = chain.ctxRow(null, prevText);
                                rows.put(prevText, row);
                            }
                        }
                    }
                    for (int m = 0; m < ec; m++) {
                        double add = esc[m];
                        String t = null;
                        if (row != null) {
                            t = eid[m] < base ? texts[eid[m]] : sn.texts[eid[m] - base];
                            if (row.containsKey(t)) add += CHAIN_BONUS;
                        }
                        if (gp != null && prevText != null) {
                            if (t == null) t = eid[m] < base ? texts[eid[m]] : sn.texts[eid[m] - base];
                            add += GRAM_WEIGHT * gp.pairScore(prevText, t);
                        }
                        ins(cnt, sc, fr, fk, wd, cap, j, sc[i][r] + add, i, r, eid[m]);
                    }
                }
            }
        }
        // 吃到结尾走不通(中间有一段词库里没有)时,退到最靠后的、走得通的终点:
        // 吃到结尾,或者(整句)吃到某个边界且剩下的是没打完的音节
        int stop = -1;
        for (int j = end; j >= 1; j--) {
            if (cnt[j] == 0) continue;
            if (j == n || (!fullOnly && pre[j] && Syllabary.isPrefix(q.substring(j)))) {
                stop = j;
                break;
            }
        }
        if (stop < 0) return EMPTY;

        java.util.ArrayList<Result> out = new java.util.ArrayList<>();
        java.util.HashSet<String> seenText = new java.util.HashSet<>();
        for (int r = 0; r < cnt[stop] && out.size() < want; r++) {
            int words = 0;
            for (int j = stop, rr = r; j > 0; ) {
                words++;
                int nj = fr[j][rr];
                rr = fk[j][rr];
                j = nj;
            }
            if (words < 2) continue;
            String[] ws = new String[words];
            String[] cs = new String[words];
            int w = words - 1;
            for (int j = stop, rr = r; j > 0; ) {
                int nj = fr[j][rr];
                ws[w] = wd[j][rr] < base ? texts[wd[j][rr]] : sn.texts[wd[j][rr] - base];
                int a = posMap == null ? nj : posMap[nj];
                int b = posMap == null ? j : posMap[j];
                cs[w] = orig.substring(a, b);
                rr = fk[j][rr];
                j = nj;
                w--;
            }
            StringBuilder sb = new StringBuilder();
            for (String x : ws) sb.append(x);
            String text = sb.toString();
            if (!seenText.add(text)) continue;
            out.add(new Result(text, posMap == null ? stop : posMap[stop], ws, cs, sc[stop][r], NO_ALTS));
        }
        return out;
    }

    // ------------------------------------------------------------ 混拼

    /** decodeMixed 一次调用里用到的状态(都是小数组)。 */
    private final class Mixed {
        final String p;
        final int n;
        final int[] cuts;
        final UserFreq user;
        final Map<String, Long> ctx;
        final double[] best;
        final int[] from;
        final int[] wid;
        final char[] ini = new char[MAX_TOKENS];
        final int[] tEnd = new int[MAX_TOKENS];
        final int[] altId = new int[MAX_ALTS + 1];
        final double[] altScore = new double[MAX_ALTS + 1];
        int altCount = 0;
        int start;

        Mixed(String p, int[] cuts, UserFreq user, Map<String, Long> ctx) {
            this.p = p;
            this.n = p.length();
            this.cuts = cuts;
            this.user = user;
            this.ctx = ctx;
            best = new double[n + 1];
            from = new int[n + 1];
            wid = new int[n + 1];
            java.util.Arrays.fill(best, NEG);
            best[0] = 0.0;
        }

        boolean crosses(int a, int b) {
            if (cuts == null) return false;
            for (int c : cuts) if (c > a && c < b) return true;
            return false;
        }

        void relax(int i, int j, int id, double s) {
            double total = best[i] + s;
            if (total > best[j]) {
                best[j] = total;
                from[j] = i;
                wid[j] = id;
            }
        }

        void offerWhole(int id, double s) {
            // 整串对上的词:只留分最高的几个
            int pos = altCount < MAX_ALTS ? altCount : MAX_ALTS - 1;
            if (altCount == MAX_ALTS && s <= altScore[MAX_ALTS - 1]) return;
            for (int k = 0; k < altCount; k++) if (altId[k] == id) return;
            while (pos > 0 && altScore[pos - 1] < s) {
                altScore[pos] = altScore[pos - 1];
                altId[pos] = altId[pos - 1];
                pos--;
            }
            altScore[pos] = s;
            altId[pos] = id;
            if (altCount < MAX_ALTS) altCount++;
        }
    }

    /**
     * 混拼:每个音节写全或只写声母,最后一个音节可以没打完。整串必须全部吃完才有结果,否则返回 null。
     * 结果里 words 可以只有一个词(比如 zgren → 中国人);alternatives 是整串也能对上的其他词。
     */
    public Result decodeMixed(String p, int[] cuts, UserFreq user, Map<String, Long> ctx) {
        int n = p.length();
        if (n < MIXED_MIN || n > MIXED_MAX) return null;
        if (cuts != null && cuts.length == 0) cuts = null;
        for (int k = 0; k < n; k++) {
            char c = p.charAt(k);
            if (c < 'a' || c > 'z') return null;
        }
        Mixed st = new Mixed(p, cuts, user, ctx);
        for (int i = 0; i < n; i++) {
            if (st.best[i] <= NEG) continue;
            // 写全的词:和整句一样按拼音连写二分
            int maxJ = Math.min(n, i + MAX_WORD_LETTERS);
            for (int j = i + 1; j <= maxJ; j++) {
                String sub = p.substring(i, j);
                int lo = lowerBound(keys, sub);
                if (lo >= keys.length || !keys[lo].startsWith(sub)) break;
                int hi = lowerBound(keys, sub + '\u0000');
                if (hi <= lo) continue;
                int bestId = -1;
                double bestS = NEG;
                for (int k = lo; k < hi; k++) {
                    int id = ids[k];
                    if (!cutsOk(id, cuts, i, j)) continue;
                    double s = wordScore(id, user, ctx, i == 0);
                    if (s > bestS) {
                        bestS = s;
                        bestId = id;
                    }
                }
                if (bestId >= 0) {
                    st.relax(i, j, bestId, bestS);
                    if (i == 0 && j == n) st.offerWhole(bestId, bestS);
                }
            }
            // 有简写的词:音节记号(完整音节 / 声母)一个个往后走,用首字母串剪枝
            st.start = i;
            mixedDfs(st, i, 0);
        }
        if (st.best[n] <= NEG) return null;

        int cnt = 0;
        for (int j = n; j > 0; j = st.from[j]) cnt++;
        String[] words = new String[cnt];
        String[] codes = new String[cnt];
        int k = cnt - 1;
        StringBuilder sb = new StringBuilder();
        for (int j = n; j > 0; j = st.from[j]) {
            words[k] = texts[st.wid[j]];
            codes[k] = p.substring(st.from[j], j);
            k--;
        }
        for (String w : words) sb.append(w);
        String text = sb.toString();
        ArrayList<String> alts = new ArrayList<>();
        for (int a = 0; a < st.altCount; a++) {
            String t = texts[st.altId[a]];
            if (!t.equals(text) && !alts.contains(t)) alts.add(t);
        }
        return new Result(text, n, words, codes, st.best[n], alts.isEmpty() ? NO_ALTS : alts.toArray(new String[0]));
    }

    private static boolean initialLetter(char c) {
        return c >= 'a' && c <= 'z' && c != 'i' && c != 'u' && c != 'v';
    }

    private void mixedDfs(Mixed st, int pos, int depth) {
        String p = st.p;
        int n = st.n;
        if (depth >= 2) mixedEval(st, pos, depth);
        if (pos >= n || depth >= MAX_TOKENS) return;
        char first = p.charAt(pos);
        int maxLen = Math.min(Syllabary.MAX_LEN, n - pos);
        for (int len = 1; len <= maxLen; len++) {
            int e = pos + len;
            boolean ok;
            if (len == 1) {
                ok = initialLetter(first) || Syllabary.isSyllable(p.substring(pos, e));
            } else if (len == 2 && p.charAt(pos + 1) == 'h' && (first == 'z' || first == 'c' || first == 's')) {
                ok = true;   // zh ch sh 当声母
            } else {
                String sub = p.substring(pos, e);
                ok = Syllabary.isSyllable(sub) || (e == n && Syllabary.isPrefix(sub));   // 完整音节,或结尾没打完的音节
            }
            if (!ok || st.crosses(pos, e)) continue;
            st.ini[depth] = first;
            st.tEnd[depth] = e;
            String ini = new String(st.ini, 0, depth + 1);
            int lo = lowerBound(initialKeys, ini);
            if (lo >= initialKeys.length || !initialKeys[lo].startsWith(ini)) continue;   // 没有词以这个首字母串开头
            mixedDfs(st, e, depth + 1);
        }
    }

    /** 当前走到 pos,已有 depth 个记号:首字母串正好相同的词逐个对一遍。 */
    private void mixedEval(Mixed st, int pos, int depth) {
        String p = st.p;
        int n = st.n;
        String ini = new String(st.ini, 0, depth);
        int lo = lowerBound(initialKeys, ini);
        int hi = lowerBound(initialKeys, ini + '\u0000');
        int start = st.start;
        for (int q = lo; q < hi; q++) {
            int id = initialIds[q];
            String c = compact[id];
            int base = sylStart[id];
            int cp = 0;
            int mixedCnt = 0;
            boolean ok = true;
            for (int m = 0; m < depth; m++) {
                int sl = sylLens[base + m];
                int a = m == 0 ? start : st.tEnd[m - 1];
                int b = st.tEnd[m];
                int pl = b - a;
                if (pl == sl && p.regionMatches(a, c, cp, sl)) {
                    // 写全
                } else if (pl == 1) {
                    if (c.charAt(cp) != p.charAt(a)) { ok = false; break; }
                    mixedCnt++;
                } else if (pl == 2 && p.charAt(a + 1) == 'h' && p.regionMatches(a, c, cp, 2)) {
                    mixedCnt++;
                } else if (b == n && pl < sl && p.regionMatches(a, c, cp, pl)) {
                    mixedCnt++;   // 结尾没打完的音节
                } else {
                    ok = false;
                    break;
                }
                cp += sl;
            }
            if (!ok || mixedCnt == 0) continue;
            // 分词符不用再查:音节记号本身不会跨过分词符(见 Mixed.crosses)
            double s = wordScore(id, st.user, st.ctx, start == 0) - MIX_PENALTY * mixedCnt;
            st.relax(start, pos, id, s);
            if (start == 0 && pos == n) st.offerWhole(id, s);
        }
    }

    /** 方便测试:把结果拼成 "词 词 词" 的形式。 */
    public static String show(Result r) {
        if (r == null) return "null";
        ArrayList<String> l = new ArrayList<>();
        for (String w : r.words) l.add(w);
        return String.join(" ", l) + " /" + r.letters;
    }
}
