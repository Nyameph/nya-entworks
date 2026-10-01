package io.github.Nyameph.nyaentworks.common.lyric;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词解析（实现说明 5.8）。纯函数，输入已经解码好的文本。
 *
 * <p>放在 {@code common} 而不是 {@code song} 下：填词歌曲与喊麦两个模块都要读歌词。
 *
 * <p>三种格式统一成 {@link LyricLine}，差别只在解析：
 * <ul>
 *   <li><b>LRC</b>：{@code [mm:ss.xx]}。一行可有多个时间戳（展开成多行）；
 *       {@code [offset:±ms]} 要应用；{@code [ti:]} 等元信息跳过；增强型逐字
 *       {@code <mm:ss.xx>} 剥掉只做行级。无结束时间，用下一行开始时间补。
 *   <li><b>SRT</b>：自带起止。<b>有 gap</b> —— gap 期间没有当前行，这是它与 LRC 的
 *       唯一行为差异，前端不能假设「总有一行是当前行」。
 *   <li><b>TXT</b>：无时间轴，整体返回一行 {@code start = null}。
 * </ul>
 *
 * <p><b>不引 lrc-kit / lrc-file-parser</b>：都是 npm 包（本项目无构建链要手工
 * vendoring），都不管 SRT 与 TXT，且 lrc-file-parser 的内部定时器与倍速冲突。
 * 行级解析本身就是下面这几十行。
 */
public final class LyricParser {

    private LyricParser() {
    }

    /** {@code [mm:ss.xx]} / {@code [mm:ss]} / {@code [h:mm:ss.xxx]}，小数点也允许冒号 */
    private static final Pattern LRC_TIME = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    /** {@code [offset:+500]} / {@code [offset:-500]}，单位毫秒 */
    private static final Pattern LRC_OFFSET = Pattern.compile(
            "\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    /** 增强型 LRC 的逐字时间戳 {@code <mm:ss.xx>}，行级解析时剥掉 */
    private static final Pattern LRC_WORD_TIME = Pattern.compile(
            "<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    /** SRT 时间行 {@code 00:00:12,000 --> 00:00:15,000}，毫秒分隔符允许 , 或 . */
    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*"
                    + "(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");

    /**
     * 按扩展名解析。
     *
     * @param extension 小写扩展名，不含点
     * @return 歌词行；{@code ass} 等不支持的格式返回空列表（调用方据此报「格式不支持」）
     */
    public static List<LyricLine> parse(String text, String extension) {
        if (text == null) {
            return List.of();
        }
        // 磁盘上偶有纯 CR 换行（老 Mac 格式，实测有）：下面三个 parse 都按 \r?\n 切，
        // 先归一化，否则纯 CR 的文件整个被当成一行。\r\n 必须先于 \r 转，否则会拆成两个 \n。
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return switch (extension) {
            case "lrc" -> parseLrc(normalized);
            case "srt" -> parseSrt(normalized);
            case "txt" -> parseTxt(normalized);
            default -> List.of();
        };
    }

    /**
     * LRC。一行多时间戳要展开：{@code [00:12.00][01:30.00]副歌} 是两行歌词。
     * <p>结束时间用下一行的开始时间补 —— LRC 本身不表达结束，而前端要靠
     * {@code [start, end)} 判「当前是哪一行」。
     */
    public static List<LyricLine> parseLrc(String text) {
        double offsetSeconds = 0;
        Matcher offsetMatcher = LRC_OFFSET.matcher(text);
        if (offsetMatcher.find()) {
            // offset 是「歌词整体提前/推后多少毫秒」，正值表示歌词该更早出现，
            // 所以是减而不是加（与多数播放器一致）
            offsetSeconds = -Integer.parseInt(offsetMatcher.group(1)) / 1000.0;
        }

        List<LyricLine> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            Matcher timeMatcher = LRC_TIME.matcher(raw);
            List<Double> stamps = new ArrayList<>();
            int contentStart = 0;
            while (timeMatcher.find()) {
                // 时间戳必须是连续前缀：中间夹了文字之后的 [..] 不算时间戳
                if (timeMatcher.start() != contentStart) {
                    break;
                }
                stamps.add(toSeconds(timeMatcher.group(1), timeMatcher.group(2), timeMatcher.group(3)));
                contentStart = timeMatcher.end();
            }
            if (stamps.isEmpty()) {
                // 没有时间戳的行是元信息（[ti:]/[offset:]）或空行，跳过
                continue;
            }
            String content = LRC_WORD_TIME.matcher(raw.substring(contentStart)).replaceAll("").trim();
            for (double stamp : stamps) {
                double start = Math.max(0, stamp + offsetSeconds);
                lines.add(new LyricLine(start, null, content));
            }
        }

        lines.sort(Comparator.comparingDouble(LyricLine::start));
        return fillEnds(lines);
    }

