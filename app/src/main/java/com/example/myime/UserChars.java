package com.example.myime;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「我用过多少个不同的汉字」:记每个汉字最近一次出现的时间(毫秒)。
 * 只存汉字(标点、字母、数字、表情都不算),同一个字出现多少次都只算一个。
 * 繁体和简体算同一个字(廣 / 广、聽 / 听 都只算一个):存的时候统一换成简体(换法由构造时传进来的 norm 决定,
 * 手机上用系统的繁转简表;不传就原样存)。所以你只用过繁体的 聽,也会计入总数。
 * 一个简体字对应好几个繁体字的(发 ← 發 / 髮,后 ← 后 / 後,干 ← 干 / 乾 / 幹),也合成一个。
 * 来源有三处:上屏时记(note)、启动 / 打开页面时从拼音 / 笔画 / 联想 / 造词几张表里补(absorb)、多机同步时合并别的手机的(mergeRows)。
 * 只增不减,只有「清除学习数据」才清空;所以不会因为学习表被清理 / 删旧条目而变小。
 * 为了不让每次打字都改动这个文件:已有的字,时间距上次记的不到一天就不更新。
 * 数据量很小(常用字几千个),存成一个文件 user/chars.txt,随备份一起走。纯 JDK 代码,可以脱离手机单独测试。
 */
public final class UserChars {
    /** 已有的字,距上次记的时间不到这么久就不刷新(避免频繁改文件)。 */
    static final long REFRESH_MS = 24L * 3600 * 1000;
    /** 汉字总共不会超过这个数;防止被构造的文件撑爆内存。 */
    private static final int MAX_CHARS = 60_000;

    private final File file;
    private final java.util.function.UnaryOperator<String> norm;   // 繁转简;null = 原样
    private final ConcurrentHashMap<Integer, Integer> normCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> map = new ConcurrentHashMap<>();
    private boolean dirty = false;
    private boolean loaded = false;

    public UserChars(File file) {
        this(file, null);
    }

    /** norm:把一个汉字(字符串)换成它的简体写法;换不了就原样返回。每个字只换一次(结果有缓存)。 */
    public UserChars(File file, java.util.function.UnaryOperator<String> norm) {
        this.file = file;
        this.norm = norm;
    }

    /** 这个字统一后的写法(繁体 → 简体)。换出来不是恰好一个汉字(异常、没有对应)就保持原字。 */
    private int canon(int cp) {
        if (norm == null) return cp;
        Integer c = normCache.get(cp);
        if (c != null) return c;
        int r = cp;
        try {
            String o = norm.apply(new String(Character.toChars(cp)));
            if (o != null && !o.isEmpty()) {
                int c0 = o.codePointAt(0);
                if (Character.charCount(c0) == o.length() && isHan(c0)) r = c0;
            }
        } catch (Throwable ignored) {
            // 转换表用不了:原样
        }
        if (normCache.size() > 30_000) normCache.clear();
        normCache.put(cp, r);
        return r;
    }

