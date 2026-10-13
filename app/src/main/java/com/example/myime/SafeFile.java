package com.example.myime;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习数据、固定词这类文本文件的安全读写。纯 JDK 代码(不依赖 Android),可以脱离手机单独测试。
 *
 * 写:先写 name.tmp 并刷到磁盘 → 主文件(确认完好时)复制一份成 name.bak → 用原子改名换上新文件。
 *     以前是"先删旧文件再改名",两步之间被杀进程主文件就没了;现在任何时刻主文件要么是旧的、要么是新的。
 * 读:主文件完好就用主文件;读不了(截断、坏字节、空文件)就把它另存成 name.corrupt(只留第一次的,不覆盖),
 *     再退回 name.bak;都不行返回 null(调用方当作没有数据)。
 * 格式:第一行 #v1(格式版本),最后一行 #end(用来发现被截断),中间是调用方自己的行。
 *     没有这两行的老文件(升级前写的)照常读;遇到比自己新的版本号,先把原文件另存成 name.vN 再继续读,
 *     免得旧版本把新版本多出来的内容悄悄覆盖掉。
 */
public final class SafeFile {
    public static final int VERSION = 1;
    private static final String TRAILER = "#end";

    private SafeFile() {
    }

    /**
     * 还原备份的过程中置 true:期间任何保存都直接失败,免得内存里的旧数据把刚还原好的文件又盖回去。
     * 还原成功后输入法进程会重启,所以不需要再置回 false;还原失败要置回 false。
     */
    private static volatile boolean frozen = false;

    /**
     * 本进程里自己刚写成功的文件(路径 → 长度、修改时间)。每次保存前不用再把整个主文件读一遍来确认它完好,
     * 长度和修改时间都没变就认为还是自己写的那份;省下一次整文件读取(省电、省时间)。
     */
    private static final java.util.Map<String, long[]> KNOWN = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean knownValid(File f) {
        long[] k = KNOWN.get(f.getPath());
        return k != null && k[0] == f.length() && k[1] == f.lastModified();
    }

    public static void setFrozen(boolean v) {
        frozen = v;
    }

    /** 调用方往里写自己的行(每行以 \n 结尾)。 */
    public interface Body {
        void write(Writer w) throws IOException;
    }

    /** 读出来的内容(不含 #v / #end 行)。fromBackup = 主文件不能用,这是从 .bak 恢复的,调用方应当标记"需要重新保存"。 */
    public static final class Loaded {
        public final List<String> lines;
        public final boolean fromBackup;

        Loaded(List<String> lines, boolean fromBackup) {
            this.lines = lines;
            this.fromBackup = fromBackup;
        }
    }

    public static File backupOf(File f) {
        return new File(f.getParentFile(), f.getName() + ".bak");
    }

    /** 读。没有任何可用的数据返回 null。 */
    public static Loaded read(File file) {
        if (file.isFile()) {
            try {
                return new Loaded(parse(file), false);
            } catch (IOException e) {
                if (file.length() > 0) keepCopy(file, ".corrupt");
            }
        }
        File bak = backupOf(file);
        if (bak.isFile()) {
            try {
                return new Loaded(parse(bak), true);
            } catch (IOException e) {
                // 备份也不能用:当作没有数据
            }
        }
        return null;
    }

    /** 写。失败抛 IOException,主文件保持原样,调用方不要把"已保存"标上。 */
    public static void write(File file, Body body) throws IOException {
        if (frozen) throw new IOException("正在还原备份,暂不保存");
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            Writer w = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            w.write("#v" + VERSION + "\n");
            body.write(w);
            w.write(TRAILER + "\n");
            w.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        // 只在现在的主文件确认完好时才更新备份,免得把一个坏文件复制过去、盖掉唯一的好备份
        if (file.isFile() && file.length() > 0 && (knownValid(file) || isValid(file))) {
            try {
                Files.copy(file.toPath(), backupOf(file).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 备份失败不影响这次保存
            }
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        KNOWN.put(file.getPath(), new long[]{file.length(), file.lastModified()});
    }

    /** 「清除」用:主文件、备份、临时文件、损坏留底都删掉,免得清除后又被备份恢复出来。 */
    public static void deleteAll(File file) {
        KNOWN.remove(file.getPath());
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        backupOf(file).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getParentFile(), file.getName() + ".tmp").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getParentFile(), file.getName() + ".corrupt").delete();
    }

    /** 这个文件是不是完好(头尾齐全、UTF-8 无坏字节)。不存在、空文件、被截断都算不完好。 */
    public static boolean isValid(File f) {
        try {
            parse(f);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> parse(File f) throws IOException {
        // 严格按 UTF-8 解码:有坏字节会抛 MalformedInputException(也是 IOException)
        List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        if (all.isEmpty()) throw new IOException("empty");
        String first = all.get(0);
        if (first.startsWith("#v")) {
            int v;
            try {
                v = Integer.parseInt(first.substring(2).trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad header");
            }
            if (!TRAILER.equals(all.get(all.size() - 1))) throw new IOException("truncated");
            if (v > VERSION) keepCopy(f, ".v" + v);
            return new ArrayList<>(all.subList(1, all.size() - 1));
        }
        return all; // 升级前的老文件:没有头尾,照常读
    }

    private static void keepCopy(File f, String suffix) {
        File dest = new File(f.getParentFile(), f.getName() + suffix);
        if (dest.exists()) return;
        try {
            Files.copy(f.toPath(), dest.toPath());
        } catch (IOException ignored) {
            // 留底失败就算了
        }
    }
}
