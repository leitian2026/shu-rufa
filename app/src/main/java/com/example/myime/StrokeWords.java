package com.example.myime;

import java.util.List;

/**
 * 笔画组词:一个词的每个字各打一段笔画(段与段之间用分字键隔开,如 hs'sphn)。
 * 做法是"先定第一个字,再在以它开头的词里筛后面的字",不扫整个词库:
 *   1. 第一段笔画先查出前几十个可能的首字(笔画引擎照常查);
 *   2. 在「按词本身排序」的下标里,取以这些字开头的词(每个字一段连续区间,二分定位);
 *   3. 后面每个字用笔画反查索引核对;通过的按分数留前 max 个。
 * 总共最多看 scanCap 个词(常用字开头的词有几千个),一次按键的开销有上限。
 * 全是原始数组上的循环,不分配对象(只有最后的结果数组),在电脑上就能测。
 */
public final class StrokeWords {
    private StrokeWords() {}

    /** 候选词的第 2、3…个字要和 segs[1]、segs[2]… 对上(第 1 个字由调用方保证)。segs[i] 为空 = 这一位任意字;* = 任意一笔。 */
    public static TextFilter accept(final StrokeIndex ix, final String[] segs) {
        return accept(ix, segs, false);
    }

    /** exact = true:每个字的笔画数必须刚好等于它那一段的长度(「整字」)。空段不受限制。 */
    public static TextFilter accept(final StrokeIndex ix, final String[] segs, final boolean exact) {
        return new TextFilter() {
            @Override
            public boolean accept(String text) {
                int off = 0;
                for (int i = 0; i < segs.length; i++) {
                    if (off >= text.length()) return false;   // 词的字数不够
                    int cp = text.codePointAt(off);
                    off += Character.charCount(cp);
                    if (i == 0) continue;
                    if (!ix.matchesCp(cp, segs[i], exact)) return false;
                }
                return true;
            }
        };
    }

    private static int lowerBound(String[] keys, String target) {
        int lo = 0;
        int hi = keys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid].compareTo(target) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * 从以 firsts 里某个字开头的词里挑:accept 通过的才要,score 打分,留分数最高的 max 个(同分先遇到的在前),按分数从高到低返回。
     * 单字本身(文本就是 first)不要。firsts 按优先级排好(排前面的字先看);扫描总数到 scanCap 就停。
     * textKeys 是按词排序的词表,order[i] 是 textKeys[i] 在 texts / freqs 里的下标。
     */
    public static String[] pick(String[] textKeys, int[] order, String[] texts, int[] freqs,
                                List<String> firsts, TextFilter accept, WordScore score, int max, int scanCap) {
        if (max <= 0 || firsts.isEmpty()) return new String[0];
        String[] bestText = new String[max];
        long[] bestKey = new long[max];
        int size = 0;
        int scanned = 0;
        outer:
        for (String first : firsts) {
            if (first == null || first.isEmpty()) continue;
            int lo = lowerBound(textKeys, first);
            int hi = lowerBound(textKeys, first + '\uffff');
            for (int j = lo; j < hi; j++) {
                if (scanned++ >= scanCap) break outer;
                int id = order[j];
                String text = texts[id];
                if (text.length() <= first.length()) continue;   // 就是这个字本身
                if (!accept.accept(text)) continue;
                long key = score.score(text, freqs[id]);
                if (size == max && key <= bestKey[max - 1]) continue;
                boolean dup = false;
                for (int k = 0; k < size; k++) {
                    if (bestText[k].equals(text)) { dup = true; break; }   // 多音字同一个词会有几条
                }
                if (dup) continue;
                int pos = size < max ? size : max - 1;
                while (pos > 0 && bestKey[pos - 1] < key) {
                    bestKey[pos] = bestKey[pos - 1];
                    bestText[pos] = bestText[pos - 1];
                    pos--;
                }
                bestKey[pos] = key;
                bestText[pos] = text;
                if (size < max) size++;
            }
        }
        String[] out = new String[size];
        System.arraycopy(bestText, 0, out, 0, size);
        return out;
    }
}
