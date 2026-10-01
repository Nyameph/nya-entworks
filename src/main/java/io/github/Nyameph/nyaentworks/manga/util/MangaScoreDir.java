package io.github.Nyameph.nyaentworks.manga.util;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评分分区目录名的解析与查找，纯静态。
 * <p>三处根目录下都用同一套分区命名，只差一个 {@code #}：
 * <pre>
 * F:\MangaGroup\9-百读不厌                   （归档，无 #）
 * F:\NetdiskDownload\#待看\#9-百读不厌         （新漫画）
 * F:\MangaIndie\#9-百读不厌    （未归档）
 * </pre>
 * <p><b>评分存在目录名里，不存在别处</b> —— 这是既有做法（{@code scanAndArchiveMangas}
 * 的 {@code scanRootScoreMap} 就是手写的目录→评分表），所以「打分」这个动作
 * 落到磁盘上就是「把漫画移进对应的分区目录」。好处是评分在资源管理器里也看得见，
 * 且新漫画不入库时评分依然有地方存。
 */
public final class MangaScoreDir {

    private MangaScoreDir() {
    }

    /** 需求定的四挡评分，顺序即页面上的展示顺序（高分在前，与归档根一致） */
    public static final int[] SCORES = {9, 7, 5, 3};

    /** {@code #9-百读不厌} 或 {@code 9-百读不厌}，取开头的数字 */
    private static final Pattern SCORE_DIR_PATTERN = Pattern.compile("^#?(\\d+)\\s*-");

    /**
     * 从分区目录名取评分。
     *
     * @return 评分；不是分区目录形态时返回 {@code null}
     */
    public static Integer parseScore(String dirName) {
        if (StringUtils.isBlank(dirName)) {
            return null;
        }
        Matcher matcher = SCORE_DIR_PATTERN.matcher(dirName.trim());
        if (!matcher.find()) {
            return null;
        }
        int score = Integer.parseInt(matcher.group(1));
        for (int valid : SCORES) {
            if (valid == score) {
                return score;
            }
        }
        // 3/5/7/9 之外的数字开头目录（如 #toWriter 之类）不算分区
        return null;
    }

    /**
     * 某个根目录下的「评分 → 分区目录」。
     * <p>按磁盘上实际存在的目录建表，而不是拿评分拼名字 —— 分区目录名后半段
     * （{@code 百读不厌}）是人起的，代码里不该有第二份。
     */
    public static Map<Integer, Path> scoreDirs(String rootPath) {
        Map<Integer, Path> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(rootPath)) {
            return result;
        }
        File[] children = new File(rootPath).listFiles(File::isDirectory);
        if (children == null) {
            return result;
        }
        for (int score : SCORES) {
            for (File child : children) {
                if (Integer.valueOf(score).equals(parseScore(child.getName()))) {
                    result.put(score, child.toPath());
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 归档根 → 评分 的映射：把根下<b>实际存在的</b>分区目录翻成
     * 「分区目录全路径 → 评分」，供归档匹配、同步、环境自检共用。
     *
     * <p>为什么按磁盘扫、而不是拿评分拼名字：分区名后半段（{@code 百读不厌} 之类）
     * 是人起的，代码里不该有第二份 —— 同 {@link #scoreDirs}。原先这份映射是
     * {@code MangaNameParser} 里写死的四条 {@code F:\MangaGroup\…}，
     * 换成配置里的归档根之后，归档根搬到哪个盘、分区叫什么，都不必再改代码。
     *
     * <p>键是 {@link Path#toString()}，与库里 {@code manga_archive_unit.root_path}
     * 存的值同口径 —— 那边会直接拿字符串来查这张表（如改名时的「目标根」校验）。
     */
    public static Map<String, Integer> rootScoreMap(String archiveRoot) {
        Map<String, Integer> result = new LinkedHashMap<>();
        scoreDirs(archiveRoot).forEach((score, dir) -> result.put(dir.toString(), score));
        return result;
    }

    /**
     * 某个评分对应的分区目录，<b>缺这一档时不抛异常、也不建目录</b>，
     * 而是返回一个裸分数字目录（{@code #9-}）—— 由调用方在真正搬漫画时
     * {@code Files.createDirectories} 建出来。
     *
     * <p>分区名后半段（{@code 百读不厌}）是磁盘权威、代码里没有第二份，所以自动建出来的
     * 目录名只有分数字加横线，人在资源管理器里改名补评语即可（见 {@link #scoreDirs}
     * 那条「代码里不该有第二份」的理由）。
     *
     * <p><b>漫画这一侧只有这一个入口</b>（歌曲 / 喊麦那边才有「缺档即拦」的
     * {@code ScorePartition#requireDir}）：漫画没有「导入」那条路，
     * 往各根下写分区只有打分（归档）这一个动作，而它缺档就会自己建出来。
     * 归档根（{@code F:\MangaGroup}）缺档仍由 {@code sync} 整个拒跑拦着，那一条没变。
     *
     * @throws IllegalStateException 根目录不是目录（盘没挂、根配错）
     */
    public static Path resolveScoreDir(String rootPath, int score) {
        Path dir = scoreDirs(rootPath).get(score);
        if (dir != null) {
            return dir;
        }
        if (!Files.isDirectory(Path.of(StringUtils.defaultString(rootPath)))) {
            throw new IllegalStateException(missingMessage(rootPath, score));
        }
        return Path.of(rootPath, "#" + score + "-");
    }

    /** 「找不到 N 分」的原话（根目录不是目录时抛出去的那一句） */
    private static String missingMessage(String rootPath, int score) {
        return "在 " + rootPath + " 下找不到 " + score
                + " 分的分区目录（形如 #" + score + "-…）。外挂盘没挂上，或根目录配错了";
    }
}
