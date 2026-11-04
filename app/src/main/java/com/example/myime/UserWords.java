package com.example.myime;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 我自己造的词(纯 JDK 代码,可以脱离手机单独测试,见 tools/UserWordsSelfTest.java)。
 *
 * 来源:同一次输入里连续选了几段拼音候选(打 zhangwei,先选「张」再选「伟」),拼起来的「张伟」
 * 词库里没有,就记成一个新词;下次打 zhangwei 它排在最前面,整句里也能直接用。
 *
 * 防止把随手拼的乱词记进去:
 *   · 每一段都是单个字的(人名、新词)第一次就记;
 *   · 有的段本身是多字词(我们+明天)要拼成同样的词第二次才算,第一次只是记个数,不出现在候选里。
 *
 * 文件:user/words.txt(SafeFile 格式),每行:W<Tab>词<Tab>音节(空格分隔,如 zhang wei)<Tab>次数<Tab>时间。
 * 次数 ≥ 2 才算「可用」。最多存 3000 个,多了先丢没有用过的、再丢次数少又久没用的。
 * 查询只读一份不可变的快照(Snapshot),打字时不加锁。
 */
public final class UserWords {
    public static final int MAX_WORDS = 3000;
    public static final int MIN_CHARS = 2;
    public static final int MAX_CHARS = 8;
    private static final int USABLE = 2;

    private static final class Entry {
        final String text;
        final String[] syls;
        final String compact;
        int count;
        long ts;

        Entry(String text, String[] syls, int count, long ts) {
            this.text = text;
            this.syls = syls;
            StringBuilder sb = new StringBuilder();
            for (String s : syls) sb.append(s);
            this.compact = sb.toString();
            this.count = count;
            this.ts = ts;
        }
    }

    /** 只读快照:按拼音连写排好序,供查词和整句用。只包含「可用」的词。 */
    public static final class Snapshot {
        public final String[] keys;
        public final String[] texts;
        public final int[][] lens;
        public final int[] counts;
        private final long[] stamps;
        /** 只有「模糊音折叠视图」才有:keys 是折叠后的拼音连写,这里是每一项折叠前(原来)的拼音连写。普通快照是 null。 */
        public final String[] origKeys;
        private volatile Folded foldedView;

        Snapshot(String[] keys, String[] texts, int[][] lens, int[] counts, long[] stamps) {
            this(keys, texts, lens, counts, stamps, null);
        }

        Snapshot(String[] keys, String[] texts, int[][] lens, int[] counts, long[] stamps, String[] origKeys) {
            this.keys = keys;
            this.texts = texts;
            this.lens = lens;
            this.counts = counts;
            this.stamps = stamps;
            this.origKeys = origKeys;
        }

        private static final class Folded {
            final int mask;
            final Snapshot snap;

            Folded(int mask, Snapshot snap) {
                this.mask = mask;
                this.snap = snap;
            }
        }

        /** 一个拼音连写按音节长度逐个折叠(Fuzzy.canonSyllable)。 */
        private static String foldKey(String key, int[] len, int mask) {
            StringBuilder sb = new StringBuilder(key.length());
            int pos = 0;
            for (int l : len) {
                if (l <= 0 || pos + l > key.length()) break;
                sb.append(Fuzzy.canonSyllable(key.substring(pos, pos + l), mask));
                pos += l;
            }
            if (pos < key.length()) sb.append(key, pos, key.length());
            return sb.toString();
        }

