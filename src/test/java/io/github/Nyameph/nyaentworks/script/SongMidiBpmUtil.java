package io.github.Nyameph.nyaentworks.script;

import io.github.Nyameph.nyaentworks.song.service.SongTemplateService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 查指定 .mid 的曲速（一次性脚本，只读：不改盘、不写库）。
 *
 * <p>把三级来源全摊开，一眼看出扫描会用哪个：mid <b>文件内容</b>里的 tempo 元事件
 * （{@link SongTemplateService#bpmFromMidi}）、同目录 svp 工程的曲速
 * （{@link SongTemplateService#bpmFromSvp}，工程内权威、扫描时优先）、mid <b>文件名</b>
 * 兜底（{@link SongTemplateService#bpmFromFileName}）。写库口径 = svp ＞ 内容 ＞ 文件名。
 *
 * <p><b>用法</b>：改 {@link #TARGET} —— 填一个 .mid 看单个文件的三个来源，
 * 填目录则递归列出下面所有 .mid（一行一个）。{@code ./mvnw test -Dtest=SongMidiBpmUtil}。
 * 会被转成乱码，数字不受影响但路径没法看，核对请开报告文件。
 *
 * <p>与 {@code SongTemplateBpmTest} 的分工：那个用造出来的字节守解析逻辑（回归网），
 * 这个用盘上的真文件回答「这一首到底是多少」。
 */
public class SongMidiBpmUtil {

    /** 要查的 .mid 文件，或一个目录（递归查下面所有 .mid / .midi） */
    private static final String TARGET = "F:\\歌曲\\模板\\某首歌_某歌手";

    /** 报告文件（UTF-8，与控制台同一份内容） */
//    private static final String REPORT = "F:\\歌曲\\mid曲速.txt";

    @Test
    public void bpm() throws IOException {
        Path target = Path.of(TARGET);
        List<Path> mids;
        if (Files.isRegularFile(target)) {
            mids = List.of(target);
        } else if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                mids = walk.filter(Files::isRegularFile)
                        .filter(SongMidiBpmUtil::isMidi)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
        } else {
            System.out.println("找不到：" + target);
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("来源：").append(target).append("（.mid ").append(mids.size()).append(" 个）\n");
        if (mids.size() == 1) {
            one(mids.get(0), report);
        } else {
            for (Path mid : mids) {
                BigDecimal content = SongTemplateService.bpmFromMidi(mid);
                BigDecimal name = SongTemplateService.bpmFromFileName(mid.getFileName().toString());
                report.append(String.format("内容 %-10s 文件 %-10s %s%n",
                        content == null ? "读不到" : plain(content),
                        name == null ? "读不到" : plain(name),
                        mid));
            }
            report.append("（写库口径：svp ＞ 内容 ＞ 文件名；目录里有没有 svp 用单个文件模式看）\n");
        }

//        Files.writeString(Path.of(REPORT), report, StandardCharsets.UTF_8);
        System.out.print(report);
//        System.out.println("报告：" + REPORT);
    }

    /** 单个文件：三个来源 + 扫描最终会写库的值 */
    private static void one(Path mid, StringBuilder report) throws IOException {
        BigDecimal content = SongTemplateService.bpmFromMidi(mid);
        BigDecimal name = SongTemplateService.bpmFromFileName(mid.getFileName().toString());
        Path svp = siblingSvp(mid);
        BigDecimal fromSvp = svp == null ? null : SongTemplateService.bpmFromSvp(svp);

        report.append("文件          ：").append(mid).append('\n');
        report.append("mid 内容      ：").append(content == null
                ? "读不到（文件里没有 tempo 元事件 / 截断 / 不是 MIDI）" : plain(content)).append('\n');
        report.append("mid 文件名    ：").append(name == null ? "读不到" : plain(name)).append('\n');
        report.append("同目录 svp    ：").append(svp == null ? "没有 svp"
                : svp.getFileName() + " → " + (fromSvp == null ? "读不到" : plain(fromSvp))).append('\n');
        BigDecimal effective = fromSvp != null ? fromSvp : content != null ? content : name;
        report.append("扫描会写库的值：")
                .append(effective == null ? "null（三级都读不到）" : plain(effective)).append('\n');
    }

    /** 同目录里的第一个 .svp（扫描时它优先于 mid；多个时按文件名排序取第一个） */
    private static Path siblingSvp(Path mid) throws IOException {
        Path dir = mid.getParent();
        if (dir == null) {
            return null;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".svp"))
                    .min(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static boolean isMidi(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".mid") || name.endsWith(".midi");
    }

    /** 抹掉 BigDecimal 的标度尾巴（1.2E+2 / 120.00 → 120） */
    private static String plain(BigDecimal bpm) {
        return bpm == null ? "-" : bpm.stripTrailingZeros().toPlainString();
    }
}
