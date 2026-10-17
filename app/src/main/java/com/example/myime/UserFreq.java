package com.example.myime;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拼音 / 笔画用:记"选过哪个词",选了就排到最前面。
 *
 * 不存次数,只存"最后一次选它是什么时候"(毫秒时间戳,本机内严格递增),所以:
 *   - 每选一个词,它马上排到最前面,同一个编码下旧的往后挤一位;不需要衰减算法;
 *   - 超过上限时删最久没用的(LRU),不是删次数最少的;
 *   - 时间戳在几台手机上是同一条时间线,以后要合并习惯,取较大值就行。
 *
 * 排序层级(数字越大越靠前,0 = 没学过,调用者退回词频):
 *   4 这个编码 + 这个词 选过的:先比"这个编码最近 WINDOW 次选择里出现几次",再比最后一次的时间;
 *       所以"的/得/地"这类同音高频字不会因为偶尔选了一次别的就跳位,但连续选就会换;
 *   3 跟在上一个+上上个词后面我接过的词(两词上下文),按时间;
 *   2 跟在上一个词后面我接过的词(一词上下文),按时间;
 *   1 这个词在别的编码下选过的,按时间。
 * 纯 JDK 代码(不依赖 Android),可以脱离手机单独测试(见 tools/RankSelfTest.java)。
 */
public final class UserFreq {
    /** 每个编码保留最近几次选择,用来数"最近出现几次"。 */
    public static final int WINDOW = 5;
    public static final long TS_MASK = (1L << 42) - 1;
    /** 接词记录里,两词上下文命中的标记(放在时间戳上面的位)。 */
    public static final long TWO = 1L << 50;

    private static final int TIER_SHIFT = 45;
    private static final int CNT_SHIFT = 42;
    private static final long TIER_PAIR = 4;
    private static final long TIER_CTX2 = 3;
    private static final long TIER_CTX1 = 2;
    private static final long TIER_TEXT = 1;

    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_PAIRS = MAX_ENTRIES * 4;

    /** 一个编码最近的几次选择,新的在前。不可变,更新时整个换掉,读的时候不用加锁。 */
    private static final class Win {
        final String[] texts;
        final long[] stamps;

        Win(String[] texts, long[] stamps) {
            this.texts = texts;
            this.stamps = stamps;
        }
    }

    private final File file;
    private final ConcurrentHashMap<String, Long> byText = new ConcurrentHashMap<>();   // 词 -> 最后一次选的时间
    private final ConcurrentHashMap<String, Long> byPair = new ConcurrentHashMap<>();   // "编码\t词" -> 最后一次选的时间
    private final ConcurrentHashMap<String, Win> byCode = new ConcurrentHashMap<>();    // 编码 -> 最近几次选择
    private boolean dirty = false;
    private long last = 0L;
    private volatile boolean loaded = false;
    private volatile long learned = 0L;   // 本进程里学了多少次(多机同步判断"攒够没有"用;合并进来的不算)

    public UserFreq(File file) {
        this.file = file;
    }

    private static boolean badChars(String s) {
        return s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }

