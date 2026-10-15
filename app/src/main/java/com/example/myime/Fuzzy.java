package com.example.myime;

import java.util.Arrays;

/**
 * 模糊音(纯 JDK,可脱离手机测试,见 tools/FuzzySelfTest.java)。
 *
 * 思路:不把输入展开成一堆写法去查,而是把"容易混的音"在词库一侧和输入一侧都折成同一种写法(规范形),
 * 比如打开 z/zh 以后,zhi 和 zi 的规范形都是 zi;打开 an/ang 以后 xiang 和 xian 都是 xian。
 * 查询时只要把输入折一次,到"规范形索引"里二分一次,就同时找到所有说法。
 *
 * 规则(都是单向折叠,每个音节独立):
 *   声母:zh→z,ch→c,sh→s;n→l;h→f
 *   韵母:ang→an,eng→en,ing→in(音节结尾的 ng 去掉 g)
 * 输入的音节边界用 Syllabary.segment 切(音节数最少);词库一侧用词条自己的音节长度,不需要切分。
 *
 * 规范形索引只在打开了模糊音时才在后台建一次:对没有受影响的词直接共用原来的字符串,
 * 只给受影响的词分配新串;关掉模糊音或词库释放时一起释放。
 */
public final class Fuzzy {
    private Fuzzy() {}

    public static final int ZH = 1;    // z / zh
    public static final int CH = 2;    // c / ch
    public static final int SH = 4;    // s / sh
    public static final int ANG = 8;   // an / ang
    public static final int ENG = 16;  // en / eng
    public static final int ING = 32;  // in / ing
    public static final int LN = 64;   // l / n
    public static final int FH = 128;  // f / h

    /** 一个音节的规范形;没有要折的就返回原对象。 */
    public static String canonSyllable(String s, int mask) {
        int len = s.length();
        if (len == 0 || mask == 0) return s;
        String t = s;
        char c0 = t.charAt(0);
        if (len >= 2 && t.charAt(1) == 'h') {
            if (c0 == 'z' && (mask & ZH) != 0) t = "z" + t.substring(2);
            else if (c0 == 'c' && (mask & CH) != 0) t = "c" + t.substring(2);
            else if (c0 == 's' && (mask & SH) != 0) t = "s" + t.substring(2);
        }
        if (c0 == 'n' && (mask & LN) != 0) t = "l" + t.substring(1);
        else if (c0 == 'h' && (mask & FH) != 0) t = "f" + t.substring(1);
        int tl = t.length();
        if (tl >= 3 && t.charAt(tl - 1) == 'g' && t.charAt(tl - 2) == 'n') {
            char v = t.charAt(tl - 3);
            if ((v == 'a' && (mask & ANG) != 0) || (v == 'e' && (mask & ENG) != 0) || (v == 'i' && (mask & ING) != 0)) {
                t = t.substring(0, tl - 1);
            }
        }
        return t;
    }

    /** compact 里从 pos 开始长 len 的这个音节,在 mask 下会不会被折(不分配对象)。 */
    private static boolean affects(String c, int pos, int len, int mask) {
        char c0 = c.charAt(pos);
        if (len >= 2 && c.charAt(pos + 1) == 'h') {
            if ((c0 == 'z' && (mask & ZH) != 0) || (c0 == 'c' && (mask & CH) != 0) || (c0 == 's' && (mask & SH) != 0)) return true;
        }
        if ((c0 == 'n' && (mask & LN) != 0) || (c0 == 'h' && (mask & FH) != 0)) return true;
        if (len >= 3 && c.charAt(pos + len - 1) == 'g' && c.charAt(pos + len - 2) == 'n') {
            char v = c.charAt(pos + len - 3);
            return (v == 'a' && (mask & ANG) != 0) || (v == 'e' && (mask & ENG) != 0) || (v == 'i' && (mask & ING) != 0);
        }
        return false;
    }

    /** 输入折叠后的结果。 */
    public static final class Canon {
        public final String canon;        // 折叠后的字符串
        public final int[] canonEnds;     // 每个音节在 canon 里的结束位置
        public final int[] origEnds;      // 每个音节在原输入里的结束位置
        public final boolean tailComplete;   // 最后一段是不是完整音节(没打完的尾巴整句不用)

        Canon(String canon, int[] canonEnds, int[] origEnds, boolean tailComplete) {
            this.canon = canon;
            this.canonEnds = canonEnds;
            this.origEnds = origEnds;
            this.tailComplete = tailComplete;
        }
    }

    /** 把输入(只含 a-z,没有分词符)折叠。切不成音节、或没有开任何规则返回 null。 */
    public static Canon canon(String p, int mask) {
        if (mask == 0) return null;
        return canon(p, mask, Syllabary.segment(p));
    }

    /** 同上,音节切分结果已经算好(ends = Syllabary.segment(p),可为 null)时直接用,省得再切一遍。 */
    public static Canon canon(String p, int mask, int[] ends) {
        if (mask == 0 || ends == null) return null;
        StringBuilder sb = new StringBuilder(p.length());
        int[] cEnds = new int[ends.length];
        int from = 0;
        boolean complete = true;
        for (int k = 0; k < ends.length; k++) {
            String piece = p.substring(from, ends[k]);
            if (k == ends.length - 1 && !Syllabary.isSyllable(piece)) complete = false;
            sb.append(canonSyllable(piece, mask));
            cEnds[k] = sb.length();
            from = ends[k];
        }
        return new Canon(sb.toString(), cEnds, ends, complete);
    }

    /** 规范形索引:每个词条折叠后的拼音连写,按它排好序。 */
    public static final class Index {
        public final int mask;
        public final String[] keys;   // 已排序
        public final int[] ids;       // keys[k] 对应的词条号

        Index(int mask, String[] keys, int[] ids) {
            this.mask = mask;
            this.keys = keys;
            this.ids = ids;
        }

        /** 在后台线程建。compact:每个词条的拼音连写;sylLens/sylStart:各音节长度(见 PinyinEngine)。 */
        public static Index build(String[] compact, byte[] sylLens, int[] sylStart, int mask) {
            int n = compact.length;
            String[] folded = new String[n];
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < n; i++) {
                String c = compact[i];
                int a = sylStart[i];
                int b = sylStart[i + 1];
                boolean changed = false;
                int pos = 0;
                for (int k = a; k < b; k++) {
                    int len = sylLens[k];
                    if (affects(c, pos, len, mask)) {
                        changed = true;
                        break;
                    }
                    pos += len;
                }
                if (!changed) {
                    folded[i] = c;   // 没受影响:共用原字符串,不占新内存
                    continue;
                }
                sb.setLength(0);
                pos = 0;
                for (int k = a; k < b; k++) {
                    int len = sylLens[k];
                    sb.append(canonSyllable(c.substring(pos, pos + len), mask));
                    pos += len;
                }
                folded[i] = sb.toString();
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (x, y) -> folded[x].compareTo(folded[y]));
            String[] keys = new String[n];
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) {
                int id = order[i];
                ids[i] = id;
                keys[i] = folded[id];
            }
            return new Index(mask, keys, ids);
        }
    }
}
