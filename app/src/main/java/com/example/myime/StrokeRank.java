package com.example.myime;

/**
 * 笔画页单字的排序键(越大越靠前)。从高到低:
 *   字形档(3 = 偏好的那种字形里的常用字,2 = 另一种字形里的常用字,1 = 没字频的生僻字) >
 *   笔画数刚好等于输入的 > 字频(2 倍一档) > 精确字频 > 笔画短的。
 * 「我选过的」排在这一切前面,由调用方另外处理(键 = 1<<62 | 学习排序)。
 *
 * sf = 简体字频(8105 字表),tf = 繁体字频(朙月拼音),没有的是 0 或 1;>1 才算"常用"。
 * 字在两边都常用(人、的这类通用字)时,两种偏好下都是 3 档,只是同档里看偏好那一边的字频。
 * tf 全是 0(旧词库没有繁体字频)时,两种偏好的排序和以前完全一样。
 */
public final class StrokeRank {
    private StrokeRank() {}

    public static long key(int sf, int tf, boolean tradFirst, boolean exact, int len) {
        boolean sfOk = sf > 1;
        boolean tfOk = tf > 1;
        long tier;
        int f;
        if (tradFirst) {
            tier = tfOk ? 3L : (sfOk ? 2L : 1L);
            f = tfOk ? tf : sf;
        } else {
            tier = sfOk ? 3L : (tfOk ? 2L : 1L);
            f = sfOk ? sf : (tfOk ? tf : sf);
        }
        long octave = 32 - Integer.numberOfLeadingZeros(Math.max(f, 0));   // 0..32
        return (tier << 60)
                | (exact ? 1L << 59 : 0L)
                | (octave << 53)
                | ((long) f << 18)
                | (long) (255 - Math.min(len, 255));
    }
}