        /**
         * 模糊音折叠视图:同样这些词,拼音连写按 mask 折叠后重新排序(zhi / zi 折成同一种写法),
         * origKeys 记着每个词原来的拼音。快照不可变,所以每个 mask 只建一次(最多 3000 个词,毫秒级),随快照一起被换掉。
         * mask = 0 返回自己。
         */
        public Snapshot folded(int mask) {
            if (mask == 0 || keys.length == 0) return this;
            Folded f = foldedView;
            if (f != null && f.mask == mask) return f.snap;
            synchronized (this) {
                f = foldedView;
                if (f != null && f.mask == mask) return f.snap;
                int n = keys.length;
                final String[] fk = new String[n];
                for (int i = 0; i < n; i++) fk[i] = foldKey(keys[i], lens[i], mask);
                Integer[] ord = new Integer[n];
                for (int i = 0; i < n; i++) ord[i] = i;
                Arrays.sort(ord, (a, b) -> fk[a].compareTo(fk[b]));
                String[] k2 = new String[n];
                String[] t2 = new String[n];
                int[][] l2 = new int[n][];
                int[] c2 = new int[n];
                long[] s2 = new long[n];
                String[] o2 = new String[n];
                for (int i = 0; i < n; i++) {
                    int j = ord[i];
                    k2[i] = fk[j];
                    t2[i] = texts[j];
                    l2[i] = lens[j];
                    c2[i] = counts[j];
                    s2[i] = stamps[j];
                    o2[i] = keys[j];
                }
                Snapshot s = new Snapshot(k2, t2, l2, c2, s2, o2);
                foldedView = new Folded(mask, s);
                return s;
            }
        }

        /** 折叠视图里:折叠后的拼音连写正好等于 p、原来的拼音不是 skip 的词(skip 那些普通查询已经给过了),常用的在前。 */
        public String[] exactSkip(String p, String skip) {
            int lo = lowerBound(keys, p);
            if (lo >= keys.length || !keys[lo].equals(p)) return NONE;
            return collect(lo, lowerBound(keys, p + '\u0000'), 8, skip, false);
        }

        /** 折叠视图里:折叠后的拼音连写以 p 开头、原来的拼音不以 skip 开头的词(补全),常用的在前,最多 max 个。 */
        public String[] prefixSkip(String p, String skip, int max) {
            int lo = lowerBound(keys, p);
            if (lo >= keys.length || !keys[lo].startsWith(p)) return NONE;
            return collect(lo, lowerBound(keys, p + '\uffff'), max, skip, true);
        }

        public static int lowerBound(String[] a, String target) {
            int lo = 0;
            int hi = a.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[mid].compareTo(target) < 0) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        private static final String[] NONE = new String[0];

        private String[] collect(int lo, int hi, int max, String skipKey) {
            return collect(lo, hi, max, skipKey, false);
        }

        /** skipKey:跳过拼音(折叠视图里用原拼音)等于它的词;skipPrefix = true 时改成「以它开头的」。 */
        private String[] collect(int lo, int hi, int max, String skipKey, boolean skipPrefix) {
            if (hi <= lo) return NONE;
            Integer[] idx = new Integer[hi - lo];
            int n = 0;
            for (int i = lo; i < hi; i++) {
                if (skipKey != null) {
                    String real = origKeys != null ? origKeys[i] : keys[i];
                    if (skipPrefix ? real.startsWith(skipKey) : real.equals(skipKey)) continue;
                }
                idx[n++] = i;
            }
            if (n == 0) return NONE;
            Arrays.sort(idx, 0, n, (a, b) -> counts[a] != counts[b] ? counts[b] - counts[a] : Long.compare(stamps[b], stamps[a]));
            int m = Math.min(n, max);
            String[] out = new String[m];
            for (int i = 0; i < m; i++) out[i] = texts[idx[i]];
            return out;
        }

        /** 拼音连写正好等于 p 的词,常用的在前。 */
        public String[] exact(String p) {
            int lo = lowerBound(keys, p);
            if (lo >= keys.length || !keys[lo].equals(p)) return NONE;
            return collect(lo, lowerBound(keys, p + '\u0000'), 8, null);
        }

        /** 拼音连写以 p 开头、但不等于 p 的词(补全),常用的在前,最多 max 个。 */
        public String[] prefix(String p, int max) {
            int lo = lowerBound(keys, p);
            if (lo >= keys.length || !keys[lo].startsWith(p)) return NONE;
            return collect(lo, lowerBound(keys, p + '\uffff'), max, p);
        }

        public int size() {
            return keys.length;
        }
    }

    private final File file;
    private final Map<String, Entry> map = new HashMap<>();   // key = 词 + \t + 音节连写
    private boolean dirty = false;
    private boolean loaded = false;
    private long last = 0L;
    private volatile long learned = 0L;   // 本进程里自己造/重复造了多少次(多机同步判断"攒够没有"用;合并进来的不算)
    private volatile Snapshot snap = null;

    public UserWords(File file) {
        this.file = file;
    }

