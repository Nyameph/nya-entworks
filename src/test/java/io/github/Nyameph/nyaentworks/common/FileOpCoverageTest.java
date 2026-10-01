package io.github.Nyameph.nyaentworks.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 防漏记的扫描守卫：{@code src/main/java} 与 {@code src/test/java/.../script} 里
 * <b>可能改盘</b>的调用（{@code Files.move} / {@code Files.delete} / … ）所在的
 * {@code 类#方法}，要么同一个方法体里出现过记录调用（{@code FileOpRecorder.} /
 * {@code recordPlan} / {@code recordPath}），要么在下面的允许清单里带理由登记。
 *
 * <p><b>为什么要有它</b>：这个功能的失效方式是静默的 —— 某个调用点忘了记，
 * 页面照画、文件照搬、什么也不报，只有真正要查记录的那天才发现那一段是空的。
 * 同类的静默错（{@code SettingsCatalog} 的 key 少前缀）当时也是靠「会红的失败」钉住的。
 *
 * <p><b>纯读源码 + 比对清单</b>：不连库、不碰 {@code F:\}，所以进了「能直接跑」那张表。
 * 按 {@code 类#方法} 而不是按行号：行号天天漂、方法名不会 —— 「行移动」不误报，
 * 「新加了一个会动盘的方法」一定报。注释里的调用不算（{@code SongMidiBpmUtil} 那行
 * 报告写入是被注释掉的），所以先剥掉注释再扫。
 */
public class FileOpCoverageTest {

    /** 扫描范围：主源码 + script 运维脚本（script 在 src/test 下但会真改盘） */
    private static final Path MAIN = Paths.get("src", "main", "java");
    /** 包名从本类现推，不写死 —— 写死的下场是换一次包名这条守卫就扫不到 {@code script/}，
     *  看着绿、其实什么都没查（2026-10-01 改包名时真踩到：报 NoSuchFile）。 */
    private static final Path SCRIPT = Paths.get("src", "test", "java")
            .resolve(FileOpCoverageTest.class.getPackageName().replace('.', '/'))
            .resolveSibling("script");

    /** 可能改盘的调用形态（在剥过注释的源码上匹配） */
    private static final Pattern DISK_MUTATION = Pattern.compile(
            "Files\\s*\\.\\s*(move|delete|deleteIfExists|write|writeString|copy)\\s*\\("
                    + "|\\.renameTo\\s*\\("
                    + "|\\.delete\\s*\\(\\s*\\)"
                    + "|FileUtil\\s*\\.\\s*del\\s*\\("
                    + "|FileUtils\\s*\\.\\s*(delete|move|copy|write)"
                    // 2026-09-30 起删除走回收站原语：调用它同样是一次改盘，同样要记录
                    // （它的内部是 powershell 进程，Java 这一侧看不见 Files.delete）
                    + "|RecycleBin\\s*\\.\\s*recycle");

    /**
     * <b>永久</b>删除的调用形态（2026-09-30 起，第二个守卫）。
     *
     * <p>与 {@link #DISK_MUTATION} 的分工：那个问「改盘记了没」，这个问「这一处该不该
     * 绕过回收站」。判定读的是<b>全部</b>方法、不看有没有记流水 —— 记了流水也改变不了
     * 「这份东西永久没了」这个事实。
     */
    private static final Pattern PERMANENT_DELETE = Pattern.compile(
            "Files\\s*\\.\\s*delete(IfExists)?\\s*\\("
                    + "|FileUtil\\s*\\.\\s*del\\s*\\("
                    + "|FileUtils\\s*\\.\\s*delete"
                    + "|\\w\\s*\\.\\s*delete\\s*\\(\\s*\\)"
                    + "|\\.deleteOnExit\\s*\\(");

    /** 方法声明（到参数表结束、可选 throws、然后 `{`）。名字捕获在 group 1 */
    private static final Pattern METHOD_HEAD = Pattern.compile(
            "(?:^|[\\n;}])\\s*"
                    + "(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|private|protected|static|final|synchronized"
                    + "|abstract|default|native|strictfp)\\s+)+"
                    + "(?:<[\\w<>\\[\\],\\s\\.?&]+>\\s+)?"
                    + "[\\w<>\\[\\],\\s\\.?]+?\\s+(\\w+)\\s*\\("
                    + "[^;{}]*\\)\\s*(?:throws[\\w\\s,.]+)?\\{");

    /** 方法体里出现过的记录调用形态（本守卫认这个为「已覆盖」） */
    private static final Pattern RECORD_CALL = Pattern.compile(
            "FileOpRecorder\\s*\\.|recordPlan\\s*\\(|recordPath\\s*\\(");

    /**
     * 允许清单：一条 = 类#方法 + 一行理由。<b>没在清单里、又没记录 ⇒ 红。</b>
     * 初值来自设计文档阶段 4.1 / 5.1 / 5.3 / 5.4 那几张表。
     */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>();

    static {
        // ---- 共用原语：记录在调用方（recordPlan / recordPath）那一层 ----
        allow("GroupFileOps#moveAll", "共用搬动原语；记录由调用方在成功后 recordPlan（挂这里会让文件级动作全部进流水）");
        allow("GroupFileOps#deleteAll", "共用删除原语；记录由调用方在成功后 recordPlan");
        allow("GroupFileOps#rollback", "moveAll 中途失败时的回滚半步，随调用方记录");
        // ---- 回收站原语本身（2026-09-30）：它就是「改盘」那一步，记录在调用方 ----
        allow("RecycleBin#recycleAll", "回收站原语；改盘本身不记，记录一律在调用方成功后按老规矩记");
        allow("RecycleBin#deleteQuietly", "回收站原语自己的临时工作目录（paths.txt / result.txt / 日志），不记");
        // ---- 配置写入：不是三个模块的文件，且自带 *.bak 备份（附录 A.2 第 6 条） ----
        allow("SettingsService#save", "配置文件写入不记（nya-entworks.yaml，自带 .bak）");
        // ---- 漫画：文件级 / 形态转换，一律不记（5.4 反面清单） ----
        allow("MangaCoverService#thumbnail", "缩略图缓存，文件级（5.4）");
        allow("MangaCoverService#cbzThumbnail", "缩略图缓存，文件级（5.4）");
        allow("MangaCoverService#clearCache", "缩略图缓存的清理，文件级（5.4）");
        allow("MangaCoverService#makeThumbnailViaNConvert", "缩略图生成的临时文件，文件级（5.4）");
        allow("MangaCoverService#makeThumbnailFromBytes", "缩略图生成的临时文件，文件级（5.4）");
        allow("MangaPackService#packDir", "cbz 打包是形态转换：目录与 cbz 是一体两面，两头都不记才如实（5.4）；"
                + "源目录那一次删除 2026-09-30 起走 RecycleBin，但流水口径不变");
        allow("MangaArchiveService#applyOtherMove", "搬的是归档根下的散文件/杂物，含漫画的子目录已被 scanOthers 拦住（5.4）");
        allow("MangaStoreService#moveDirectory", "move / moveCollectionIntoArchive 的跨卷兜底内部，记录在上层");
        allow("MangaStoreService#copyTree", "同上：moveDirectory 的内部递归复制");
        allow("MangaStoreService#deleteTree", "同上：moveDirectory 的内部递归删除");
        allow("MangaStoreService#visitFile", "copyTree / deleteTree 匿名 visitor 的逐文件步，记录在上层");
        allow("MangaStoreService#postVisitDirectory", "deleteTree 匿名 visitor 的逐目录步，记录在上层");
        // ---- script/ 侧 ----
        allow("SongOriginalFileRenameUtil#moveFile",
                "两步搬工具（Windows 大小写冲突时经 temp 中转），记录在 applyInside 逐条出（5.3）");
        allow("MangaCleanCbzResidueUtil#mergeAndRepack", "cbz 合写是形态转换，不记（5.3）；「删空目录」那处在 clean 里单独记");
        allow("MangaCleanCbzResidueUtil#unzipCbz", "cbz 合写的内部步骤（5.3）");
        allow("MangaCleanCbzResidueUtil#copyDirOver", "cbz 合写的内部步骤（5.3）");
        allow("MangaCleanCbzResidueUtil#visitFile", "copyDirOver 匿名 visitor 的逐文件步（5.3）");
        allow("MangaCleanCbzResidueUtil#deleteDir", "工具方法（clean 的「删空目录」与临时目录清理共用），记录在 clean 里");
        allow("MangaNameParserTestUtil#extractSubFile", "抽深层图片到根：文件级动作，不记（5.3）");
        allow("MangaNameParserTestUtil#visitFile", "extractSubFile 匿名 visitor 的逐文件搬动，文件级不记（5.3）");
        allow("SongAudioBpmUtil#beatsViaBeatThis", "只删后端临时输出文件；WRITE_REPORT=false 时报告也不写（5.3）");
        allow("SongAudioBpmUtil#run", "同上：外部命令的临时输出");
        allow("SongAudioBpmUtil#finish", "WRITE_REPORT=false 时不写报告；将来打开时按 SCRIPT 记（5.3）");
    }

    private static void allow(String method, String reason) {
        String prev = ALLOWED.putIfAbsent(method, reason);
        if (prev != null) {
            throw new IllegalStateException("允许清单里有重复条目：" + method);
        }
    }

    /**
     * <b>允许永久删除</b>的方法清单（第二个守卫，2026-09-30 起）。
     * 一条 = 类#方法 + 一行理由，理由要答的是「为什么它不该进回收站」。
     * 只扫 {@code src/main/java}：{@code script/} 下的运维脚本不在此次改动的范围内
     * （用户 2026-09-30 裁决），它们的临时文件与报告文件永久删是既有口径。
     */
    private static final Map<String, String> PERMANENT = new LinkedHashMap<>();

    static {
        // 回收站原语自己的工作目录（清单 / 结果 / 日志），本来就在系统临时目录里
        permanent("RecycleBin#deleteQuietly",
                "回收站原语自己的临时工作目录，用完即弃；送回收站等于把垃圾堆进用户的回收站");
        // 缩略图缓存：可再生，用户从没「拥有」过；「清空缩略图缓存」按钮的目的就是抹掉它们
        permanent("MangaCoverService#clearCache",
                "缩略图缓存，按需重建；这个按钮的语义就是清掉它们");
        permanent("MangaCoverService#makeThumbnailViaNConvert",
                "转 jpg 用的临时文件（系统临时目录）");
        permanent("MangaCoverService#makeThumbnailFromBytes",
                "cbz 封面转 jpg 用的临时文件（系统临时目录）");
        // 打包 cbz 里只有 .part 中间文件是永久删：源目录那一次走 RecycleBin
        permanent("MangaPackService#packDir",
                "只删 .part 中间文件；源目录那一次已改成进回收站");
        // 存储合集时的断点标记：我们自己写进用户目录的 .nya-entworks-*.json，
        // 搬进归档根之后必须消失 —— 它要是进回收站，用户的回收站里就躺着一堆我们的垃圾
        permanent("MangaStoreService#moveCollectionIntoArchive",
                "删的是自己的断点标记文件（.nya-entworks-*.json），搬迁成功后不该留在归档目录里");
        // 跨卷兜底的搬运：内容已经完整复制到目标那一份，删的是源副本。
        // 送回收站的语义在这里是错的 —— 那等于把同一份内容在回收站里再放一遍
        permanent("MangaStoreService#moveDirectory",
                "跨卷兜底搬运：删的是「已复制到目标」的源副本，同一份内容的两个路径只能留一个");
        permanent("MangaStoreService#deleteTree",
                "moveDirectory 的内部（含删源失败时对已复制内容的回滚）");
        permanent("MangaStoreService#visitFile",
                "deleteTree 匿名 visitor 的逐文件步（同上）");
        permanent("MangaStoreService#postVisitDirectory",
                "deleteTree 匿名 visitor 的逐目录步（同上）");
        // 下载的 .ncm 只是解密的中间产物，解出来的音频已经落盘；它没有还原价值，
        // 每下载一次就往回收站塞一份是净负担（用户 2026-09-30 裁决）
        permanent("SongResourceSearchService#decryptNcm",
                ".ncm 解密中间文件，产物（音频）已经落盘");
    }

    private static void permanent(String method, String reason) {
        String prev = PERMANENT.putIfAbsent(method, reason);
        if (prev != null) {
            throw new IllegalStateException("永久删除清单里有重复条目：" + method);
        }
    }

    @Test
    public void everyDiskMutationIsRecordedOrAllowed() throws IOException {
        Map<String, List<String>> hits = scan();
        assertTrue(!hits.isEmpty(),
                "一个命中点都没扫到 —— 路径写错了，这个守卫在空转（假绿）");

        List<String> uncovered = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : hits.entrySet()) {
            String method = entry.getKey();
            if (ALLOWED.containsKey(method)) {
                continue;
            }
            uncovered.add(method + "  ← " + entry.getValue().size() + " 处改盘调用未记录："
                    + String.join("; ", entry.getValue()));
        }
        // 顺带盯住清单里已经失效的条目（方法删了/改名了），免得它变成没人认领的杂物
        List<String> stale = new ArrayList<>();
        for (String method : ALLOWED.keySet()) {
            if (!hits.containsKey(method)) {
                stale.add(method);
            }
        }

        if (!uncovered.isEmpty() || !stale.isEmpty()) {
            StringBuilder message = new StringBuilder();
            if (!uncovered.isEmpty()) {
                message.append("以下会改盘的方法既没记录也没进允许清单（漏记会静默，必须处理）：\n  ");
                message.append(String.join("\n  ", uncovered)).append('\n');
            }
            if (!stale.isEmpty()) {
                message.append("允许清单里这些方法已经不存在了（改名/删除后记得同步清单）：\n  ");
                message.append(String.join(", ", stale));
            }
            fail(message.toString());
        }
    }

    /**
     * 第二个守卫：主代码里<b>永久删</b>的每一处都得在 {@link #PERMANENT} 里写明为什么。
     *
     * <p><b>为什么要有它</b>：删除改走回收站之后，失效方式变成「某处悄悄绕过了回收站」——
     * 新写的代码顺手一个 {@code Files.deleteIfExists} 就把用户的东西永久抹了，页面照画、
     * 一切正常，只有用户去回收站找的时候才发现没有。这条守卫让「绕过回收站」必须是一次
     * 有理由的登记，而不是一次顺手。
     */
    @Test
    public void permanentDeletesAreAllJustified() throws IOException {
        Map<String, List<String>> hits = scanMainForPermanentDeletes();
        assertTrue(!hits.isEmpty(),
                "一个永久删除都没扫到 —— 路径或正则写错了，这个守卫在空转（假绿）");

        List<String> unjustified = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : hits.entrySet()) {
            if (PERMANENT.containsKey(entry.getKey())) {
                continue;
            }
            unjustified.add(entry.getKey() + "  ← " + entry.getValue().size() + " 处永久删："
                    + String.join("; ", entry.getValue())
                    + "（改用 RecycleBin.recycle，或写进 PERMANENT 清单并说清为什么）");
        }
        List<String> stale = new ArrayList<>();
        for (String method : PERMANENT.keySet()) {
            if (!hits.containsKey(method)) {
                stale.add(method);
            }
        }
        if (!unjustified.isEmpty() || !stale.isEmpty()) {
            StringBuilder message = new StringBuilder();
            if (!unjustified.isEmpty()) {
                message.append("以下方法里有绕过回收站的永久删除（要么改掉、要么登记理由）：\n  ");
                message.append(String.join("\n  ", unjustified)).append('\n');
            }
            if (!stale.isEmpty()) {
                message.append("永久删除清单里这些方法已经没有永久删除了（改名/改实现后记得同步清单）：\n  ");
                message.append(String.join(", ", stale));
            }
            fail(message.toString());
        }
    }

    // ------------------------------------------------------------------
    // 扫描实现
    // ------------------------------------------------------------------

    /** 永久删的命中表：类#方法 → 命中的原文片段；只扫主源码（script/ 不在范围内） */
    private static Map<String, List<String>> scanMainForPermanentDeletes() throws IOException {
        Map<String, List<String>> hits = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (MethodRange method : methodRanges(code)) {
                    String body = code.substring(method.start(), method.end());
                    Matcher matcher = PERMANENT_DELETE.matcher(body);
                    List<String> snippets = new ArrayList<>();
                    while (matcher.find()) {
                        String snippet = matcher.group().replaceAll("\\s+", "");
                        if (!snippets.contains(snippet)) {
                            snippets.add(snippet);
                        }
                    }
                    if (snippets.isEmpty()) {
                        continue;
                    }
                    // 同名方法（重载 / 匿名 visitor）会落到同一个键上，累计而不是覆盖
                    hits.computeIfAbsent(file.getFileName().toString().replace(".java", "")
                            + "#" + method.name(), k -> new ArrayList<>()).addAll(snippets);
                }
            }
        }
        return hits;
    }

    /** @return 类#方法 → 该方法体里命中 DISK_MUTATION 的原文片段（去重、保序） */
    private static Map<String, List<String>> scan() throws IOException {
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (Path file : sources()) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String code = stripComments(raw);
            for (MethodRange method : methodRanges(code)) {
                String body = code.substring(method.start(), method.end());
                if (!RECORD_CALL.matcher(body).find()) {
                    collectHits(file, method, body, hits);
                }
            }
        }
        return hits;
    }

    private static void collectHits(Path file, MethodRange method, String body,
                                    Map<String, List<String>> hits) {
        Matcher matcher = DISK_MUTATION.matcher(body);
        while (matcher.find()) {
            String key = file.getFileName().toString().replace(".java", "") + "#" + method.name();
            hits.computeIfAbsent(key, k -> new ArrayList<>());
            String snippet = matcher.group().replaceAll("\\s+", "");
            if (!hits.get(key).contains(snippet)) {
                hits.get(key).add(snippet);
            }
        }
    }

    private static List<Path> sources() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        try (Stream<Path> walk = Files.walk(SCRIPT)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        return files;
    }

    private record MethodRange(String name, int start, int end) {
    }

    /** 找出全部方法体区间（含 record / 匿名类这类「名字后跟大括号」的块） */
    private static List<MethodRange> methodRanges(String code) {
        List<MethodRange> ranges = new ArrayList<>();
        Matcher head = METHOD_HEAD.matcher(code);
        while (head.find()) {
            int open = code.indexOf('{', head.end() - 1);
            if (open < 0) {
                continue;
            }
            int close = matchBrace(code, open);
            if (close > open) {
                ranges.add(new MethodRange(head.group(1), open, close + 1));
            }
        }
        return ranges;
    }

    /** 从 open（指向 `{`）出发配对到对应的 `}`；配不上返回 -1 */
    private static int matchBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 剥掉行注释、块注释与文本块。字符串字面量保留原样（本仓库的文件名字符串
     * 不含这些调用形态；真含了也只是多扫出一处、由清单兜住，不会漏）。
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length()
                        && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');   // 保住行结构，错误信息里的行号不漂
                    }
                    i++;
                }
                i = Math.min(i + 2, source.length());
            } else if (c == '"' && i + 2 < source.length()
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                out.append("\"\"");
                i += 3;
                while (i + 2 < source.length()
                        && !(source.charAt(i) == '"' && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"')) {
                    i++;
                }
                i = Math.min(i + 3, source.length());
                out.append("\"\"");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 调试用：临时在某个测试里调它，可以看到全部命中点与判定的方法名 */
    @SuppressWarnings("unused")
    private static void dump() throws IOException {
        for (Map.Entry<String, List<String>> e : scan().entrySet()) {
            System.out.println(e.getKey() + " : " + e.getValue());
        }
    }
}
