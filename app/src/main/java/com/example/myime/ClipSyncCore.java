package com.example.myime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 剪贴板多设备联动的核心。纯 JDK 代码(不依赖 Android),可以脱离手机和假的 WebDAV 服务器一起测试(见 tools/ClipSyncSelfTest.java、
 * tools/ClipSyncE2E.java)。什么时候同步、省电判断、口令和密钥、设置存取、主线程切换,在 ClipSync.kt 里。
 *
 * <h3>数据模型:每条剪贴板内容一条记录(Rec)</h3>
 * id = 文字的 SHA-256 前 8 字节(16 位十六进制);st = 状态:a 正常 / l 上了锁 / t 在回收箱 / x 已彻底删除(只留 id 和时间,不留文字);
 * ot = 排序时间(列表里谁在前);mt = 最后修改时间。
 * 合并:同一条内容,谁的 mt 大听谁的;mt 一样时 x &gt; t &gt; l &gt; a。这样删除、恢复、上锁、挪到最前、清空都是普通的"状态变化",
 * 没有特殊规则,几台设备按什么顺序读到都会得到同一个结果(顺序无关、重复读也无害)。
 *
 * <h3>云端:每台设备一份快照 + 一串增量,只写自己的文件,从不改别人的</h3>
 *   s-设备ID-序号.snp   快照:这台设备此刻"窗口内"的全部记录(最近 WINDOW 条 + 上锁的 + 回收箱 + 最近 30 天的删除记录)。序号 = 它覆盖到的增量序号。
 *   d-设备ID-序号.dlt   增量:这台设备自上次上传以来改动过的记录(很小)。序号每次上传 +1。
 * 第一次(云端还没有本机快照)传整份快照;之后每次只传增量;增量攒到 COMPACT_AT 个就并成一份新快照、删掉自己的旧文件。
 * 拉取:列一次目录 → 每台别的设备只下载自己还没收过的增量(游标 = 已经收到的最大序号);游标落后于快照、或增量中间缺号
 * (对方并过快照了)就读快照。文件名里带序号,所以没有新内容时只列目录,不下载任何东西。
 * 所有文件都是 gzip + AES-256-GCM(BackupCrypto)。解不开的文件(口令不一致、损坏、格式太新)跳过,记下来不反复重试。
 */
public final class ClipSyncCore {
    public static final String SUBFOLDER = "clip";
    public static final int WINDOW = 200;                       // 快照里放最近多少条(上了锁的、回收箱的另算)
    public static final int MAX_TOMBS = 1000;                   // 快照里最多放多少条删除记录
    public static final long TOMB_KEEP_MS = 30L * 24 * 3600 * 1000;   // 删除记录保留 30 天(更久没上线的设备,删掉的条目可能再冒出来)
    public static final int COMPACT_AT = 20;                    // 本机增量攒到这么多个就并成新快照
    private static final long FUTURE_SKEW_MS = 10L * 60 * 1000; // 别的设备的时间比现在晚超过 10 分钟的,按"现在 + 10 分钟"算
    private static final int MAX_TEXT = 200_000;                // 远端来的单条文字上限(防止被构造的文件撑爆内存)

    private ClipSyncCore() {
    }

    // ------------------------------------------------------------ 记录

    /** 一条剪贴板内容的同步记录。 */
    public static final class Rec {
        public final String id;
        public String text;   // st == 'x' 时为 null
        public char st;
        public long ot;
        public long mt;
        public boolean dirty;   // 本机改过、还没上传

        Rec(String id, String text, char st, long ot, long mt, boolean dirty) {
            this.id = id;
            this.text = text;
            this.st = st;
            this.ot = ot;
            this.mt = mt;
            this.dirty = dirty;
        }

        Rec copy() {
            return new Rec(id, text, st, ot, mt, dirty);
        }
    }

    /** 界面上那三份列表:剪贴板(新的在前)、上了锁的、回收箱(新删的在前)。 */
    public static final class Lists {
        public final List<String> clips;
        public final Set<String> locked;
        public final List<String> trash;

