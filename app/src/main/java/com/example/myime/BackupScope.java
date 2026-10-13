package com.example.myime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 备份范围:哪些数据要进备份。纯 JDK 代码(不依赖 Android),可以脱离手机单独测试。
 *
 * 原则:**默认全部备份,只排除明确登记的"可重建 / 可下载 / 设备专属"数据**。
 * 所以以后加新功能,只要数据放在应用数据目录(filesDir)或 SharedPreferences 里,就自动进备份、自动能还原,
 * 不用改备份代码。不想备份的(纯缓存)放进 cacheDir,或者在下面的 SKIP_* 里登记一行。
 *
 * 备份条目名(压缩包里的路径):
 *   files/<相对 filesDir 的路径>     应用数据目录里的所有文件(含子目录)
 *   prefs/<名字>.txt                 每个 SharedPreferences 文件(导出成文本)
 *   dicts/<文件名>                   词库包(可下载的,只有勾选「包含词库包」才有)
 */
public final class BackupScope {
    private BackupScope() {
    }

    /** filesDir 顶层目录里不备份的:可重建的缓存、可下载的词库、还原时留的安全副本和临时目录。 */
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "dictcache", "dicts", "restore_staging", "user_before_restore"));

    /** 不备份的设置文件:坚果云账号和包着备份密钥的数据(设备专属,换手机没用,也不该导出)。 */
    private static final Set<String> SKIP_PREFS = new HashSet<>(Collections.singletonList("backupcfg"));

    /** 各种临时 / 副本文件(SafeFile 的 .tmp .bak .corrupt .vN 等),它们是主文件的附属,不单独备份。 */
    private static final Pattern TEMP = Pattern.compile(".*(\\.(tmp|bak|corrupt|new|lock)|\\.v[0-9]+)$");

    private static final Pattern PREF_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final int MAX_DEPTH = 8;

    public static boolean isTemp(String fileName) {
        return TEMP.matcher(fileName).matches();
    }

    /** filesDir 下要备份的文件,返回相对路径(用 / 分隔),排好序。 */
    public static List<String> listFiles(File filesDir) {
        List<String> out = new ArrayList<>();
        walk(filesDir, "", 0, out);
        Collections.sort(out);
        return out;
    }

    private static void walk(File dir, String prefix, int depth, List<String> out) {
        if (depth > MAX_DEPTH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String name = f.getName();
            if (depth == 0 && SKIP_DIRS.contains(name)) continue;
            String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            if (f.isDirectory()) {
                walk(f, rel, depth + 1, out);
            } else if (f.isFile() && !isTemp(name)) {
                out.add(rel);
            }
        }
    }

    /** 要备份的 SharedPreferences 文件名(不含 .xml)。prefsDir = <数据目录>/shared_prefs。 */
    public static List<String> listPrefNames(File prefsDir) {
        List<String> out = new ArrayList<>();
        File[] kids = prefsDir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName();
                if (!f.isFile() || !n.endsWith(".xml")) continue;
                String name = n.substring(0, n.length() - 4);
                if (safePrefName(name)) out.add(name);
            }
        }
        Collections.sort(out);
        return out;
    }

    public static boolean safePrefName(String name) {
        return PREF_NAME.matcher(name).matches() && !SKIP_PREFS.contains(name);
    }

    /**
     * 还原时把条目里的相对路径解析成 filesDir 下的文件。路径不安全(含 ..、绝对路径、反斜杠、空段、
     * 跑出 filesDir)、落在不备份的目录里、或是临时文件名,都返回 null —— 恶意构造的备份文件写不到别的地方。
     */
    public static File resolve(File filesDir, String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.indexOf('\\') >= 0 || rel.indexOf('\0') >= 0) return null;
        String[] parts = rel.split("/", -1);
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..")) return null;
        }
        if (parts.length > MAX_DEPTH + 1) return null;
        if (parts.length > 1 && SKIP_DIRS.contains(parts[0])) return null;
        if (parts.length == 1 && SKIP_DIRS.contains(parts[0])) return null;
        if (isTemp(parts[parts.length - 1])) return null;
        File f = new File(filesDir, rel);
        try {
            String base = filesDir.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(base)) return null;
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    /**
     * 数据有没有变化的「指纹」:要备份的文件和设置文件最近一次修改时间。只是几次 stat,不读内容,省电。
     * 没有任何数据返回 0。
     */
    public static long signature(File filesDir, File prefsDir) {
        long t = 0L;
        for (String rel : listFiles(filesDir)) t = Math.max(t, new File(filesDir, rel).lastModified());
        for (String n : listPrefNames(prefsDir)) t = Math.max(t, new File(prefsDir, n + ".xml").lastModified());
        return t;
    }
}