    private static String keyOf(String text, String compact) {
        return text + "\t" + compact;
    }

    private long stamp() {
        long n = System.currentTimeMillis();
        last = n > last ? n : last + 1;
        return last;
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        try {
            SafeFile.Loaded r = SafeFile.read(file);
            if (r != null) {
                for (String line : r.lines) {
                    String[] p = line.split("\t", -1);
                    if (p.length != 5 || !p[0].equals("W") || p[1].isEmpty() || p[2].isEmpty()) continue;
                    try {
                        String[] syls = p[2].split(" ");
                        int count = Integer.parseInt(p[3].trim());
                        long ts = Long.parseLong(p[4].trim());
                        if (count < 1 || ts < 1 || syls.length < MIN_CHARS) continue;
                        Entry e = new Entry(p[1], syls, Math.min(count, 1000), Math.min(ts, UserFreq.TS_MASK));
                        map.put(keyOf(e.text, e.compact), e);
                        if (e.ts > last) last = e.ts;
                    } catch (NumberFormatException ignored) {
                        // 坏行跳过
                    }
                }
                if (r.fromBackup) dirty = true;
            } else if (file.isFile()) {
                ErrorHint.INSTANCE.set("learn", "造词数据文件已损坏且没有可用备份,原文件另存为 " + file.getName() + ".corrupt");
            }
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("造词数据读取失败", e));
        } finally {
            loaded = true;
        }
    }

    /**
     * 记一个新词。
     * @param syls      每个字的拼音(词库里的写法,如 zhang、wei),个数必须等于字数
     * @param immediate 每一段都是单个字:第一次就可用
     * @return 这次添加后这个词是否可用
     */
    public synchronized boolean add(String text, String[] syls, boolean immediate) {
        ensureLoaded();
        int chars = text.codePointCount(0, text.length());
        if (chars < MIN_CHARS || chars > MAX_CHARS || syls.length != chars) return false;
        StringBuilder sb = new StringBuilder();
        for (String s : syls) {
            if (s.isEmpty()) return false;
            sb.append(s);
        }
        String compact = sb.toString();
        String key = keyOf(text, compact);
        Entry e = map.get(key);
        if (e == null) {
            e = new Entry(text, syls.clone(), immediate ? USABLE : 1, stamp());
            map.put(key, e);
            if (map.size() > MAX_WORDS) prune();
        } else {
            if (e.count < 1000) e.count++;
            e.ts = stamp();
        }
        dirty = true;
        snap = null;
        learned++;
        return e.count >= USABLE;
    }

    private void prune() {
        List<Entry> all = new ArrayList<>(map.values());
        // 先丢还没可用的(只见过一次),再丢次数少、久没用的;一次丢掉 10%,免得每次添加都要排序
        all.sort(Comparator.<Entry>comparingInt(x -> x.count >= USABLE ? 1 : 0)
                .thenComparingInt(x -> x.count)
                .thenComparingLong(x -> x.ts));
        int drop = Math.max(1, MAX_WORDS / 10);
        for (int i = 0; i < drop && i < all.size(); i++) {
            Entry x = all.get(i);
            map.remove(keyOf(x.text, x.compact));
        }
    }

    /** 当前可用词的只读快照;没有可用词返回 null。 */
    public Snapshot snapshot() {
        Snapshot s = snap;
        if (s != null) return s.size() == 0 ? null : s;
        synchronized (this) {
            ensureLoaded();
            s = snap;
            if (s == null) {
                List<Entry> use = new ArrayList<>();
                for (Entry e : map.values()) if (e.count >= USABLE) use.add(e);
                use.sort(Comparator.comparing((Entry e) -> e.compact));
                int n = use.size();
                String[] keys = new String[n];
                String[] texts = new String[n];
                int[][] lens = new int[n][];
                int[] counts = new int[n];
                long[] stamps = new long[n];
                for (int i = 0; i < n; i++) {
                    Entry e = use.get(i);
                    keys[i] = e.compact;
                    texts[i] = e.text;
                    lens[i] = new int[e.syls.length];
                    for (int k = 0; k < e.syls.length; k++) lens[i][k] = e.syls[k].length();
                    counts[i] = e.count;
                    stamps[i] = e.ts;
                }
                s = new Snapshot(keys, texts, lens, counts, stamps);
                snap = s;
            }
        }
        return s.size() == 0 ? null : s;
    }

    /** 本进程里自己学了多少次(不含从别的手机合并进来的)。 */
    public long learnedCount() {
        return learned;
    }

    // ------------------------------------------------------------------ 多机同步(见 LearnShare / LearnSync)

    /**
     * 导出要共享给别的手机的行(W 行,格式和存盘文件一样)。只导出可用的词(次数 ≥ 2);
     * 含数字、长串字母等的词不导出(和别的学习数据同一条规则)。
     */
    public void exportShared(java.io.Writer w) throws java.io.IOException {
        List<String> lines = new ArrayList<>();
        synchronized (this) {
            ensureLoaded();
            for (Entry e : map.values()) {
                if (e.count >= USABLE && LearnShare.okWord(e.text)) {
                    lines.add("W\t" + e.text + "\t" + String.join(" ", e.syls) + "\t" + e.count + "\t" + e.ts + "\n");
                }
            }
        }
        for (String l : lines) w.write(l);
    }

    public int mergeRows(List<String> lines, long now) {
        return mergeRows(lines, now, 0L);
    }

    /**
     * 合并别的手机导出的 W 行:同一个(词, 拼音)次数取较大的、时间取较大的,所以顺序无关、重复合并结果不变;
     * 时间戳超过 now 的截成 now;早于 minTs(这台手机清除学习数据的时间)的不收;格式或内容不合格的行跳过。
     * 返回有多少个词发生了变化(新增或次数 / 时间变大)。
     */
    public synchronized int mergeRows(List<String> lines, long now, long minTs) {
        ensureLoaded();
        int changed = 0;
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length != 5 || !p[0].equals("W") || !LearnShare.okWord(p[1])) continue;
            String[] syls = p[2].split(" ");
            int chars = p[1].codePointCount(0, p[1].length());
            if (chars < MIN_CHARS || chars > MAX_CHARS || syls.length != chars) continue;
            boolean ok = true;
            for (String sy : syls) {
                if (sy.isEmpty() || sy.length() > 6) { ok = false; break; }
                for (int i = 0; i < sy.length(); i++) {
                    char c = sy.charAt(i);
                    if (c < 'a' || c > 'z') { ok = false; break; }
                }
                if (!ok) break;
            }
            if (!ok) continue;
            int count;
            long ts;
            try {
                count = Integer.parseInt(p[3].trim());
                ts = Long.parseLong(p[4].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (count < USABLE || ts < Math.max(minTs, 1)) continue;
            count = Math.min(count, 1000);
            ts = Math.min(Math.min(ts, UserFreq.TS_MASK), now);
            Entry cur = new Entry(p[1], syls, count, ts);
            String key = keyOf(cur.text, cur.compact);
            Entry old = map.get(key);
            if (old == null) {
                map.put(key, cur);
                changed++;
            } else if (count > old.count || ts > old.ts) {
                old.count = Math.max(old.count, count);
                old.ts = Math.max(old.ts, ts);
                changed++;
            }
            if (ts > last) last = ts;
        }
        if (changed > 0) {
            if (map.size() > MAX_WORDS) prune();
            dirty = true;
            snap = null;
        }
        return changed;
    }

    /** 逐个给出自己造过的词和时间(统计「不同的字」用)。 */
    public synchronized void forEachWord(java.util.function.BiConsumer<String, Long> f) {
        ensureLoaded();
        for (Entry e : map.values()) f.accept(e.text, e.ts);
    }

    /** 可用的词有多少个。 */
    public synchronized int count() {
        ensureLoaded();
        int n = 0;
        for (Entry e : map.values()) if (e.count >= USABLE) n++;
        return n;
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        try {
            SafeFile.write(file, w -> {
                for (Entry e : map.values()) {
                    w.write("W\t" + e.text + "\t" + String.join(" ", e.syls) + "\t" + e.count + "\t" + e.ts + "\n");
                }
            });
            dirty = false;
            ErrorHint.INSTANCE.clear("learn");
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("造词数据保存失败", e));
        }
    }

    public synchronized void clear() {
        loaded = true;
        map.clear();
        dirty = false;
        snap = null;
        SafeFile.deleteAll(file);
    }
}
