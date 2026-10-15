package com.example.myime;

import java.util.Arrays;

/**
 * 字 → 笔画串 的反查索引,给拼音里的「笔画筛选」用:问"这个词的第一个字,笔画是不是以 hs 开头"。
 *
 * 数据直接借用笔画引擎已经读进内存的两个数组(字、笔画串),这里只多建两个整数数组(按字码排好的字码、对应的条目号),
 * 不复制任何字符串。建一次要对整个笔画表排一次序(原始类型排序,十万条在手机上几十毫秒),只在第一次用笔画筛选时建,
 * 随笔画词库一起被释放。同一个字有几种笔顺时都收,任何一种以这几笔开头就算匹配。
 */
public final class StrokeIndex {
    private final String[] codes;
    private final int[] cps;   // 排好序的字码
    private final int[] ids;   // cps[i] 对应的条目号(codes 的下标)

    public StrokeIndex(String[] texts, String[] codes) {
        this.codes = codes;
        int n = Math.min(texts.length, codes.length);
        long[] packed = new long[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            String t = texts[i];
            if (t == null || t.isEmpty()) continue;
            int cp = t.codePointAt(0);
            if (Character.charCount(cp) != t.length()) continue;   // 只收单字
            packed[m++] = ((long) cp << 32) | (long) i;
        }
        Arrays.sort(packed, 0, m);
        cps = new int[m];
        ids = new int[m];
        for (int i = 0; i < m; i++) {
            cps[i] = (int) (packed[i] >>> 32);
            ids[i] = (int) (packed[i] & 0xffffffffL);
        }
    }

    /** text 的第一个字,有没有一种笔顺是以 prefix 开头的。prefix 为空时只看这个字在不在笔画表里。 */
    public boolean hasPrefix(String text, String prefix) {
        if (text == null || text.isEmpty()) return false;
        int cp = text.codePointAt(0);
        int lo = 0;
        int hi = cps.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cps[mid] < cp) lo = mid + 1; else hi = mid;
        }
        while (lo < cps.length && cps[lo] == cp) {
            if (codes[ids[lo]].startsWith(prefix)) return true;
            lo++;
        }
        return false;
    }

    /** 条目数(自测用)。 */
    public int size() {
        return cps.length;
    }
}
