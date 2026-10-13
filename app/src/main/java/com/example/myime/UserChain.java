package com.example.myime;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中文联想用:记"上一个上屏的词 → 下一个上屏的词"最后一次是什么时候(比如我打过"吃"后面接"苹果")。
 * 和 UserFreq 一样不存次数、不衰减:接词每出现一次就盖一个新的时间戳,最近接的排最前;超过上限删最久没用的。
 * 只存在本机应用私有目录。读不加锁,写串行。纯 JDK 代码,可以脱离手机单独测试(见 tools/RankSelfTest.java)。
 */
public final class UserChain {
    /** 两词上下文的分隔符(不会出现在词里,也不是制表/换行)。 */
    public static final char SEP = '\u0001';
    private static final int MAX_PAIRS = 20_000;
    private static final int NEXT_MAX = 8;

    private final File file;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> map = new ConcurrentHashMap<>();
    private boolean dirty = false;
    private int sincePrune = 0;
    private long last = 0L;
    private volatile boolean loaded = false;
    private volatile long learned = 0L;   // 本进程里学了多少次(多机同步判断"攒够没有"用;合并进来的不算)

    public UserChain(File file) {
        this.file = file;
    }

    private static long parseTs(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v < 1 ? -1 : Math.min(v, UserFreq.TS_MASK);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        try {
            SafeFile.Loaded r = SafeFile.read(file);
            if (r != null) {
                List<String[]> old = new ArrayList<>();   // 0.11 以前存的是次数(小写 c 行)
                for (String line : r.lines) {
                    String[] p = line.split("\t", -1);
                    if (p.length == 4 && p[0].equals("C")) {
                        long ts = parseTs(p[3]);
                        if (ts > 0 && !p[1].isEmpty() && !p[2].isEmpty()) {
                            map.computeIfAbsent(p[1], k -> new ConcurrentHashMap<>()).put(p[2], ts);
                        }
                    } else if (p.length == 4 && p[0].equals("c")) {
                        old.add(p);
                    }
                }
                if (!old.isEmpty()) {
                    // 按次数从小到大排好,换成时间先后(都比真实时间早),次数多的就是最近的
                    old.sort(Comparator.comparingInt(p -> countOf(p[3])));
                    long ts = 0;
                    for (String[] p : old) {
                        if (p[1].isEmpty() || p[2].isEmpty()) continue;
                        map.computeIfAbsent(p[1], k -> new ConcurrentHashMap<>()).putIfAbsent(p[2], ++ts);
                    }
                    dirty = true;
                }
                if (r.fromBackup) dirty = true;
            } else if (file.isFile()) {
                ErrorHint.INSTANCE.set("learn", "联想数据文件已损坏且没有可用备份,原文件另存为 " + file.getName() + ".corrupt");
            }
            for (Map<String, Long> row : map.values()) {
                for (long v : row.values()) if (v > last) last = v;
            }
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("用词习惯读取失败", e));
        } finally {
            loaded = true;
        }
    }

    private static int countOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long stamp() {
        long n = System.currentTimeMillis();
        last = n > last ? n : last + 1;
        return last;
    }

    /**
     * 上下文行:上上个词+上一个词(两词上下文)和上一个词(一词上下文)后面我接过的词,合并成 词 -> 值。
     * 值 = 最后一次接的时间;两词上下文命中的再加上 UserFreq.TWO 标记(排在一词上下文命中的前面)。
     * 没有记录返回 null。两词上下文数据少但更准,一词上下文覆盖广,两个一起用。
     */
    public Map<String, Long> ctxRow(String prev2, String prev) {
        if (prev == null) return null;
        if (!loaded) ensureLoaded();
        Map<String, Long> one = map.get(prev);
        Map<String, Long> two = prev2 != null ? map.get(prev2 + SEP + prev) : null;
        if (one == null && two == null) return null;
        Map<String, Long> out = new HashMap<>();
        if (one != null) out.putAll(one);
        if (two != null) {
            for (Map.Entry<String, Long> e : two.entrySet()) {
                long v = e.getValue();
                Long o = out.get(e.getKey());
                if (o != null && o > v) v = o;
                out.put(e.getKey(), v | UserFreq.TWO);
            }
        }
        return out;
    }

    /** 联想:接在这个(或这两个)词后面,我打过的词:两词上下文的在前,各自最近的在前,最多 8 个。 */
    public List<String> next(String prev2, String prev) {
        Map<String, Long> row = ctxRow(prev2, prev);
        if (row == null) return Collections.emptyList();
        List<Map.Entry<String, Long>> es = new ArrayList<>(row.entrySet());
        es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < es.size() && i < NEXT_MAX; i++) out.add(es.get(i).getKey());
        return out;
    }

    public synchronized void learn(String prev, String next) {
        if (prev == null || next == null || prev.isEmpty() || next.isEmpty()) return;
        if (prev.indexOf('\t') >= 0 || prev.indexOf('\n') >= 0) return;
        if (next.indexOf('\t') >= 0 || next.indexOf('\n') >= 0) return;
        ensureLoaded();
        map.computeIfAbsent(prev, k -> new ConcurrentHashMap<>()).put(next, stamp());
        dirty = true;
        learned++;
        if (++sincePrune >= 500) {
            sincePrune = 0;
            if (count() > MAX_PAIRS) prune();
        }
    }

    /** 超过上限:删最久没用的,留 3/4。 */
    private void prune() {
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Long>> a : map.entrySet()) {
            for (Map.Entry<String, Long> b : a.getValue().entrySet()) {
                all.add(new Object[] {a.getKey(), b.getKey(), b.getValue()});
            }
        }
        all.sort((x, y) -> Long.compare((Long) y[2], (Long) x[2]));
        map.clear();
        for (int i = 0; i < Math.min(all.size(), MAX_PAIRS * 3 / 4); i++) {
            Object[] t = all.get(i);
            map.computeIfAbsent((String) t[0], k -> new ConcurrentHashMap<>()).put((String) t[1], (Long) t[2]);
        }
    }


    /** 本进程里自己学了多少次(不含从别的手机合并进来的)。 */
    public long learnedCount() {
        return learned;
    }

    // ------------------------------------------------------------------ 多机同步(见 LearnShare / LearnSync)

    /** 上一个词(一词上下文),或 上上词 SEP 上一个词(两词上下文):每个词都合格才算合格。 */
    private static boolean okContext(String prev) {
        int i = prev.indexOf(SEP);
        if (i < 0) return LearnShare.okWord(prev);
        return LearnShare.okWord(prev.substring(0, i)) && LearnShare.okWord(prev.substring(i + 1));
    }

    /** 导出要共享给别的手机的行(C 行,格式和存盘文件一样)。含数字、长串字母等的词不导出。不加锁(遍历是弱一致的)。 */
    public void exportShared(java.io.Writer w) throws java.io.IOException {
        ensureLoaded();
        for (Map.Entry<String, ConcurrentHashMap<String, Long>> a : map.entrySet()) {
            if (!okContext(a.getKey())) continue;
            for (Map.Entry<String, Long> b : a.getValue().entrySet()) {
                if (LearnShare.okWord(b.getKey())) w.write("C\t" + a.getKey() + "\t" + b.getKey() + "\t" + b.getValue() + "\n");
            }
        }
    }

    private static final int MERGE_CHUNK = 2000;

    /**
     * 合并别的手机导出的 C 行:同一个(上文, 下一个词)取较大的时间戳。顺序无关、重复合并结果不变;
     * 时间戳超过 now 的截成 now。分批加锁,不会让正在打字的 learn() 等很久。返回有多少条发生了变化。
     */
    public int mergeRows(List<String> lines, long now) {
        return mergeRows(lines, now, 0L);
    }

    /** minTs:时间戳早于它的行不收(这台手机清除过学习数据,清除之前的记录不能被别的手机再同步回来)。 */
    public int mergeRows(List<String> lines, long now, long minTs) {
        ensureLoaded();
        int changed = 0;
        List<Object[]> batch = new ArrayList<>(MERGE_CHUNK);
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length != 4 || !p[0].equals("C")) continue;
            long ts = parseTs(p[3]);
            if (ts < Math.max(minTs, 1) || p[1].isEmpty() || !okContext(p[1]) || !LearnShare.okWord(p[2])) continue;
            batch.add(new Object[] {p[1], p[2], Math.min(ts, now)});
            if (batch.size() >= MERGE_CHUNK) {
                changed += applyBatch(batch);
                batch.clear();
            }
        }
        changed += applyBatch(batch);
        return changed;
    }

    private synchronized int applyBatch(List<Object[]> batch) {
        int changed = 0;
        for (Object[] r : batch) {
            long ts = (Long) r[2];
            ConcurrentHashMap<String, Long> row = map.computeIfAbsent((String) r[0], k -> new ConcurrentHashMap<>());
            Long old = row.get((String) r[1]);
            if (old == null || old < ts) {
                row.put((String) r[1], ts);
                changed++;
            }
            if (ts > last) last = ts;
        }
        if (changed > 0) {
            dirty = true;
            if (count() > MAX_PAIRS) prune();
        }
        return changed;
    }

    public synchronized int count() {
        ensureLoaded();
        int n = 0;
        for (Map<String, Long> m : map.values()) n += m.size();
        return n;
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        try {
            SafeFile.write(file, w -> {
                for (Map.Entry<String, ConcurrentHashMap<String, Long>> a : map.entrySet()) {
                    for (Map.Entry<String, Long> b : a.getValue().entrySet()) {
                        w.write("C\t" + a.getKey() + "\t" + b.getKey() + "\t" + b.getValue() + "\n");
                    }
                }
            });
            dirty = false;
            ErrorHint.INSTANCE.clear("learn");
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("用词习惯保存失败", e));
        }
    }

    public synchronized void clear() {
        loaded = true;
        map.clear();
        dirty = false;
        SafeFile.deleteAll(file);
    }
}