    /** 是不是汉字(含生僻字、扩展区;标点、假名、字母、数字都不是)。 */
    public static boolean isHan(int cp) {
        if (cp < 0x2E80) return false;   // 常见的非汉字(字母、数字、拉丁标点)一眼排除,省得查脚本表
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        try {
            SafeFile.Loaded r = SafeFile.read(file);
            if (r != null) {
                for (String line : r.lines) {
                    String[] p = line.split("\t", -1);
                    if (p.length != 3 || !p[0].equals("H")) continue;
                    int raw = single(p[1]);
                    long ts = parseTs(p[2]);
                    if (raw < 0 || ts < 1) continue;
                    int cp = canon(raw);
                    if (cp != raw) dirty = true;   // 文件里还存着繁体:归并后重新存一遍
                    Long old = map.get(cp);
                    if (old == null && map.size() >= MAX_CHARS) continue;
                    if (old == null || old < ts) map.put(cp, ts);
                }
                if (r.fromBackup) dirty = true;
            } else if (file.isFile()) {
                ErrorHint.INSTANCE.set("learn", "字集文件已损坏且没有可用备份,原文件另存为 " + file.getName() + ".corrupt");
            }
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("字集读取失败", e));
        } finally {
            loaded = true;
        }
    }

    private static long parseTs(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 字符串恰好是一个汉字就返回它的码点,否则 -1。 */
    private static int single(String s) {
        if (s == null || s.isEmpty()) return -1;
        int cp = s.codePointAt(0);
        if (Character.charCount(cp) != s.length() || !isHan(cp)) return -1;
        return cp;
    }

    /** 把这段文字里的汉字记进来(时间取现在)。上屏时调。 */
    public synchronized void note(CharSequence text) {
        note(text, System.currentTimeMillis());
    }

    synchronized void note(CharSequence text, long now) {
        if (text == null || text.length() == 0) return;
        ensureLoaded();
        String s = text.toString();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            put(cp, now);
        }
    }

    /** 从学习表里补:这段文字里的汉字,时间用那条记录自己的时间(不晚于现在)。返回新增了几个字。 */
    public synchronized int absorb(String text, long ts, long now) {
        if (text == null || text.isEmpty()) return 0;
        ensureLoaded();
        long t = Math.min(Math.max(ts, 1L), now);
        int added = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!isHan(cp)) continue;
            cp = canon(cp);
            if (!map.containsKey(cp)) added++;
            put(cp, t);
        }
        return added;
    }

    /** 记一个字。新字直接收;已有的字,新时间比旧的晚一天以上才刷新。 */
    private void put(int cp, long ts) {
        if (!isHan(cp)) return;
        cp = canon(cp);
        Long old = map.get(cp);
        if (old == null) {
            if (map.size() >= MAX_CHARS) return;
            map.put(cp, ts);
            dirty = true;
        } else if (ts - old >= REFRESH_MS) {
            map.put(cp, ts);
            dirty = true;
        }
    }

    /** 不同的汉字有多少个。 */
    public synchronized int count() {
        ensureLoaded();
        return map.size();
    }

    // ------------------------------------------------------------------ 多机同步(见 LearnShare / LearnSync)

    /** 导出要共享给别的手机的行(H 行:字 + 最近时间)。不加锁(遍历弱一致),多一条少一条没关系。 */
    public void exportShared(Writer w) throws IOException {
        ensureLoaded();
        for (java.util.Map.Entry<Integer, Long> e : map.entrySet()) {
            w.write("H\t" + new String(Character.toChars(e.getKey())) + "\t" + e.getValue() + "\n");
        }
    }

    public int mergeRows(List<String> lines, long now) {
        return mergeRows(lines, now, 0L);
    }

    /**
     * 合并别的手机导出的 H 行:同一个字取较晚的时间。顺序无关,重复合并结果不变。
     * minTs:早于它的行不收(这台手机清除过学习数据,清除之前的记录不能被别的手机再同步回来)。
     * 时间超过 now 的截成 now。返回新增或刷新了几个字。
     */
    public synchronized int mergeRows(List<String> lines, long now, long minTs) {
        ensureLoaded();
        int changed = 0;
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length != 3 || !p[0].equals("H")) continue;
            int cp = single(p[1]);
            long ts = parseTs(p[2]);
            if (cp < 0 || ts < Math.max(minTs, 1L)) continue;
            cp = canon(cp);   // 对方发来的可能是繁体(老版本),统一成简体再合并
            ts = Math.min(ts, now);
            Long old = map.get(cp);
            if (old == null) {
                if (map.size() >= MAX_CHARS) continue;
                map.put(cp, ts);
                dirty = true;
                changed++;
            } else if (old < ts) {
                map.put(cp, ts);
                dirty = true;
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ 存盘

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        try {
            List<String> rows = new ArrayList<>(map.size());
            for (java.util.Map.Entry<Integer, Long> e : map.entrySet()) {
                rows.add("H\t" + new String(Character.toChars(e.getKey())) + "\t" + e.getValue() + "\n");
            }
            SafeFile.write(file, w -> {
                for (String r : rows) w.write(r);
            });
            dirty = false;
            ErrorHint.INSTANCE.clear("learn");
        } catch (Exception e) {
            ErrorHint.INSTANCE.set("learn", ErrorHint.INSTANCE.of("字集保存失败", e));
        }
    }

    public synchronized void clear() {
        loaded = true;
        map.clear();
        dirty = false;
        SafeFile.deleteAll(file);
    }
}
