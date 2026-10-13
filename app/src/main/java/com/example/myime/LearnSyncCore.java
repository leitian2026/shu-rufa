package com.example.myime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 多台自己的手机同步词频学习数据的核心流程。纯 JDK 代码(不依赖 Android),可以脱离手机和假的 WebDAV 服务器一起测试
 * (见 tools/LearnSyncE2E.java)。什么时候同步、省电判断、密钥保管、设置存取,在 LearnSync.kt 里。
 *
 * 云端文件夹(和备份同一个)里,每台手机只写自己的一个文件 myime-learn-&lt;设备ID&gt;.lrn,从不改别人的:
 *   - 没有写冲突,不需要服务器和锁;
 *   - 拉取:列目录 → 别的手机的文件里,大小 / 修改时间 / 版本标记和上次合并时一样的跳过,不下载 → 其余的下载、解密、解压、合并;
 *   - 上传:把本机现在的状态(已经合并过别人的)写成 gzip + AES-256-GCM 加密文件,读回校验,上传,核对云端大小。
 * 解不开的文件(别的构建 / 不同的内置密码 / 格式太新 / 损坏)跳过,不报错、不删除,记下它的版本标记,它变了才再试。
 */
public final class LearnSyncCore {
    public static final String PREFIX = "myime-learn-";
    public static final String EXT = ".lrn";

    private LearnSyncCore() {
    }

    /** 同步状态的存取(Android 那边放在设备专属的设置里,不进备份)。 */
    public interface State {
        /** 上次处理这个远端文件时记下的版本标记;没有返回 null。 */
        String sig(String fileName);

        void putSig(String fileName, String sig);

        /** 这台手机清除学习数据的时间(毫秒);早于它的记录不收。没清除过返回 0。 */
        long floor();
    }

    public static final class Result {
        public final int filesMerged;     // 这次读了几台手机的文件
        public final int filesSkipped;    // 解不开 / 读不了而跳过的
        public final int changed;         // 合并后发生变化的条目数
        public final boolean pushed;

        Result(int filesMerged, int filesSkipped, int changed, boolean pushed) {
            this.filesMerged = filesMerged;
            this.filesSkipped = filesSkipped;
            this.changed = changed;
            this.pushed = pushed;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (filesMerged > 0) {
                sb.append("收到 ").append(filesMerged).append(" 台手机的更新");
                sb.append(changed > 0 ? "(合并 " + changed + " 条)" : "(没有新内容)");
            } else {
                sb.append("别的手机没有新内容");
            }
            if (filesSkipped > 0) sb.append(";").append(filesSkipped).append(" 个文件解不开,已跳过(可能是用别的构建装的)");
            if (pushed) sb.append(";已上传本机的学习数据");
            return sb.toString();
        }
    }

    /** 云端文件名 → 设备 ID;不是同步文件返回 null。 */
    static String deviceOf(String name) {
        if (!name.startsWith(PREFIX) || !name.endsWith(EXT)) return null;
        String id = name.substring(PREFIX.length(), name.length() - EXT.length());
        return id.isEmpty() ? null : id;
    }

    public static String fileNameOf(String deviceId) {
        return PREFIX + deviceId + EXT;
    }

