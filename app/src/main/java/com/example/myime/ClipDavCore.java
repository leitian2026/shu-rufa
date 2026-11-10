package com.example.myime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剪贴板联动的备用通道:坚果云(WebDAV)。纯 JDK,有自测(tools/ClipDavE2E.java);什么时候同步、开关、设置在 ClipSync.kt。
 * 0.40 到 0.42 版里它是唯一的通道,0.43.0 起主通道换成 Cloudflare Worker(ClipRelayCore),0.45.0 起它作为备用通道回来,两条通道可以同时开着。
 *
 * 两条通道传的是同一种记录(ClipSyncCore.Rec),合并是"谁的修改时间大听谁的、重复读无害",所以同时开着不会重复或冲突;
 * 各记各的"还没传出去的改动"(Rec.dirty 给 Worker,Rec.dirty2 给坚果云),Worker 发完了不影响坚果云这边照样传。
 *
 * <h3>云端:备份文件夹下的 clip 子文件夹,每台设备一份快照 + 一串增量,只写自己的文件,从不改别人的</h3>
 *   s-设备ID-序号.snp   快照:这台设备此刻"窗口内"的全部记录(最近 WINDOW 条 + 上了锁的 + 回收箱 + 最近 30 天的删除记录)。序号 = 它覆盖到的增量序号。
 *   d-设备ID-序号.dlt   增量:这台设备自上次上传以来改动过的记录(很小)。序号每次上传 +1。
 * 第一次(云端还没有本机快照)传整份快照;之后每次只传增量;增量攒到 COMPACT_AT 个就并成一份新快照、删掉自己的旧文件。
 * 拉取:列一次目录 → 每台别的设备只下载自己还没收过的增量(游标 = 已经收到的最大序号);游标落后于快照、或增量中间缺号
 * (对方并过快照了)就读快照。文件名里带序号,所以没有新内容时只列目录,不下载任何东西。
 * 所有文件都是 gzip + AES-256-GCM(ClipSyncCore.encode / decode,格式和 0.40~0.42 的文件一样,老文件还能读)。
 * 解不开的文件(密钥不一致、损坏、格式太新)跳过,记下来不反复重试。
 */
public final class ClipDavCore {
    public static final String SUBFOLDER = "clip";
    public static final int COMPACT_AT = 20;   // 本机增量攒到这么多个就并成新快照

    private ClipDavCore() {
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
            if (filesSkipped > 0) sb.append(";").append(filesSkipped).append(" 个文件解不开,已跳过(别的设备装的是不是同一个构建?)");
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
                             ClipSyncCore.Store store, File tmpDir, State state, boolean push, final long now) throws IOException {
        return run(client, folder, deviceId, master, salt, store, tmpDir, state, push, false, now);
    }

    /**
     * forceSnap = true:上传时一定传完整快照(从备份还原之后用:云端本机的旧文件已经过时,要整份重传)。
     */
    public static Result run(WebDavClient client, List<String> folder, String deviceId, byte[] master, byte[] salt,
                             ClipSyncCore.Store store, File tmpDir, State state, boolean push, boolean forceSnap, final long now) throws IOException {
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
        final List<ClipSyncCore.Rec> incoming = new ArrayList<>();
        Map<String, Long> newCursor = new LinkedHashMap<>();
        int read = 0;
        int skipped = 0;
        File dl = new File(tmpDir, "clipdav_download.tmp");
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
            changed = store.atomically(new ClipSyncCore.Op<Integer>() {
                @Override
                public Integer run(ClipSyncCore.Model m) {
                    return m.merge(incoming, now);
                }
            });
        }
        for (Map.Entry<String, Long> c : newCursor.entrySet()) state.setCursor(c.getKey(), c.getValue());

        // ---- 上传
        String pushed = "";
        if (push) {
            final List<ClipSyncCore.Rec> dirty = store.atomically(new ClipSyncCore.Op<List<ClipSyncCore.Rec>>() {
                @Override
                public List<ClipSyncCore.Rec> run(ClipSyncCore.Model m) {
                    return m.takeDirty2();
                }
            });
            Dev own = devs.get(deviceId);
            boolean haveSnap = own != null && own.snapName() != null;
            if (!dirty.isEmpty() || !haveSnap || forceSnap) {
                long seq = state.mySeq() + 1;
                if (own != null && own.latest() >= seq) seq = own.latest() + 1;   // 本机的序号记录丢了 / 回退了:接着云端已有的往后排
                boolean snap = forceSnap || !haveSnap || own.deltas.size() + 1 >= COMPACT_AT;
                File up = new File(tmpDir, "clipdav_upload.tmp");
                try {
                    if (entries.isEmpty()) client.ensureFolder(folder);
                    if (snap) {
                        List<ClipSyncCore.Rec> win = store.atomically(new ClipSyncCore.Op<List<ClipSyncCore.Rec>>() {
                            @Override
                            public List<ClipSyncCore.Rec> run(ClipSyncCore.Model m) {
                                return m.window(ClipSyncCore.WINDOW, now);
                            }
                        });
                        writeFile(up, ClipSyncCore.encode(master, salt, true, deviceId, seq, win));
                        put(client, folder, snapName(deviceId, seq), up);
                        // 新快照已经覆盖了之前所有的增量:删掉自己的旧文件(删不掉不要紧,下次再清,别人读到旧文件也无害)
                        if (own != null) {
                            for (String n : own.snaps.values()) quietDelete(client, folder, n);
                            for (String n : own.deltas.values()) quietDelete(client, folder, n);
                        }
                    } else {
                        writeFile(up, ClipSyncCore.encode(master, salt, false, deviceId, seq, dirty));
                        put(client, folder, deltaName(deviceId, seq), up);
                    }
                } finally {
                    up.delete();
                }
                state.setMySeq(seq);
                store.atomically(new ClipSyncCore.Op<Object>() {
                    @Override
                    public Object run(ClipSyncCore.Model m) {
                        m.clearDirty2(dirty);
                        return null;
                    }
                });
                pushed = snap ? "snap" : "delta";
            }
        }
        return new Result(read, skipped, changed, pushed);
    }

    /** 下载并解开一个云端文件,把里面的记录放进 out。返回 1 = 读到了,0 = 解不开(已标记跳过),-1 = 文件刚被删掉了(这台设备这次先别推进游标)。 */
    private static int fetch(WebDavClient client, List<String> folder, String name, File dl, byte[] master, List<ClipSyncCore.Rec> out, State state) throws IOException {
        dl.delete();
        try {
            client.get(folder, name, dl);
        } catch (WebDavClient.DavException ex) {
            if (ex.code == 404) return -1;
            throw ex;
        }
        try {
            ClipSyncCore.Decoded p = ClipSyncCore.decode(readFile(dl), master);
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

    private static void writeFile(File f, byte[] data) throws IOException {
        f.delete();
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(data);
        }
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(Math.max(f.length(), 16), 1 << 26));
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
