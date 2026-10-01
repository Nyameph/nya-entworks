package io.github.Nyameph.nyaentworks.common.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 把文件 / 目录<b>送进 Windows 回收站</b>的原语（2026-09-30）。
 *
 * <p><b>为什么要有它</b>：原先删除走 {@code Files.delete} —— 删错了就真没了，而「删」是这个
 * 应用里最常点的破坏性动作（歌曲 / 喊麦整组删、漫画目录删、打包后删源目录）。改成进回收站之后，
 * 删错了可以去资源管理器里右键「还原」，落点还在原路径。
 *
 * <p><b>为什么是 PowerShell</b>：Java 没有回收站 API（{@code Desktop.moveToTrash} 已经没有了）。
 * 本机自带的 Windows PowerShell 5.1 里有 {@code Microsoft.VisualBasic.FileIO.FileSystem}，
 * 它的 {@code RecycleOption.SendToRecycleBin} 语义正好是我们要的那一条：<b>送不进回收站就报错，
 * 绝不悄悄永久删</b>（用户 2026-09-30 裁决：拒绝执行并报原因）。不引 JNA、不自己 P/Invoke
 * {@code SHFileOperation}（那要手拼双 {@code \0} 结尾的路径块与结构体对齐，为一次删除不值得）。
 *
 * <p><b>三处编码上的讲究，每一条都是踩过的坑的镜像</b>：
 * <ol>
 *   <li>脚本正文<b>全 ASCII</b>、用 {@code -EncodedCommand}（UTF-16LE + Base64）传 ——
 *       学 {@code 启动.cmd} 那条：把中文塞进命令行参数会被控制台按 936 解码冲垮解析；</li>
 *   <li>要删的路径<b>上不了命令行</b>：写进一个 UTF-8 临时清单文件，路径经<b>环境变量</b>告诉脚本
 *       （Windows 上的环境块是 UTF-16，中文路径安全），脚本再按 UTF-8 读那份清单；</li>
 *   <li>结果也写 UTF-8 文件读回来 —— 全程不碰控制台代码页。</li>
 * </ol>
 *
 * <p><b>「进程退出了」不等于成功</b>：结果逐条回报，{@code MISSING}（调用前就不在了）按成功算
 * （与原先 {@code deleteIfExists} 的口径一致），{@code FAIL} 的原因原样带回 Java 塞进异常。
 *
 * <p><b>两处真调用时才现形的坑</b>（2026-09-30 第一次真删时连撞两个，记在这儿免得下一轮重犯）：
 * <ol>
 *   <li>{@code DeleteDirectory} 的第四个参数是 {@code UICancelOption}，<b>不是</b>
 *       {@code DeleteDirectoryOption} —— 后者只属于 {@code My.Computer.FileSystem} 那几个重载，
 *       传进去不会编译报错，是运行到那一行才抛「无法将…转换为类型 UICancelOption」；</li>
 *   <li>powershell 重定向出来的字节走<b>控制台代码页</b>（本机 936），拿 {@code UTF_8} 严格读会抛
 *       {@code MalformedInputException}，而它的消息只有一句 {@code Input length = 1} —— 正好把
 *       上一条那个说得清清楚楚的报错盖住。所以它输出的日志与结果**一律宽松解码**
 *       （{@link #decodeLeniently}）。</li>
 * </ol>
 *
 * <p><b>已知边界</b>（详见 {@code docs/已完成/删除进回收站实施计划.md} §0.3）：
 * 回收站有容量上限，比上限还大的文件 Windows 会永久删掉；所以脚本事后复核一遍
 * 「那个路径还在不在」。{@code UIOption.OnlyErrorDialogs} 出错时理论上会弹一个系统错误框，
 * 真弹了就没人点 —— 所以带超时，超时后报一句「去屏幕上看看是不是有框挂着」。
 *
 * <p><b>本类只管文件，一概不碰库</b>（同 {@link GroupFileOps} 的立场）：改动流水由调用方
 * 在成功后按老规矩记（{@code FileOpRecorder.recordPath} / {@code recordPlan}）。
 */
public final class RecycleBin {

    private static final Logger log = LoggerFactory.getLogger(RecycleBin.class);

    private RecycleBin() {
    }

    /** 路径清单与结果文件的位置经环境变量传给脚本 —— 命令行上只出现 Base64，不出现路径 */
    private static final String ENV_LIST = "NYA_ENTWORKS_RECYCLE_LIST";
    private static final String ENV_RESULT = "NYA_ENTWORKS_RECYCLE_RESULT";

    /** 等 powershell 的上限；超了八成是弹了个没人点的系统框（见类注释的已知边界） */
    private static final int TIMEOUT_SECONDS = 120;

    /**
     * 送回收站的脚本。<b>全 ASCII</b>（理由见类注释第 1 条），带中文的话只在 Java 这一侧拼。
     */
    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName Microsoft.VisualBasic
            $enc = New-Object System.Text.UTF8Encoding($false)
            $paths = [System.IO.File]::ReadAllLines($env:NYA_ENTWORKS_RECYCLE_LIST, $enc)
            $out = New-Object System.Collections.Generic.List[string]
            foreach ($p in $paths) {
                try {
                    if ([System.IO.Directory]::Exists($p)) {
                        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($p,
                            [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
                            [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin,
                            [Microsoft.VisualBasic.FileIO.UICancelOption]::ThrowException)
                    } elseif ([System.IO.File]::Exists($p)) {
                        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($p,
                            [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
                            [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)
                    } else {
                        $out.Add('MISSING|' + $p)
                        continue
                    }
                    if ([System.IO.Directory]::Exists($p) -or [System.IO.File]::Exists($p)) {
                        $out.Add('FAIL|' + $p + '|still present after the call')
                    } else {
                        $out.Add('OK|' + $p)
                    }
                } catch {
                    $msg = $_.Exception.Message.Replace([string][char]13, ' ').Replace([string][char]10, ' ')
                    $out.Add('FAIL|' + $p + '|' + $msg)
                }
            }
            [System.IO.File]::WriteAllLines($env:NYA_ENTWORKS_RECYCLE_RESULT, $out, $enc)
            """;

    /**
     * 批量送进回收站。
     *
     * <p><b>不抛异常</b>（除非整个调用都起不来）：返回「没成功的那些」＝ 路径 → 原因，空 Map 表示全成。
     * 调用方多半要拿这份明细拼一句人话（{@link GroupFileOps#deleteAll} 就是这么用的）。
     * 调用前就已经不在磁盘上的路径算成功（那正是删除想要的结果）。
     */
    public static Map<String, String> recycleAll(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("nya-entworks-recycle-");
            Path listFile = work.resolve("paths.txt");
            Path resultFile = work.resolve("result.txt");
            Path logFile = work.resolve("powershell.log");
            Files.write(listFile, paths, StandardCharsets.UTF_8);

            Process process = processBuilderFor(listFile, resultFile, logFile).start();

            boolean finished;
            try {
                finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("送回收站被中断（" + paths.size() + " 个文件）", e);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("送回收站超时（" + TIMEOUT_SECONDS + " 秒，"
                        + paths.size() + " 个文件）。屏幕上可能挂着一个系统错误框，"
                        + "点掉它再试；否则就是回收站那一刻不可用");
            }
            if (!Files.isRegularFile(resultFile)) {
                throw new IllegalStateException("送回收站失败：powershell 没有回报结果"
                        + "（退出码 " + process.exitValue() + "）" + tailOf(logFile));
            }
            return failuresOf(paths, readLinesLeniently(resultFile));
        } catch (IOException e) {
            throw new IllegalStateException("送回收站失败：" + e.getMessage(), e);
        } finally {
            deleteQuietly(work);
        }
    }

    /** 单个文件 / 目录送回收站；没送成抛 {@link IllegalStateException}，消息里带着原因 */
    public static void recycle(Path path) {
        recycle(path == null ? null : path.toString());
    }

    /** 单个（路径版本）；没送成抛 {@link IllegalStateException} */
    public static void recycle(String path) {
        if (path == null) {
            throw new IllegalArgumentException("要送回收站的路径不能为空");
        }
        Map<String, String> failures = recycleAll(List.of(path));
        if (!failures.isEmpty()) {
            throw new IllegalStateException("没能把这个送进回收站：" + joinFailures(failures));
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 脚本正文（给单测看：断言它是纯 ASCII，且带的是 SendToRecycleBin） */
    static String script() {
        return SCRIPT;
    }

    /**
     * 解析结果文件：返回「没成功的那些」＝ 路径 → 原因，空 Map 表示清单里每一条都成。
     * <p>三类：{@code OK}（成了）、{@code MISSING}（调用前就不在了，也算成）、
     * {@code FAIL}（带上 .NET 的原话）。清单里有、结果里没有的按「没有回报」算失败 ——
     * 静默少一条比报错更糟。
     */
    static Map<String, String> failuresOf(List<String> requested, List<String> resultLines) {
        Map<String, String> byPath = new LinkedHashMap<>();
        for (String line : resultLines) {
            int first = line.indexOf('|');
            if (first <= 0) {
                continue;
            }
            int second = line.indexOf('|', first + 1);
            String status = line.substring(0, first);
            String path = second < 0 ? line.substring(first + 1) : line.substring(first + 1, second);
            String message = second < 0 ? "" : line.substring(second + 1);
            byPath.put(path, "OK".equals(status) || "MISSING".equals(status) ? null : message);
        }
        Map<String, String> failures = new LinkedHashMap<>();
        for (String path : requested) {
            if (!byPath.containsKey(path)) {
                failures.put(path, "powershell 没有回报这条的结果");
            } else if (byPath.get(path) != null) {
                String message = byPath.get(path);
                failures.put(path, message.isEmpty() ? "没能移入回收站" : message);
            }
        }
        return failures;
    }

    /** 把失败明细拼成给人看的一段（调用方自己那句更贴切的话优先，这个是兜底） */
    static String joinFailures(Map<String, String> failures) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : failures.entrySet()) {
            parts.add(e.getKey() + " —— " + e.getValue());
        }
        return String.join("；", parts);
    }

    /**
     * 拼好那次调用（给单测看：<b>两个环境变量必须在</b>）。
     *
     * <p>2026-09-30 第一次真调用时踩到：脚本读 {@code $env:NYA_ENTWORKS_RECYCLE_LIST}，
     * 而 Java 这一侧只把路径写进了清单文件、**从来没把环境变量交给子进程** ——
     * 于是 powershell 收到一个空路径，报「路径不是合法的形式」。纯单测看不见这件事
     * （它不跑进程），所以这里单独抽出来钉一条。
     */
    static ProcessBuilder processBuilderFor(Path listFile, Path resultFile, Path logFile) {
        // 输出全部转进文件：既不会因为没人读管道而卡住子进程，失败时也有原话可看
        ProcessBuilder builder = new ProcessBuilder(powershell(),
                "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        builder.environment().put(ENV_LIST, listFile.toString());
        builder.environment().put(ENV_RESULT, resultFile.toString());
        return builder;
    }

    /** 找不到 powershell 就不删（拒绝执行并报原因），不偷偷退回永久删除 */
    private static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path exe = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(exe)) {
                return exe.toString();
            }
        }
        return "powershell.exe";   // 兜底：PATH 里找（找不到会抛 IOException，照样是拒绝执行）
    }

    private static String encodedScript() {
        return Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * powershell 的原话（末 8 行足够看清 Add-Type / 编码一类的问题）。
     * <p><b>它读文件绝不抛</b>：只读不出来的话，我们会把「参数枚举传错了」这种一眼能看懂的
     * 报错盖成 {@code Input length = 1}（{@code MalformedInputException} 的消息就这一句）
     * —— 2026-09-30 实际踩到过一次，见 {@link #decodeLeniently}。
     */
    private static String tailOf(Path logFile) {
        if (!Files.isRegularFile(logFile)) {
            return "";
        }
        List<String> lines;
        try {
            lines = readLinesLeniently(logFile);
        } catch (IOException e) {
            return "。（powershell 的日志读不出来：" + e.getMessage() + "）";
        }
        if (lines.isEmpty()) {
            return "";
        }
        int from = Math.max(0, lines.size() - 8);
        return "。powershell 说：" + String.join(" ", lines.subList(from, lines.size()));
    }

    /** 按行读，但**坏字节只换成 {@code ?}、绝不抛**（理由见 {@link #decodeLeniently}） */
    static List<String> readLinesLeniently(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : decodeLeniently(Files.readAllBytes(file)).split("\r?\n")) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * 宽松解码 powershell 重定向出来的字节：先当 UTF-8 严格读，读不动就按 **GBK**
     * （Windows 中文机的控制台代码页是 936，powershell 的重定向输出跟着它走）再来一遍。
     *
     * <p><b>为什么非得宽松</b>：严格读会在遇到坏字节时抛
     * {@code MalformedInputException}，而它只带一句 {@code Input length = 1} —— 真正的原因
     * （那一次是「DeleteDirectory 的第四个参数应当是 UICancelOption」）就被它盖掉了，
     * 报出来等于没报。这条原话会进异常消息，是这个功能唯一的排查线索。
     */
    static String decodeLeniently(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            try {
                return Charset.forName("GBK").decode(ByteBuffer.wrap(raw)).toString();
            } catch (RuntimeException notGbk) {
                return new String(raw, Charset.defaultCharset());
            }
        }
    }

    /** 临时目录删不掉不影响结论，只记一句 */
    private static void deleteQuietly(Path work) {
        if (work == null) {
            return;
        }
        try (var walk = Files.walk(work)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("回收站的临时目录没能清掉：{}", work, e);
        }
    }
}