    /**
     * 做一次同步:先拉取合并,push 为 true 时再上传合并后的结果。网络 / 服务器出错抛 IOException(这次整体失败,下次再试);
     * 单个远端文件解不开不算失败,跳过。flush:合并进来的数据先落盘(Android 那边传 UserData.saveAll),
     * 落盘之后才记下远端文件的版本标记 —— 不然进程被杀、数据没存住,却已经标记成\"合并过\",就再也不会重新合并了。
     */
    public static Result run(WebDavClient client, List<String> folder, String deviceId, byte[] master, byte[] salt,
                             UserFreq pinyin, UserFreq stroke, UserChain chain, File tmpDir, State state,
                             boolean push, long now, Runnable flush) throws IOException {
        String own = fileNameOf(deviceId);
        List<WebDavClient.Entry> entries = client.list(folder);

        int merged = 0;
        int skipped = 0;
        int changed = 0;
        long floor = state.floor();
        Map<String, String> sigs = new LinkedHashMap<>();
        File dl = new File(tmpDir, "learn_download.tmp");
        try {
            for (WebDavClient.Entry e : entries) {
                if (e.dir || e.name.equals(own) || deviceOf(e.name) == null) continue;
                String sig = (e.modified.isEmpty() && e.etag.isEmpty()) ? "" : e.size + "|" + e.modified + "|" + e.etag;
                if (!sig.isEmpty() && sig.equals(state.sig(e.name))) continue;   // 没变化,不下载
                dl.delete();
                try {
                    client.get(folder, e.name, dl);
                } catch (WebDavClient.DavException ex) {
                    if (ex.code == 404) continue;   // 列目录之后被对方删掉了
                    throw ex;
                }
                try (InputStream fi = new BufferedInputStream(new FileInputStream(dl), 1 << 16);
                     InputStream dec = BackupCrypto.decryptWithMaster(fi, master);
                     InputStream in = new GZIPInputStream(dec, 1 << 16)) {
                    changed += LearnShare.merge(in, pinyin, stroke, chain, now, floor);
                    merged++;
                } catch (IOException ex) {
                    skipped++;   // 解不开 / 太新 / 损坏:跳过
                }
                if (!sig.isEmpty()) sigs.put(e.name, sig);
            }
        } finally {
            dl.delete();
        }
        if (changed > 0 && flush != null) flush.run();
        for (Map.Entry<String, String> s : sigs.entrySet()) state.putSig(s.getKey(), s.getValue());

        boolean pushed = false;
        if (push) {
            upload(client, folder, own, master, salt, pinyin, stroke, chain, tmpDir);
            pushed = true;
        }
        return new Result(merged, skipped, changed, pushed);
    }

    private static void upload(WebDavClient client, List<String> folder, String own, byte[] master, byte[] salt,
                               UserFreq pinyin, UserFreq stroke, UserChain chain, File tmpDir) throws IOException {
        File up = new File(tmpDir, "learn_upload.tmp");
        try {
            up.delete();
            try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(up), 1 << 16)) {
                OutputStream enc = BackupCrypto.encrypt(fo, master, salt, BackupCrypto.MIN_ITERATIONS);
                GZIPOutputStream gz = new GZIPOutputStream(enc, 1 << 16) {
                    {
                        def.setLevel(Deflater.BEST_SPEED);   // 省电:压缩用最快档
                    }
                };
                try (Writer w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
                    LearnShare.write(w, pinyin, stroke, chain);
                }
            }
            // 读回来整个解密 + 解压一遍(每个块的认证标签都会校验),确认真的能被别的手机读出来
            try (InputStream fi = new BufferedInputStream(new FileInputStream(up), 1 << 16);
                 InputStream dec = BackupCrypto.decryptWithMaster(fi, master);
                 InputStream in = new GZIPInputStream(dec, 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                long total = 0;
                int r;
                while ((r = in.read(buf)) >= 0) total += r;
                if (total == 0) throw new IOException("读回校验失败:内容是空的");
            }
            client.ensureFolder(folder);
            client.put(folder, own, up);
            List<WebDavClient.Entry> after = client.list(folder);
            WebDavClient.Entry remote = null;
            for (WebDavClient.Entry e : after) if (e.name.equals(own)) remote = e;
            if (remote == null || remote.size != up.length()) {
                try {
                    client.delete(folder, own);   // 云端大小对不上,不留残缺文件
                } catch (IOException ignored) {
                    // 删不掉就留着,下次上传会覆盖它
                }
                throw new IOException("上传后核对失败:云端文件和本地不一致");
            }
        } finally {
            up.delete();
        }
    }
}