    /**
     * SRT。自带起止时间，所以<b>不</b>调 {@link #fillEnds} —— gap 是真实的，
     * 填掉就等于把「这段没有字幕」改成「上一句一直挂着」。
     */
    public static List<LyricLine> parseSrt(String text) {
        List<LyricLine> lines = new ArrayList<>();
        // 按空行分块。序号行可有可无，所以不靠它切
        for (String block : text.split("\\r?\\n\\s*\\r?\\n")) {
            Matcher matcher = SRT_TIME.matcher(block);
            if (!matcher.find()) {
                continue;
            }
            double start = toSeconds(matcher.group(1), matcher.group(2),
                    matcher.group(3), matcher.group(4));
            double end = toSeconds(matcher.group(5), matcher.group(6),
                    matcher.group(7), matcher.group(8));

            // 时间行之后的都是文本，可能多行（srt 允许一条字幕两行）
            String afterTime = block.substring(matcher.end());
            List<String> textLines = new ArrayList<>();
            for (String piece : afterTime.split("\\r?\\n")) {
                String one = piece.trim();
                if (!one.isEmpty()) {
                    textLines.add(one);
                }
            }
            if (textLines.isEmpty()) {
                continue;
            }
            lines.add(new LyricLine(start, end, String.join(" ", textLines)));
        }
        lines.sort(Comparator.comparingDouble(LyricLine::start));
        return lines;
    }

    /**
     * TXT。没有时间轴，<b>不假装能同步</b>：按行返回但 start 全为 null，
     * 前端据此降级成可滚动的纯文本并标明「无时间轴」。
     */
    public static List<LyricLine> parseTxt(String text) {
        List<LyricLine> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            lines.add(new LyricLine(null, null, raw.trim()));
        }
        // 结尾的空行去掉，开头与中间的留着（排版的一部分）
        while (!lines.isEmpty() && StringUtils.isBlank(lines.getLast().text())) {
            lines.removeLast();
        }
        return lines;
    }

    /** 用下一行的开始时间当上一行的结束时间，最后一行留 null（到文件结束） */
    private static List<LyricLine> fillEnds(List<LyricLine> lines) {
        List<LyricLine> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            Double end = i + 1 < lines.size() ? lines.get(i + 1).start() : null;
            result.add(new LyricLine(line.start(), end, line.text()));
        }
        return result;
    }

    private static double toSeconds(String minutes, String seconds, String fraction) {
        double result = Integer.parseInt(minutes) * 60.0 + Integer.parseInt(seconds);
        return result + fractionToSeconds(fraction);
    }

    private static double toSeconds(String hours, String minutes, String seconds, String millis) {
        return Integer.parseInt(hours) * 3600.0
                + Integer.parseInt(minutes) * 60.0
                + Integer.parseInt(seconds)
                + fractionToSeconds(millis);
    }

    /**
     * 小数部分按位数定标：{@code .5} 是 0.5 秒、{@code .50} 也是 0.5 秒、
     * {@code .500} 是 0.5 秒。LRC 常写两位（百分秒），SRT 写三位（毫秒），
     * 按长度算而不是固定除以 1000。
     */
    private static double fractionToSeconds(String fraction) {
        if (StringUtils.isBlank(fraction)) {
            return 0;
        }
        return Integer.parseInt(fraction) / Math.pow(10, fraction.length());
    }
}
