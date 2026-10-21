package com.example.myime;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 词库管理「从本地导入」用的 zip 展开(纯 JDK,可脱离手机测试,见 tools/DictZipSelfTest.java)。
 *
 * 选到的文件如果是 zip(文件头 PK),就把里面的普通文件展开到指定文件夹,再当作词库文件处理;
 * 比如把 gram.bin 压成 zip 传到手机,选这个 zip 就行,不用先解压。
 *
 * 安全:只用条目名的最后一段当文件名(不会写到文件夹外面),跳过目录、隐藏文件(. 开头,含 macOS 的 __MACOSX/._xxx),
 * 展开的总大小超过上限就中止。
 */
public final class DictZip {
    private DictZip() {}

    /** 文件开头是不是 zip 的魔数(PK 03 04)。读不了当不是。 */
    public static boolean isZip(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K' && in.read() == 3 && in.read() == 4;
        } catch (IOException e) {
            return false;
        }
    }

    /** 条目名 → 安全的文件名(最后一段,去掉不能用的字符);不该要的返回 null。 */
    static String safeName(ZipEntry e) {
        if (e.isDirectory()) return null;
        String n = e.getName().replace('\\', '/');
        if (n.contains("__MACOSX/")) return null;
        int slash = n.lastIndexOf('/');
        String base = slash >= 0 ? n.substring(slash + 1) : n;
        if (base.isEmpty() || base.startsWith(".")) return null;
        base = base.replaceAll("[\\u0000-\\u001F:*?\"<>|]", "_");
        return base.isEmpty() ? null : base;
    }

    /**
     * 把 zip 里的普通文件展开到 outDir,返回展开出来的文件(按 zip 里的顺序)。
     * 重名的在后面加 _2、_3。总大小超过 maxTotal 字节抛 IOException。
     */
    public static List<File> expand(File zip, File outDir, long maxTotal) throws IOException {
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("无法创建文件夹 " + outDir);
        List<File> out = new ArrayList<>();
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = safeName(e);
                if (name == null) continue;
                File dest = new File(outDir, name);
                for (int k = 2; dest.exists(); k++) dest = new File(outDir, uniq(name, k));
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(dest))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        total += n;
                        if (total > maxTotal) {
                            os.close();
                            dest.delete();
                            throw new IOException("zip 展开后太大(超过 " + (maxTotal >> 20) + " MB)");
                        }
                        os.write(buf, 0, n);
                    }
                }
                out.add(dest);
            }
        }
        return out;
    }

    /**
     * 把分成几段的大文件按文件名顺序接回一个文件(gram.bin.part1、gram.bin.part2 …;名字里最后的数字按大小比,part10 排在 part2 后面)。
     * 一个文件太大发不出去时分段传,手机上选中所有段导入时用。parts 至少要有两个;返回 out。
     */
    public static File join(List<File> parts, File out) throws IOException {
        if (parts.size() < 2) throw new IOException("至少要两段才能接起来");
        java.util.ArrayList<File> sorted = new java.util.ArrayList<>(parts);
        sorted.sort((a, b) -> {
            String na = a.getName(), nb = b.getName();
            String pa = na.replaceAll("[0-9]+$", ""), pb = nb.replaceAll("[0-9]+$", "");
            int c = pa.compareTo(pb);
            if (c != 0) return c;
            String da = na.substring(pa.length()), db = nb.substring(pb.length());
            long va = da.isEmpty() ? -1 : Long.parseLong(da.length() > 18 ? da.substring(da.length() - 18) : da);
            long vb = db.isEmpty() ? -1 : Long.parseLong(db.length() > 18 ? db.substring(db.length() - 18) : db);
            return Long.compare(va, vb);
        });
        byte[] buf = new byte[64 * 1024];
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            for (File f : sorted) {
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        return out;
    }

    private static String uniq(String name, int k) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + "_" + k + name.substring(dot) : name + "_" + k;
    }
}