    private static long parseTs(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v < 1 ? -1 : Math.min(v, TS_MASK);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        try {
            SafeFile.Loaded r = SafeFile.read(file);
            if (r != null) {
                List<String[]> win = new ArrayList<>();
                // 0.11 以前存的是次数(t / p 两种行):读进来,按次数从小到大排好,换成时间先后(都比真实时间早)
                List<String[]> oldText = new ArrayList<>();
                List<String[]> oldPair = new ArrayList<>();
                for (String line : r.lines) {
                    String[] p = line.split("\t", -1);
                    if (p.length == 3 && p[0].equals("T")) {
                        long ts = parseTs(p[2]);
                        if (ts > 0 && !p[1].isEmpty()) byText.put(p[1], ts);
                    } else if (p.length == 4 && p[0].equals("P")) {
                        long ts = parseTs(p[3]);
                        if (ts > 0 && !p[1].isEmpty() && !p[2].isEmpty()) byPair.put(p[1] + "\t" + p[2], ts);
                    } else if (p.length == 4 && p[0].equals("W")) {
                        if (parseTs(p[3]) > 0 && !p[1].isEmpty() && !p[2].isEmpty()) win.add(p);
                    } else if (p.length == 3 && p[0].equals("t")) {
                        oldText.add(p);
                    } else if (p.length == 4 && p[0].equals("p")) {
                        oldPair.add(p);
                    }
                }
                if (!win.isEmpty()) loadWindows(win);
                if (!oldText.isEmpty() || !oldPair.isEmpty()) {
                    migrate(oldText, oldPair);
                    dirty = true;
                }
                if (r.fromBackup) dirty = true;   // 主文件坏了、这是从备份恢复的:下次保存时把主文件重写好
            } else if (file.isFile()) {
                ErrorHint.INSTANCE.set("learn", "用词习惯文件已损坏且没有可用备份,原文件另存为 " + file.getName() + ".corrupt");
            }
            for (long v : byText.values()) if (v > last) last = v;
            for (long v : byPair.values()) if (v > last) last = v;
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("用词习惯读取失败", e));
        } finally {
            loaded = true;   // 读完才标记,别的线程在这之前会等在 ensureLoaded 的锁上
        }
    }

    private void loadWindows(List<String[]> rows) {
        rows.sort((a, b) -> Long.compare(parseTs(b[3]), parseTs(a[3])));   // 新的在前
        Map<String, List<String[]>> by = new java.util.HashMap<>();
        for (String[] p : rows) {
            List<String[]> l = by.computeIfAbsent(p[1], k -> new ArrayList<>());
            if (l.size() < WINDOW) l.add(p);
        }
        for (Map.Entry<String, List<String[]>> e : by.entrySet()) {
            List<String[]> l = e.getValue();
            String[] t = new String[l.size()];
            long[] s = new long[l.size()];
            for (int i = 0; i < t.length; i++) {
                t[i] = l.get(i)[2];
                s[i] = parseTs(l.get(i)[3]);
            }
            byCode.put(e.getKey(), new Win(t, s));
        }
    }

    private void migrate(List<String[]> oldText, List<String[]> oldPair) {
        oldText.sort(Comparator.comparingInt(p -> countOf(p[2])));
        long ts = 0;
        for (String[] p : oldText) {
            if (!p[1].isEmpty()) byText.putIfAbsent(p[1], ++ts);
        }
        oldPair.sort(Comparator.comparingInt(p -> countOf(p[3])));
        ts = 0;
        Map<String, List<String[]>> byC = new java.util.HashMap<>();
        for (String[] p : oldPair) {
            if (p[1].isEmpty() || p[2].isEmpty()) continue;
            byPair.putIfAbsent(p[1] + "\t" + p[2], ++ts);
            byC.computeIfAbsent(p[1], k -> new ArrayList<>()).add(p);   // 每个编码里仍是次数从小到大
        }
        // 窗口填初值:不然升级后第一次选\"得\",它会直接跳到选过上百次的\"的\"前面。
        // 按这个编码下各词的次数占比分配窗口的 WINDOW 个位置(向下取整,且不超过它自己的次数):
        // 的 100 次、得 3 次 → 窗口里是 的 ×4;两个词各 1 次 → 各 1 个(没有谁占优);
        // 只选过 1 次的词只占 1 个位置,所以稀疏的编码下,新选的词仍然直接排第一。
        for (Map.Entry<String, List<String[]>> e : byC.entrySet()) {
            if (byCode.containsKey(e.getKey())) continue;
            List<String[]> l = e.getValue();
            long total = 0;
            for (String[] p : l) total += Math.max(countOf(p[3]), 0);
            if (total <= 0) continue;
            List<String> texts = new ArrayList<>();
            List<Long> stamps = new ArrayList<>();
            for (int i = l.size() - 1; i >= 0 && texts.size() < WINDOW; i--) {   // 次数多的(时间新的)在前
                String[] p = l.get(i);
                long c = Math.max(countOf(p[3]), 0);
                int slots = (int) Math.min(c, WINDOW * c / total);
                Long st = byPair.get(p[1] + "\t" + p[2]);
                if (st == null) continue;
                for (int k = 0; k < slots && texts.size() < WINDOW; k++) {
                    texts.add(p[2]);
                    stamps.add(st);
                }
            }
            if (texts.isEmpty()) continue;
            long[] sa = new long[stamps.size()];
            for (int i = 0; i < sa.length; i++) sa[i] = stamps.get(i);
            byCode.put(e.getKey(), new Win(texts.toArray(new String[0]), sa));
        }
    }

    private static int countOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 本机内严格递增的时间戳:时钟被往回调也不会倒退。 */
    private long stamp() {
        long n = System.currentTimeMillis();
        last = n > last ? n : last + 1;
        return last;
    }

    /** 有没有学过任何词。没有的话排序时可以完全不查表。 */
    public boolean hasLearned() {
        if (!loaded) ensureLoaded();
        return !byText.isEmpty();
    }

    /** 这个词最后一次(在任何编码下)被选的时间;0 = 没选过。不加锁。 */
    public long textStamp(String text) {
        if (!loaded) ensureLoaded();
        Long v = byText.get(text);
        return v == null ? 0L : v;
    }

    /** 这个编码下选这个词的最后时间;0 = 没选过。不加锁。 */
    public long pairStamp(String code, String text) {
        if (!loaded) ensureLoaded();
        Long v = byPair.get(code + "\t" + text);
        return v == null ? 0L : v;
    }

    /** 这个编码最近的几次选择(新的在前,可能重复);没有返回 null。不加锁,调用方不要改它。 */
    public String[] recent(String code) {
        if (!loaded) ensureLoaded();
        Win w = byCode.get(code);
        return w == null ? null : w.texts;
    }

    /**
     * 排序键,越大越靠前;0 = 这个词没有任何学习记录(调用方用词频)。
     * u 可以为 null(没学过),这时只看 ctx。ctx = 接词记录里这个词的值(UserChain.ctxRow),没有传 0。
     * win = recent(code),调用方每次查询取一次传进来。
     */
    public static long rank(UserFreq u, String code, String text, String[] win, long ctx) {
        long tt = u == null ? 0L : u.textStamp(text);
        if (tt != 0L) {
            long pt = u.pairStamp(code, text);
            if (pt != 0L) {
                int cnt = 0;
                if (win != null) {
                    for (String w : win) if (w.equals(text)) cnt++;
                }
                return (TIER_PAIR << TIER_SHIFT) | ((long) cnt << CNT_SHIFT) | (pt & TS_MASK);
            }
        }
        if (ctx != 0L) {
            long tier = (ctx & TWO) != 0L ? TIER_CTX2 : TIER_CTX1;
            return (tier << TIER_SHIFT) | (ctx & TS_MASK);
        }
        if (tt != 0L) return (TIER_TEXT << TIER_SHIFT) | (tt & TS_MASK);
        return 0L;
    }

    /** 只看"这个词选过"的排序键(联想里按前缀补全用);没选过返回 0。 */
    public static long textRank(UserFreq u, String text) {
        long tt = u == null ? 0L : u.textStamp(text);
        return tt == 0L ? 0L : (TIER_TEXT << TIER_SHIFT) | (tt & TS_MASK);
    }

    public synchronized void learn(String code, String text) {
        if (text == null || code == null || text.isEmpty() || code.isEmpty()) return;
        if (badChars(text) || badChars(code)) return;
        ensureLoaded();
        long ts = stamp();
        byText.put(text, ts);
        byPair.put(code + "\t" + text, ts);
        Win w = byCode.get(code);
        int n = w == null ? 0 : Math.min(w.texts.length, WINDOW - 1);
        String[] t = new String[n + 1];
        long[] s = new long[n + 1];
        t[0] = text;
        s[0] = ts;
        for (int i = 0; i < n; i++) {
            t[i + 1] = w.texts[i];
            s[i + 1] = w.stamps[i];
        }
        byCode.put(code, new Win(t, s));
        dirty = true;
        learned++;
        if (byText.size() > MAX_ENTRIES || byPair.size() > MAX_PAIRS) prune();
    }

    /** 本进程里自己学了多少次(不含从别的手机合并进来的)。 */
    public long learnedCount() {
        return learned;
    }

    /** 超过上限:删最久没用的,留 3/4。 */
    private void prune() {
        if (byText.size() > MAX_ENTRIES) {
            List<Map.Entry<String, Long>> es = new ArrayList<>(byText.entrySet());
            es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            Set<String> drop = new HashSet<>();
            for (int i = MAX_ENTRIES * 3 / 4; i < es.size(); i++) drop.add(es.get(i).getKey());
            byText.keySet().removeAll(drop);
            byPair.keySet().removeIf(k -> drop.contains(k.substring(k.indexOf('\t') + 1)));
        }
        if (byPair.size() > MAX_PAIRS) {
            List<Map.Entry<String, Long>> es = new ArrayList<>(byPair.entrySet());
            es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            Set<String> drop = new HashSet<>();
            for (int i = MAX_PAIRS * 3 / 4; i < es.size(); i++) drop.add(es.get(i).getKey());
            byPair.keySet().removeAll(drop);
        }
        // 窗口里只留还有记录的(编码, 词)
        Iterator<Map.Entry<String, Win>> it = byCode.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Win> e = it.next();
            Win w = e.getValue();
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < w.texts.length; i++) {
                if (byPair.containsKey(e.getKey() + "\t" + w.texts[i])) keep.add(i);
            }
            if (keep.isEmpty()) {
                it.remove();
            } else if (keep.size() < w.texts.length) {
                String[] t = new String[keep.size()];
                long[] s = new long[keep.size()];
                for (int i = 0; i < t.length; i++) {
                    t[i] = w.texts[keep.get(i)];
                    s[i] = w.stamps[keep.get(i)];
                }
                e.setValue(new Win(t, s));
            }
        }
    }


    // ------------------------------------------------------------------ 多机同步(见 LearnShare / LearnSync)

    /**
     * 导出要共享给别的手机的行(T / P / W,格式和存盘文件一样)。词不合格的(含数字、长串字母等)不导出。
     * 不加锁:ConcurrentHashMap 遍历是弱一致的,窗口对象不可变;导出期间有新学的词,多一条少一条都没关系。
     */
    public void exportShared(java.io.Writer w) throws java.io.IOException {
        ensureLoaded();
        for (Map.Entry<String, Long> e : byText.entrySet()) {
            if (LearnShare.okWord(e.getKey())) w.write("T\t" + e.getKey() + "\t" + e.getValue() + "\n");
        }
        for (Map.Entry<String, Long> e : byPair.entrySet()) {
            String k = e.getKey();
            int i = k.indexOf('\t');
            if (i > 0 && LearnShare.okWord(k.substring(i + 1))) w.write("P\t" + k + "\t" + e.getValue() + "\n");
        }
        for (Map.Entry<String, Win> e : byCode.entrySet()) {
            Win x = e.getValue();
            for (int i = 0; i < x.texts.length; i++) {
                if (LearnShare.okWord(x.texts[i])) w.write("W\t" + e.getKey() + "\t" + x.texts[i] + "\t" + x.stamps[i] + "\n");
            }
        }
    }

    private static final int MERGE_CHUNK = 2000;

    /**
     * 合并别的手机导出的行:同一个词 / (编码, 词)取较大的时间戳;每个编码的最近选择窗口把两边合在一起取最新的 WINDOW 个。
     * 合并只取较大值,所以顺序无关、重复合并结果不变。时间戳超过 now 的截成 now(别的手机时钟偏快不会一直排在最前面)。
     * 分批加锁(每批 2000 条),不会让正在打字的 learn() 等很久。返回有多少条发生了变化。
     */
    public int mergeRows(List<String> lines, long now) {
        return mergeRows(lines, now, 0L);
    }

    /** minTs:时间戳早于它的行不收(这台手机清除过学习数据,清除之前的记录不能被别的手机再同步回来)。 */
    public int mergeRows(List<String> lines, long now, long minTs) {
        ensureLoaded();
        int changed = 0;
        List<String[]> batch = new ArrayList<>(MERGE_CHUNK);
        List<String[]> win = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length == 3 && p[0].equals("T")) {
                long ts = parseTs(p[2]);
                if (ts >= Math.max(minTs, 1) && LearnShare.okWord(p[1])) batch.add(new String[] {"T", p[1], null, Long.toString(Math.min(ts, now))});
            } else if (p.length == 4 && p[0].equals("P")) {
                long ts = parseTs(p[3]);
                if (ts >= Math.max(minTs, 1) && !p[1].isEmpty() && LearnShare.okWord(p[2])) batch.add(new String[] {"P", p[1], p[2], Long.toString(Math.min(ts, now))});
            } else if (p.length == 4 && p[0].equals("W")) {
                long ts = parseTs(p[3]);
                if (ts >= Math.max(minTs, 1) && !p[1].isEmpty() && LearnShare.okWord(p[2])) win.add(new String[] {"W", p[1], p[2], Long.toString(Math.min(ts, now))});
            }
            if (batch.size() >= MERGE_CHUNK) {
                changed += applyBatch(batch);
                batch.clear();
            }
        }
        changed += applyBatch(batch);
        changed += mergeWindows(win);
        return changed;
    }

    private synchronized int applyBatch(List<String[]> batch) {
        int changed = 0;
        for (String[] r : batch) {
            long ts = Long.parseLong(r[3]);
            ConcurrentHashMap<String, Long> m = r[0].equals("T") ? byText : byPair;
            String key = r[0].equals("T") ? r[1] : r[1] + "\t" + r[2];
            Long old = m.get(key);
            if (old == null || old < ts) {
                m.put(key, ts);
                changed++;
            }
            if (ts > last) last = ts;
        }
        if (changed > 0) {
            dirty = true;
            if (byText.size() > MAX_ENTRIES || byPair.size() > MAX_PAIRS) prune();
        }
        return changed;
    }

    private int mergeWindows(List<String[]> rows) {
        if (rows.isEmpty()) return 0;
        Map<String, List<String[]>> by = new java.util.HashMap<>();
        for (String[] p : rows) by.computeIfAbsent(p[1], k -> new ArrayList<>()).add(p);
        int changed = 0;
        for (Map.Entry<String, List<String[]>> e : by.entrySet()) {
            changed += mergeWindow(e.getKey(), e.getValue());
        }
        return changed;
    }

    /** 一个编码:本机窗口 + 别的手机的窗口,按时间从新到旧,同一(词, 时间)只算一次,留最新的 WINDOW 个。 */
    private synchronized int mergeWindow(String code, List<String[]> incoming) {
        Win old = byCode.get(code);
        List<Object[]> all = new ArrayList<>();
        if (old != null) {
            for (int i = 0; i < old.texts.length; i++) all.add(new Object[] {old.texts[i], old.stamps[i]});
        }
        for (String[] p : incoming) all.add(new Object[] {p[2], Long.parseLong(p[3])});
        all.sort((a, b) -> {
            int c = Long.compare((Long) b[1], (Long) a[1]);
            return c != 0 ? c : ((String) a[0]).compareTo((String) b[0]);
        });
        List<Object[]> keep = new ArrayList<>();
        for (Object[] x : all) {
            if (!keep.isEmpty()) {
                Object[] y = keep.get(keep.size() - 1);
                if (y[0].equals(x[0]) && ((Long) y[1]).equals(x[1])) continue;
            }
            keep.add(x);
            if (keep.size() >= WINDOW) break;
        }
        String[] t = new String[keep.size()];
        long[] s = new long[keep.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = (String) keep.get(i)[0];
            s[i] = (Long) keep.get(i)[1];
            if (s[i] > last) last = s[i];
        }
        if (old != null && java.util.Arrays.equals(old.texts, t) && java.util.Arrays.equals(old.stamps, s)) return 0;
        byCode.put(code, new Win(t, s));
        dirty = true;
        return 1;
    }

    public synchronized int count() {
        ensureLoaded();
        return byText.size();
    }

    /** 逐条给出选过的词和它最近一次选的时间(统计「不同的字」用)。 */
    public synchronized void forEachText(java.util.function.BiConsumer<String, Long> f) {
        ensureLoaded();
        for (Map.Entry<String, Long> e : byText.entrySet()) f.accept(e.getKey(), e.getValue());
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        try {
            SafeFile.write(file, w -> {
                for (Map.Entry<String, Long> e : byText.entrySet()) w.write("T\t" + e.getKey() + "\t" + e.getValue() + "\n");
                for (Map.Entry<String, Long> e : byPair.entrySet()) w.write("P\t" + e.getKey() + "\t" + e.getValue() + "\n");
                for (Map.Entry<String, Win> e : byCode.entrySet()) {
                    Win x = e.getValue();
                    for (int i = 0; i < x.texts.length; i++) {
                        w.write("W\t" + e.getKey() + "\t" + x.texts[i] + "\t" + x.stamps[i] + "\n");
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
        byText.clear();
        byPair.clear();
        byCode.clear();
        dirty = false;
        SafeFile.deleteAll(file);
    }
}
