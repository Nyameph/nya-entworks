package io.github.Nyameph.nyaentworks.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从音频文件估曲速 + 高精度回归（一次性脚本，只读：不改盘、不写库）。
 *
 * <p><b>为什么不是「找个库直接吐 BPM」</b>：这类工具报的 BPM 都是「拍长估计」，
 * 输出 128.03 还是 128.031 只是格式化，不代表那个精度。真正的精度来自<b>拍点序列的回归</b>——
 * 后端只负责给出拍点时刻（秒），本脚本对拍点做最小二乘拟合 {@code t_i = a + b·i}，
 * 取 {@code BPM = 60/b}。回归用上全部拍点，比「首尾两点法」和「间隔中位数」稳得多，
 * 而且能顺带算出<b>不确定度</b>：拍点本身带量化误差（模型帧率 / 采样点），
 * 斜率标准误差 {@code ≈ σ_拍 / √(Σ(i-ī)²)}，歌越长、拍点越多就越准。
 * 报告按这个不确定度决定报几位小数，<b>不报虚假精度</b>。
 *
 * <p><b>后端</b>（{@link #BACKEND}）：
 * <ul>
 *   <li>{@code BEAT_THIS}（默认，精度最高）—— CPJKU 的 ISMIR 2024 模型。
 *       {@code pip install beat-this}（先装 PyTorch），跑 {@code beat_this <音频> -o <临时文件>}
 *       再把拍点文件读回来。有 CUDA 很快，{@link #BEAT_THIS_ARGS} 里把 {@code --gpu=-1} 去掉；
 *       纯 CPU 也能跑，慢一些。</li>
 *   <li>{@code AUBIO}（轻量、快）—— {@code aubio beat <音频>}，解析 stdout。
 *       单个 C 二进制、无 Python 依赖；但精度和抗 octave error 明显不如前者。</li>
 * </ul>
 *
 * <p><b>用法</b>：改 {@link #TARGET}（一个音频文件，或一个目录——递归找音频），
 * {@code ./mvnw test -Dtest=SongAudioBpmUtil}。
 * <b>控制台中文会被 surefire 转成乱码</b>，数字不受影响但路径没法看，
 * 目录批量核对请把 {@link #WRITE_REPORT} 打开（报告写 UTF-8）。
 *
 * <p>与 {@link SongMidiBpmUtil} 的分工：那个读 <b>mid / svp 里的元数据</b>——工程文件里的
 * 精确真值，只对拿去 SynthV 的那套文件有意义；这个从 <b>音频波形</b>推，用于成品、或手上
 * 只有音频的场合，结果必然带估计误差。填词导出 lrc 的时间戳仍按 svp 曲速算，本脚本不参与。
 */
public class SongAudioBpmUtil {

    /** 后端：BEAT_THIS = 深度学习（准，要 PyTorch）；AUBIO = 轻量二进制（快，精度一般） */
    private enum Backend { BEAT_THIS, AUBIO }

    private static final Backend BACKEND = Backend.BEAT_THIS;

    /** 要查的音频文件，或一个目录（递归查下面所有音频） */
    private static final String TARGET = "F:\\歌曲\\模板\\某首歌_某歌手";

    /** beat_this 的额外参数：纯 CPU 就留 "--gpu=-1"；有 CUDA 机可改 "" 或 "--float16" */
    private static final String BEAT_THIS_ARGS = "--gpu=-1";

    /** 报告文件（UTF-8，与控制台同一份内容）。控制台中文乱码，核对以这份为准 */
    private static final String REPORT = "F:\\歌曲\\音频曲速.txt";

    /** 是否写报告文件（单个文件在控制台看即可；目录批量建议打开） */
    private static final boolean WRITE_REPORT = false;

    /** 常用曲速区间：落在这之外时提示可能是倍 / 半速（octave error，只提示、不改结果） */
    private static final double COMMON_MIN = 80, COMMON_MAX = 160;

    /** 认得出的音频后缀 */
    private static final List<String> AUDIO_EXT = List.of(
            ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".aiff");

    /** 抓一行里第一个数字（见 {@link #parseBeats}） */
    private static final Pattern FIRST_NUMBER =
            Pattern.compile("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

    @Test
    public void bpm() throws Exception {
        Path target = Path.of(TARGET);
        List<Path> files;
        if (Files.isRegularFile(target)) {
            files = List.of(target);
        } else if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(SongAudioBpmUtil::isAudio)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
        } else {
            System.out.println("找不到：" + target);
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("后端：").append(BACKEND).append("  ·  来源：").append(target)
                .append("（音频 ").append(files.size()).append(" 个）\n");
        if (files.isEmpty()) {
            report.append("这个路径下没有音频文件（认这些后缀：")
                    .append(String.join(" ", AUDIO_EXT)).append("）\n");
            finish(report);
            return;
        }

        // 后端命令不在 PATH 上时一次说清，而不是每个文件失败一遍
        String missing = missingTool();
        if (missing != null) {
            report.append('\n').append(missing);
            finish(report);
            return;
        }

        for (Path file : files) {
            report.append('\n');
            try {
                one(file, report);
            } catch (Exception e) {
                report.append(file).append('\n')
                        .append("    失败：").append(e.getMessage()).append('\n');
            }
        }
        finish(report);
    }

    // ==================== 单个文件 ====================

    private static void one(Path audio, StringBuilder report) throws Exception {
        long t0 = System.currentTimeMillis();
        double[] beats = BACKEND == Backend.BEAT_THIS
                ? beatsViaBeatThis(audio) : beatsViaAubio(audio);
        long cost = System.currentTimeMillis() - t0;

        report.append(audio.getFileName()).append('\n');
        report.append("    ").append(audio).append('\n');
        if (beats.length < 4) {
            report.append("    拍点只有 ").append(beats.length)
                    .append(" 个（耗时 ").append(cost)
                    .append(" ms）—— 回归至少要点 4 个点，跳过\n");
            return;
        }

        Fit fit = fit(beats);
        int dp = decimalsFor(fit.bpmErr());
        report.append(String.format(Locale.ROOT,
                "    拍点 %d 个 · 覆盖 %.1f s · 耗时 %d ms%n",
                fit.n(), fit.spanSec(), cost));
        report.append(String.format(Locale.ROOT,
                "    回归 BPM    ：%s ± %s   （R²=%.6f，拍点残差 σ=%.1f ms）%n",
                num(fit.bpm(), dp), num(fit.bpmErr(), dp), fit.r2(), fit.sigmaMs()));
        report.append(String.format(Locale.ROOT,
                "    首尾两点    ：%.3f   （只用首末拍，对单个拍点的误差最敏感，仅作对照）%n",
                fit.firstLastBpm()));
        report.append(String.format(Locale.ROOT,
                "    间隔中位数  ：%.3f   （最抗离群拍，但看不出「拍点整体是否等距」）%n",
                fit.medianBpm()));
        report.append(String.format(Locale.ROOT,
                "    误差累积    ：每 100 秒约 %.1f ms（= 100 × δBPM ÷ BPM，回归口径的固有漂移）%n",
                100 * fit.bpmErr() / fit.bpm() * 1000));

        double norm = fit.bpm();
        while (norm < COMMON_MIN) {
            norm *= 2;
        }
        while (norm > COMMON_MAX) {
            norm /= 2;
        }
        if (Math.abs(norm - fit.bpm()) > 1e-9) {
            report.append(String.format(Locale.ROOT,
                    "    ! 不在常用区间 %.0f~%.0f，可能是倍 / 半速：×2 或 ÷2 后 ≈ %.3f%n",
                    COMMON_MIN, COMMON_MAX, norm));
        }
        if (fit.r2() < 0.998) {
            report.append(String.format(Locale.ROOT,
                    "    ! R² 偏低（%.6f）—— 变速 / 自由节奏，或后端漏拍多拍；这个值只当参考%n",
                    fit.r2()));
        }
    }

    // ==================== 回归 ====================

    /** 最小二乘拟合结果与诊断量。{@code bpmErr} 是 BPM 的标准误差，报告按它决定小数位。 */
    private record Fit(int n, double spanSec, double bpm, double bpmErr, double r2,
                       double sigmaMs, double firstLastBpm, double medianBpm) {
    }

    /**
     * 对拍点做最小二乘 {@code t_i = a + b·i}（{@code i} 为拍序号 0..n-1），{@code BPM = 60/b}。
     *
     * <p>取斜率而不是「首尾相减」：首尾只用两个点，任何一拍的定位误差都直接进结果；
     * 回归把误差平摊到全部拍点上。BPM 的不确定度按误差传播算：{@code δBPM = 60/b² · δb}，
     * 其中斜率标准误差 {@code δb = σ_残差 / √(Σ(i-ī)²)}。
     */
    private static Fit fit(double[] t) {
        int n = t.length;
        double xBar = (n - 1) / 2.0;
        double yBar = 0;
        for (double v : t) {
            yBar += v;
        }
        yBar /= n;

        double sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - xBar;
            sxx += dx * dx;
            sxy += dx * (t[i] - yBar);
        }
        double b = sxy / sxx;          // 每拍秒数
        double a = yBar - b * xBar;

        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < n; i++) {
            double e = t[i] - (a + b * i);
            ssRes += e * e;
            double d = t[i] - yBar;
            ssTot += d * d;
        }
        double r2 = ssTot > 0 ? 1 - ssRes / ssTot : 1;
        double sigma = n > 2 ? Math.sqrt(ssRes / (n - 2)) : 0;   // 拍点残差（秒）
        double seB = n > 2 ? sigma / Math.sqrt(sxx) : 0;         // 斜率标准误差（秒 / 拍）
        double bpm = 60 / b;
        double bpmErr = 60 / (b * b) * seB;

        double span = t[n - 1] - t[0];
        double[] iv = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            iv[i] = t[i + 1] - t[i];
        }
        Arrays.sort(iv);
        double median = iv.length % 2 == 1
                ? iv[iv.length / 2]
                : (iv[iv.length / 2 - 1] + iv[iv.length / 2]) / 2;
        return new Fit(n, span, bpm, bpmErr, r2, sigma * 1000,
                span > 0 ? 60 * (n - 1) / span : Double.NaN,
                median > 0 ? 60 / median : Double.NaN);
    }

    /** 按不确定度决定报几位小数：δ=0.005 → 3 位，δ=0.5 → 1 位。不报超出精度的位数。 */
    private static int decimalsFor(double delta) {
        if (!(delta > 0)) {
            return 1;
        }
        return Math.max(0, Math.min(6, (int) Math.ceil(-Math.log10(delta))));
    }

    /** 定点格式化并去掉多余的 0（128.02 而不是 128.020） */
    private static String num(double v, int dp) {
        String s = String.format(Locale.ROOT, "%." + dp + "f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    // ==================== 后端 ====================

    /** beat_this：拍点只走 `-o <文件>`，写完读回来再删。 */
    private static double[] beatsViaBeatThis(Path audio) throws Exception {
        Path out = Files.createTempFile("beat-this-", ".beats");
        try {
            List<String> cmd = new ArrayList<>(
                    List.of("beat_this", audio.toString(), "-o", out.toString()));
            for (String arg : BEAT_THIS_ARGS.trim().split("\\s+")) {
                if (!arg.isBlank()) {
                    cmd.add(arg);
                }
            }
            run(cmd, "beat_this");
            return parseBeats(Files.readString(out, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /** aubio：拍点直接走 stdout。 */
    private static double[] beatsViaAubio(Path audio) throws Exception {
        return parseBeats(run(List.of("aubio", "beat", audio.toString()), "aubio"));
    }

    /**
     * 跑外部命令：stdout 读回来，stderr 重定向到临时文件 —— 这两个后端都会往 stderr 写
     * 进度 / 警告，混进 stdout 会污染拍点解析。
     */
    private static String run(List<String> cmd, String name) throws Exception {
        Path errFile = Files.createTempFile("bpm-err-", ".txt");
        try {
            Process p = new ProcessBuilder(cmd).redirectError(errFile.toFile()).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                String err = Files.readString(errFile, StandardCharsets.UTF_8).strip();
                throw new IOException(name + " 退出码 " + code
                        + (err.isEmpty() ? "（stderr 空）" : "：" + tail(err, 300)));
            }
            return out;
        } finally {
            Files.deleteIfExists(errFile);
        }
    }

    /**
     * 解析拍点：<b>只认行首那个数</b>，这一行剩下的东西一概不看（两版输出行里都可能带别的列）。
     *
     * <p>用 {@code lookingAt} 而不是 {@code find}：后者会在行内任意位置找数，于是
     * {@code BPM=128.0} / {@code tempo: 128.0} 这类<b>汇总行会被当成一个 128 秒的拍点</b>，
     * 插进序列里既不递增也不在拍上，回归直接被带偏。行首限定把这类行天然挡在外面。
     * （万一某版后端把拍号写在前面，形如 {@code 1 0.512}，改这一处即可。）
     *
     * <p>最后统一按时间升序排：回归要求拍点单调，有的后端不保证输出有序。
     */
    private static double[] parseBeats(String text) {
        List<Double> secs = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = FIRST_NUMBER.matcher(line);
            if (m.lookingAt()) {
                try {
                    double v = Double.parseDouble(m.group());
                    if (Double.isFinite(v) && v >= 0) {
                        secs.add(v);
                    }
                } catch (NumberFormatException ignored) {
                    // 行首数字长得不像数（"1e999" 之类）——跳过这行
                }
            }
        }
        secs.sort(Double::compare);
        double[] arr = new double[secs.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = secs.get(i);
        }
        return arr;
    }

    // ==================== 环境 ====================

    /** 后端命令不在 PATH 上时返回一段给人看的提示（含安装方式），在就返回 null。 */
    private static String missingTool() {
        String cmd = BACKEND == Backend.BEAT_THIS ? "beat_this" : "aubio";
        if (onPath(cmd)) {
            return null;
        }
        String how = BACKEND == Backend.BEAT_THIS
                ? "    pip install beat-this          # 先装 PyTorch：https://pytorch.org\n"
                  + "    装完 `beat_this --help` 能列帮助就算好了；纯 CPU 也没问题（慢些）\n"
                : "    https://aubio.org/download      # 下 Windows 预编译包，解压后把目录加进 PATH\n"
                  + "    或 scoop install aubio / msys2 下 pacman -S mingw-w64-x86_64-aubio\n";
        return "PATH 上找不到命令 " + cmd + "，先装再用：" + "\n" + how
                + "装好后重开终端让 PATH 生效，再跑本脚本。\n"
                + "（也可以把 BACKEND 换成另一个后端再试）\n";
    }

    /** 命令能不能起来（起得来就算在；退出码不管，有的工具 --version 不是 0 退）。 */
    private static boolean onPath(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            return true;
        } catch (IOException e) {
            return false;              // 命令不存在
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== 杂项 ====================

    private static boolean isAudio(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return AUDIO_EXT.stream().anyMatch(name::endsWith);
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    private static void finish(StringBuilder report) throws IOException {
        System.out.print(report);
        if (WRITE_REPORT) {
            Files.writeString(Path.of(REPORT), report, StandardCharsets.UTF_8);
            System.out.println("\n报告：" + REPORT);
        }
    }
}