        public Lists(List<String> clips, Set<String> locked, List<String> trash) {
            this.clips = clips;
            this.locked = locked;
            this.trash = trash;
        }

        boolean sameAs(Lists o) {
            return clips.equals(o.clips) && trash.equals(o.trash) && locked.equals(o.locked);
        }
    }

    public static String idOf(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append("0123456789abcdef".charAt((h[i] >> 4) & 15)).append("0123456789abcdef".charAt(h[i] & 15));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int rank(char st) {
        switch (st) {
            case 'x':
                return 3;
            case 't':
                return 2;
            case 'l':
                return 1;
            default:
                return 0;
        }
    }

    private static boolean validSt(char c) {
        return c == 'a' || c == 'l' || c == 't' || c == 'x';
    }

    /** a 比 b 新吗(mt 大;一样大时 x &gt; t &gt; l &gt; a)。 */
    private static boolean newer(char stA, long mtA, char stB, long mtB) {
        return mtA > mtB || (mtA == mtB && rank(stA) > rank(stB));
    }

    // ------------------------------------------------------------ 模型

    /** 所有记录。只在一个线程里用(Android 那边切到主线程,见 Session)。 */
    public static final class Model {
        private final LinkedHashMap<String, Rec> recs = new LinkedHashMap<>();
        private long version = 0;
        // 文字 → id 的缓存:一次同步会对账好几次,不用每次都把所有文字重新算一遍 SHA-256(省电)。按字数限制,超了清空重来,不会长期占着大块内存。
        private final HashMap<String, String> ids = new HashMap<>();
        private long idChars = 0;

        private String idFor(String t) {
            String id = ids.get(t);
            if (id != null) return id;
            idChars += t.length();
            if (idChars > 600_000) {
                ids.clear();
                idChars = t.length();
            }
            id = idOf(t);
            ids.put(t, id);
            return id;
        }

        /** 每次内容有变化 +1;保存本地记录时用它判断要不要写。 */
        public long version() {
            return version;
        }

        public int size() {
            return recs.size();
        }

        public Rec get(String id) {
            return recs.get(id);
        }

        private static long stamp(Rec r, long now) {
            r.mt = Math.max(now, r.mt + 1);   // 时钟往回走也保证同一条的修改时间只增不减
            return r.mt;
        }

        /**
         * 把界面上现在的三份列表对账进模型:谁是新的、谁换了状态(删了 / 恢复了 / 上锁了)、谁被挪到了最前,都记成本机改动(dirty)。
         * 返回是否有变化。不需要知道用户做了哪些操作,只看结果,所以以后界面上新加操作也不用改这里。
         */
        public boolean reconcile(List<String> clips, Set<String> locked, List<String> trash, long now) {
            boolean changed = false;
            Set<String> seen = new HashSet<>();
            // 剪贴板:从最旧的往最新走,排序时间应该不减;谁比它下面(更旧)那条的排序时间还小,就是被挪到前面来了
            long prev = Long.MIN_VALUE;
            for (int i = clips.size() - 1; i >= 0; i--) {
                String t = clips.get(i);
                String id = idFor(t);
                if (!seen.add(id)) continue;
                char want = locked.contains(t) ? 'l' : 'a';
                Rec r = recs.get(id);
                long front = prev == Long.MIN_VALUE ? now : Math.max(now, prev + 1);
                if (r == null) {
                    r = new Rec(id, t, want, front, now, true);
                    recs.put(id, r);
                    changed = true;
                    prev = r.ot;
                    continue;
                }
                boolean move = false;
                if (r.st != want) {
                    move = r.st == 't' || r.st == 'x';   // 从回收箱恢复 / 删掉后又复制了一次:当作新的放到最前
                    r.st = want;
                    r.text = t;
                    stamp(r, now);
                    r.dirty = true;
                    changed = true;
                }
                if (r.text == null) r.text = t;
                if (prev != Long.MIN_VALUE && r.ot < prev) move = true;
                if (move) {
                    r.ot = front;
                    stamp(r, now);
                    r.dirty = true;
                    changed = true;
                }
                prev = r.ot;
            }
            // 回收箱:列表里新的在前,从最旧的往新走,新进来的修改时间递增,这样按 mt 倒序就是原来的顺序
            long tprev = Long.MIN_VALUE;
            for (int i = trash.size() - 1; i >= 0; i--) {
                String t = trash.get(i);
                String id = idFor(t);
                if (!seen.add(id)) continue;   // 同时在剪贴板里的,以剪贴板为准
                Rec r = recs.get(id);
                if (r == null) {
                    long mt = tprev == Long.MIN_VALUE ? now : Math.max(now, tprev + 1);
                    r = new Rec(id, t, 't', now, mt, true);
                    recs.put(id, r);
                    changed = true;
                } else if (r.st != 't') {
                    r.st = 't';
                    r.text = t;
                    long mt = tprev == Long.MIN_VALUE ? now : Math.max(now, tprev + 1);
                    r.mt = Math.max(mt, r.mt + 1);
                    r.dirty = true;
                    changed = true;
                } else if (r.text == null) {
                    r.text = t;
                }
                tprev = r.mt;
            }
            // 模型里有、界面上两处都没有的:被彻底删了(回收箱满了挤掉的、回收箱里删的、超过上限挤掉的)
            for (Rec r : recs.values()) {
                if (r.st != 'x' && !seen.contains(r.id)) {
                    r.st = 'x';
                    r.text = null;
                    stamp(r, now);
                    r.dirty = true;
                    changed = true;
                }
            }
            if (changed) version++;
            return changed;
        }

        /** 并入别的设备的记录。返回有多少条因此发生了变化。 */
        public int merge(Collection<Rec> remote, long now) {
            int changed = 0;
            long cap = now + FUTURE_SKEW_MS;
            for (Rec in : remote) {
                if (!validSt(in.st)) continue;
                if (in.st != 'x' && (in.text == null || !in.id.equals(idOf(in.text)))) continue;
                long ot = Math.min(in.ot, cap);
                long mt = Math.min(in.mt, cap);
                Rec r = recs.get(in.id);
                if (r == null) {
                    if (in.st == 'x' && mt < now - TOMB_KEEP_MS) continue;   // 太老的删除记录用不着
                    recs.put(in.id, new Rec(in.id, in.st == 'x' ? null : in.text, in.st, ot, mt, false));
                    changed++;
                    continue;
                }
                if (!newer(in.st, mt, r.st, r.mt)) continue;
                boolean diff = r.st != in.st || r.ot != ot || r.mt != mt;
                r.st = in.st;
                r.ot = ot;
                r.mt = mt;
                r.text = in.st == 'x' ? null : in.text;
                r.dirty = false;   // 对方的更新,本机之前没传出去的改动已经过时了
                if (diff) changed++;
            }
            if (changed > 0) version++;
            return changed;
        }

        /**
         * 由模型生成界面要的三份列表,同时执行上限:剪贴板最多 maxItems 条、总字数不超过 maxChars(上了锁的不挤),
         * 回收箱最多 maxTrash 条;超出的变成"已删除"(不算本机改动、不上传:各设备用同一套规则,结果一样);过期的删除记录丢掉。
         */
        public Lists materialize(int maxItems, int maxChars, int maxTrash, long now) {
            List<Rec> act = new ArrayList<>();
            List<Rec> tr = new ArrayList<>();
            List<Rec> tombs = new ArrayList<>();
            for (Rec r : recs.values()) {
                if (r.st == 'x') tombs.add(r);
                else if (r.text == null) continue;
                else if (r.st == 't') tr.add(r);
                else act.add(r);
            }
            Collections.sort(act, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    int c = Long.compare(b.ot, a.ot);
                    return c != 0 ? c : a.id.compareTo(b.id);
                }
            });
            Collections.sort(tr, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    int c = Long.compare(b.mt, a.mt);
                    return c != 0 ? c : a.id.compareTo(b.id);
                }
            });
            List<String> clips = new ArrayList<>();
            Set<String> locked = new LinkedHashSet<>();
            long chars = 0;
            boolean dropped = false;
            for (Rec r : act) {
                boolean keep = r.st == 'l' || (clips.size() < maxItems && (clips.isEmpty() || chars + r.text.length() <= maxChars));
                if (keep) {
                    clips.add(r.text);
                    chars += r.text.length();
                    if (r.st == 'l') locked.add(r.text);
                } else {
                    r.st = 'x';
                    r.text = null;
                    dropped = true;
                }
            }
            List<String> trash = new ArrayList<>();
            for (Rec r : tr) {
                if (trash.size() < maxTrash) {
                    trash.add(r.text);
                } else {
                    r.st = 'x';
                    r.text = null;
                    dropped = true;
                }
            }
            // 过期的、太多的删除记录
            Collections.sort(tombs, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    return Long.compare(b.mt, a.mt);
                }
            });
            int keepTombs = 0;
            for (Rec r : tombs) {
                if (r.st != 'x') continue;
                if (r.mt < now - TOMB_KEEP_MS || keepTombs >= MAX_TOMBS) {
                    recs.remove(r.id);
                    dropped = true;
                } else {
                    keepTombs++;
                }
            }
            if (dropped) version++;
            return new Lists(clips, locked, trash);
        }

        public boolean hasDirty() {
            for (Rec r : recs.values()) if (r.dirty) return true;
            return false;
        }

        /** 本机改过、还没上传的记录(拷贝),按修改时间从旧到新。 */
        public List<Rec> takeDirty() {
            List<Rec> out = new ArrayList<>();
            for (Rec r : recs.values()) if (r.dirty) out.add(r.copy());
            Collections.sort(out, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    return Long.compare(a.mt, b.mt);
                }
            });
            return out;
        }

        /** 上传成功后:这些记录(拷贝时的样子)不再算"待上传";上传期间又改过的(mt 变了)继续算。 */
        public void clearDirty(Collection<Rec> sent) {
            boolean ch = false;
            for (Rec s : sent) {
                Rec r = recs.get(s.id);
                if (r != null && r.dirty && r.mt == s.mt && r.st == s.st) {
                    r.dirty = false;
                    ch = true;
                }
            }
            if (ch) version++;
        }

        /** 快照用的记录:最近 maxItems 条正常的 + 所有上了锁的 + 回收箱的 + 最近的删除记录(拷贝)。 */
        public List<Rec> window(int maxItems, long now) {
            List<Rec> act = new ArrayList<>();
            List<Rec> out = new ArrayList<>();
            List<Rec> tombs = new ArrayList<>();
            for (Rec r : recs.values()) {
                if (r.st == 'a' && r.text != null) act.add(r);
                else if ((r.st == 'l' || r.st == 't') && r.text != null) out.add(r.copy());
                else if (r.st == 'x' && r.mt >= now - TOMB_KEEP_MS) tombs.add(r);
            }
            Collections.sort(act, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    return Long.compare(b.ot, a.ot);
                }
            });
            for (int i = 0; i < act.size() && i < maxItems; i++) out.add(act.get(i).copy());
            Collections.sort(tombs, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    return Long.compare(b.mt, a.mt);
                }
            });
            for (int i = 0; i < tombs.size() && i < MAX_TOMBS; i++) out.add(tombs.get(i).copy());
            return out;
        }

        // ---- 本机保存:只存状态和时间,不存文字(文字在剪贴板文件里,已经加密保存)

        public void writeMeta(Writer w) throws IOException {
            for (Rec r : recs.values()) {
                w.write("M\t" + r.id + "\t" + r.st + "\t" + r.ot + "\t" + r.mt + "\t" + (r.dirty ? "1" : "0") + "\n");
            }
        }

        /** 读回本机记录;格式不对的行跳过。texts:界面三份列表里所有文字,用来把文字补回正常 / 上锁 / 回收箱的记录,找不到文字的记录丢掉。 */
        public static Model readMeta(List<String> lines, Collection<String> texts) {
            Model m = new Model();
            Map<String, String> byId = new HashMap<>();
            for (String t : texts) byId.put(idOf(t), t);
            for (String line : lines) {
                String[] p = line.split("\t", -1);
                if (p.length != 6 || !p[0].equals("M") || p[2].length() != 1 || !validSt(p[2].charAt(0))) continue;
                try {
                    char st = p[2].charAt(0);
                    long ot = Long.parseLong(p[3]);
                    long mt = Long.parseLong(p[4]);
                    String text = st == 'x' ? null : byId.get(p[1]);
                    if (st != 'x' && text == null) continue;
                    m.recs.put(p[1], new Rec(p[1], text, st, ot, mt, p[5].equals("1")));
                } catch (NumberFormatException e) {
                    // 跳过坏行
                }
            }
            return m;
        }
    }

    // ------------------------------------------------------------ 在"界面所在线程"里原子地读写模型

    /** 界面那边(Android 上是服务,主线程):读出现在的三份列表 / 换成新的三份。只在 Session.atomically 里被调用。 */
    public interface Host {
        Lists lists();

        void apply(Lists l);
    }

    public interface Op<T> {
        T run(Model m) throws IOException;
    }

    /** 同步流程用它读写模型。Android 上整个 atomically 切到主线程执行,所以界面列表和模型不会互相踩。 */
    public interface Store {
        <T> T atomically(Op<T> op) throws IOException;
    }

    /** Store 的标准实现:对账 → 执行 → 生成列表,列表变了就交给界面。线程由调用方保证(Android 上包一层主线程切换)。 */
    public static final class Session implements Store {
        private final Model model;
        private final Host host;
        private final int maxItems;
        private final int maxChars;
        private final int maxTrash;
        private final LongSupplier clock;

        public Session(Model model, Host host, int maxItems, int maxChars, int maxTrash, LongSupplier clock) {
            this.model = model;
            this.host = host;
            this.maxItems = maxItems;
            this.maxChars = maxChars;
            this.maxTrash = maxTrash;
            this.clock = clock;
        }

        public Model model() {
            return model;
        }

        @Override
        public <T> T atomically(Op<T> op) throws IOException {
            long now = clock.getAsLong();
            Lists cur = host.lists();
            model.reconcile(cur.clips, cur.locked, cur.trash, now);
            T r = op.run(model);
            Lists next = model.materialize(maxItems, maxChars, maxTrash, now);
            if (!next.sameAs(cur)) host.apply(next);
            return r;
        }
    }

    // ------------------------------------------------------------ 文件名

    private static final Pattern NAME = Pattern.compile("^([sd])-([0-9a-f]{1,32})-([0-9]{1,15})\\.(snp|dlt)$");

    static String snapName(String dev, long seq) {
        return "s-" + dev + "-" + seq + ".snp";
    }

    static String deltaName(String dev, long seq) {
        return "d-" + dev + "-" + seq + ".dlt";
    }

    private static final class Named {
        final boolean snap;
        final String dev;
        final long seq;

        Named(boolean snap, String dev, long seq) {
            this.snap = snap;
            this.dev = dev;
            this.seq = seq;
        }
    }

    private static Named parseName(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) return null;
        boolean snap = m.group(1).equals("s");
        if (snap != m.group(4).equals("snp")) return null;
        return new Named(snap, m.group(2), Long.parseLong(m.group(3)));
    }

    /** 一台设备在云端的文件。 */
    private static final class Dev {
        long snapSeq = -1;
        final TreeMap<Long, String> snaps = new TreeMap<>();   // 序号 → 文件名(正常只会有一份)
        final TreeMap<Long, String> deltas = new TreeMap<>();

        long latest() {
            long l = snapSeq;
            if (!deltas.isEmpty()) l = Math.max(l, deltas.lastKey());
            return l;
        }

        String snapName() {
            return snaps.isEmpty() ? null : snaps.get(snaps.lastKey());
        }
    }

    // ------------------------------------------------------------ 文件内容

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') b.append("\\\\");
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else b.append(ch);
        }
        return b.toString();
    }

    private static String unesc(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                b.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n == 't' ? '\t' : n);
                i++;
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    private static final class Parsed {
        boolean snap;
        long seq = -1;
        final List<Rec> recs = new ArrayList<>();
    }

    static void writeRecs(Writer w, boolean snap, String dev, long seq, Collection<Rec> recs) throws IOException {
        w.write("#clipsync\t1\n");
        w.write("#kind\t" + (snap ? "snap" : "delta") + "\n");
        w.write("#dev\t" + dev + "\n");
        w.write("#seq\t" + seq + "\n");
        for (Rec r : recs) {
            w.write("R\t" + r.id + "\t" + r.st + "\t" + r.ot + "\t" + r.mt + "\t" + (r.st == 'x' || r.text == null ? "" : esc(r.text)) + "\n");
        }
        w.write("#end\n");
    }

    private static Parsed readRecs(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        Parsed p = new Parsed();
        String first = br.readLine();
        if (first == null || !first.startsWith("#clipsync\t")) throw new IOException("不是联动文件");
        if (!first.equals("#clipsync\t1")) throw new IOException("联动文件格式太新");
        boolean end = false;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("#kind\t")) {
                p.snap = line.endsWith("\tsnap");
            } else if (line.startsWith("#seq\t")) {
                try {
                    p.seq = Long.parseLong(line.substring(5));
                } catch (NumberFormatException e) {
                    throw new IOException("联动文件损坏");
                }
            } else if (line.equals("#end")) {
                end = true;
                break;
            } else if (line.startsWith("R\t")) {
                String[] f = line.split("\t", 6);
                if (f.length != 6 || f[2].length() != 1 || !validSt(f[2].charAt(0))) continue;
                try {
                    char st = f[2].charAt(0);
                    String text = st == 'x' ? null : unesc(f[5]);
                    if (text != null && (text.isEmpty() || text.length() > MAX_TEXT)) continue;
                    p.recs.add(new Rec(f[1], text, st, Long.parseLong(f[3]), Long.parseLong(f[4]), false));
                } catch (NumberFormatException e) {
                    // 跳过坏行
                }
            }
        }
        if (!end) throw new IOException("联动文件不完整");
        return p;
    }

    private static void encode(File dst, byte[] master, byte[] salt, boolean snap, String dev, long seq, Collection<Rec> recs) throws IOException {
        dst.delete();
        try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16)) {
            OutputStream enc = BackupCrypto.encrypt(fo, master, salt, BackupCrypto.MIN_ITERATIONS);
            GZIPOutputStream gz = new GZIPOutputStream(enc, 1 << 16) {
                {
                    def.setLevel(Deflater.BEST_SPEED);   // 省电:压缩用最快档
                }
            };
            try (Writer w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
                writeRecs(w, snap, dev, seq, recs);
            }
        }
        // 读回来整个解密 + 解压 + 解析一遍,确认真的能被别的设备读出来
        Parsed back = decode(dst, master);
        if (back.recs.size() != recs.size() || back.seq != seq) throw new IOException("读回校验失败:内容对不上");
    }

    private static Parsed decode(File src, byte[] master) throws IOException {
        try (InputStream fi = new BufferedInputStream(new FileInputStream(src), 1 << 16);
             InputStream dec = BackupCrypto.decryptWithMaster(fi, master);
             InputStream in = new GZIPInputStream(dec, 1 << 16)) {
            return readRecs(in);
        }
    }

    // ------------------------------------------------------------ 同步流程

    /** 同步状态的存取(Android 那边放在设备专属的设置里,不进备份)。 */
    public interface State {
        /** 已经收到这台设备的哪个序号为止;没收过返回 -1。 */
        long cursor(String dev);

        void setCursor(String dev, long seq);

        /** 这个云端文件以前解不开 / 读不了,跳过。 */
        boolean bad(String fileName);

        void markBad(String fileName);

        /** 本机上次上传用到的序号(0 = 还没传过)。 */
        long mySeq();

        void setMySeq(long seq);
    }

    public static final class Result {
        public final int filesRead;
        public final int filesSkipped;
        public final int changed;
        public final String pushed;   // "" 没传 / "delta" 传了增量 / "snap" 传了快照

        Result(int filesRead, int filesSkipped, int changed, String pushed) {
            this.filesRead = filesRead;
            this.filesSkipped = filesSkipped;
            this.changed = changed;
            this.pushed = pushed;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (filesRead > 0) sb.append("收到 ").append(filesRead).append(" 个更新文件(合并 ").append(changed).append(" 条)");
            else sb.append("别的设备没有新内容");
            if (filesSkipped > 0) sb.append(";").append(filesSkipped).append(" 个文件解不开,已跳过(联动口令是不是不一致?)");
            if (pushed.equals("delta")) sb.append(";已上传本机的新变化");
            else if (pushed.equals("snap")) sb.append(";已上传本机的完整快照");
            return sb.toString();
        }
    }

    /**
     * 做一次同步:先拉取合并,push 为 true 时再上传本机的新变化。网络 / 服务器出错抛 IOException(这次整体失败,下次再试)。
     * folder 是联动用的云端子文件夹(备份文件夹下的 clip)。
     */
    public static Result run(WebDavClient client, List<String> folder, String deviceId, byte[] master, byte[] salt,
                             Store store, File tmpDir, State state, boolean push, final long now) throws IOException {
        return run(client, folder, deviceId, master, salt, store, tmpDir, state, push, false, now);
    }

    /**
     * forceSnap = true:上传时一定传完整快照(换了联动口令、从备份还原之后用:云端本机的旧文件别人解不开或已经过时,要整份重传)。
     */
    public static Result run(WebDavClient client, List<String> folder, String deviceId, byte[] master, byte[] salt,
                             Store store, File tmpDir, State state, boolean push, boolean forceSnap, final long now) throws IOException {
        List<WebDavClient.Entry> entries = client.list(folder);
        Map<String, Dev> devs = new TreeMap<>();
        for (WebDavClient.Entry e : entries) {
            if (e.dir) continue;
            Named n = parseName(e.name);
            if (n == null) continue;
            Dev d = devs.get(n.dev);
            if (d == null) {
                d = new Dev();
                devs.put(n.dev, d);
            }
            if (n.snap) {
                d.snaps.put(n.seq, e.name);
                d.snapSeq = Math.max(d.snapSeq, n.seq);
            } else {
                d.deltas.put(n.seq, e.name);
            }
        }

        // ---- 拉取
        final List<Rec> incoming = new ArrayList<>();
        Map<String, Long> newCursor = new LinkedHashMap<>();
        int read = 0;
        int skipped = 0;
        File dl = new File(tmpDir, "clipsync_download.tmp");
        try {
            for (Map.Entry<String, Dev> de : devs.entrySet()) {
                String dev = de.getKey();
                if (dev.equals(deviceId)) continue;
                Dev v = de.getValue();
                long latest = v.latest();
                long cur = state.cursor(dev);
                if (cur >= latest) continue;
                // 从游标往后的增量一个不缺才能只读增量;缺了(对方并过快照、删掉了旧增量)就得读快照
                boolean contiguous = v.deltas.subMap(cur + 1, true, latest, true).size() == latest - cur;
                boolean needSnap = v.snapName() != null && (cur < v.snapSeq || !contiguous);
                boolean incomplete = false;
                long from = cur;
                if (needSnap) {
                    String sn = v.snapName();
                    if (!state.bad(sn)) {
                        int r = fetch(client, folder, sn, dl, master, incoming, state);
                        if (r == 1) read++;
                        else if (r == 0) skipped++;
                        else incomplete = true;
                    }
                    from = v.snapSeq;
                }
                for (Map.Entry<Long, String> d : v.deltas.entrySet()) {
                    if (d.getKey() <= from) continue;
                    if (state.bad(d.getValue())) continue;
                    int r = fetch(client, folder, d.getValue(), dl, master, incoming, state);
                    if (r == 1) read++;
                    else if (r == 0) skipped++;
                    else incomplete = true;
                }
                if (!incomplete) newCursor.put(dev, latest);
            }
        } finally {
            dl.delete();
        }
        int changed = 0;
        if (!incoming.isEmpty()) {
            changed = store.atomically(new Op<Integer>() {
                @Override
                public Integer run(Model m) {
                    return m.merge(incoming, now);
                }
            });
        }
        for (Map.Entry<String, Long> c : newCursor.entrySet()) state.setCursor(c.getKey(), c.getValue());

        // ---- 上传
        String pushed = "";
        if (push) {
            final List<Rec> dirty = store.atomically(new Op<List<Rec>>() {
                @Override
                public List<Rec> run(Model m) {
                    return m.takeDirty();
                }
            });
            Dev own = devs.get(deviceId);
            boolean haveSnap = own != null && own.snapName() != null;
            if (!dirty.isEmpty() || !haveSnap || forceSnap) {
                long seq = state.mySeq() + 1;
                if (own != null && own.latest() >= seq) seq = own.latest() + 1;   // 本机的序号记录丢了 / 回退了:接着云端已有的往后排
                boolean snap = forceSnap || !haveSnap || own.deltas.size() + 1 >= COMPACT_AT;
                File up = new File(tmpDir, "clipsync_upload.tmp");
                try {
                    if (entries.isEmpty()) client.ensureFolder(folder);
                    if (snap) {
                        List<Rec> win = store.atomically(new Op<List<Rec>>() {
                            @Override
                            public List<Rec> run(Model m) {
                                return m.window(WINDOW, now);
                            }
                        });
                        encode(up, master, salt, true, deviceId, seq, win);
                        put(client, folder, snapName(deviceId, seq), up);
                        // 新快照已经覆盖了之前所有的增量:删掉自己的旧文件(删不掉不要紧,下次再清,别人读到旧文件也无害)
                        if (own != null) {
                            for (String n : own.snaps.values()) quietDelete(client, folder, n);
                            for (String n : own.deltas.values()) quietDelete(client, folder, n);
                        }
                    } else {
                        encode(up, master, salt, false, deviceId, seq, dirty);
                        put(client, folder, deltaName(deviceId, seq), up);
                    }
                } finally {
                    up.delete();
                }
                state.setMySeq(seq);
                store.atomically(new Op<Object>() {
                    @Override
                    public Object run(Model m) {
                        m.clearDirty(dirty);
                        return null;
                    }
                });
                pushed = snap ? "snap" : "delta";
            }
        }
        return new Result(read, skipped, changed, pushed);
    }

    /** 下载并解开一个云端文件,把里面的记录放进 out。返回 1 = 读到了,0 = 解不开(已标记跳过),-1 = 文件刚被删掉了(这台设备这次先别推进游标)。 */
    private static int fetch(WebDavClient client, List<String> folder, String name, File dl, byte[] master, List<Rec> out, State state) throws IOException {
        dl.delete();
        try {
            client.get(folder, name, dl);
        } catch (WebDavClient.DavException ex) {
            if (ex.code == 404) return -1;
            throw ex;
        }
        try {
            Parsed p = decode(dl, master);
            out.addAll(p.recs);
            return 1;
        } catch (IOException ex) {
            state.markBad(name);   // 解不开:跳过,不反复下载;口令改了以后游标和这些标记会一起清掉
            return 0;
        }
    }

    private static void put(WebDavClient client, List<String> folder, String name, File f) throws IOException {
        try {
            client.put(folder, name, f);
        } catch (WebDavClient.DavException ex) {
            if (ex.code != 404 && ex.code != 409) throw ex;
            client.ensureFolder(folder);   // 文件夹不见了:建好再传一次
            client.put(folder, name, f);
        }
    }

    private static void quietDelete(WebDavClient client, List<String> folder, String name) {
        try {
            client.delete(folder, name);
        } catch (IOException ignored) {
            // 删不掉就留着
        }
    }
}
