package io.github.Nyameph.nyaentworks.common.media;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评分分区目录名的解析（实现说明 5.7）。纯静态。
 *
 * <pre>
 * #1勉强  #3流畅  #5可用  #7佳作  #9超赞
 * </pre>
 *
 * <p>放在 {@code common} 而不是某个模块里：填词歌曲（{@code 成品-歌曲}）与喊麦
 * （{@code 成品-喊麦}）的分区形态完全一样，扫的都是同一套目录名。
 *
 * <p>与漫画的 {@code MangaScoreDir} 分开写，因为形态不同：漫画是 {@code #9-百读不厌}
 * （数字后跟横线），歌曲是 {@code #9超赞}（数字后直接跟评语）。
 *
 * <p><b>一个易错点</b>（进了实现说明第 7 节的坑列表）：
 * <ul>
 *   <li>歌曲根下有 {@code #统计.xlsx} 与 {@code #评分标准 & 计划.txt} 两个<b>文件</b>
 *       也以 {@code #} 开头，所以只认目录。
 * </ul>
 */
public final class ScorePartition {

    private ScorePartition() {
    }

    /** 需求定的五挡评分，顺序即页面展示顺序（高分在前） */
    public static final int[] SCORES = {9, 7, 5, 3, 1};

    /** {@code #9超赞}：# + 数字 + 评语 */
    private static final Pattern PARTITION_PATTERN = Pattern.compile("^#(\\d+)(.*)$");

    /**
     * 一个评分分区。
     *
     * @param dirName        目录名原文，如 {@code #9超赞}
     * @param score          评分
     * @param label          评语，如 {@code 超赞}
     */
    public record Partition(String dirName, int score, String label) {

        /**
         * 页面展示用：{@code 9 分 超赞}。
         * <p>没有评语时只出 {@code 9 分} —— 自动建出来的裸分数字目录（{@code #9}）
         * 就是这一种，末尾多一个空格纯属多余。
         */
        public String display() {
            return label.isBlank() ? score + " 分" : score + " 分 " + label;
        }
    }

    /**
     * 页面用的分区视图 —— 歌曲与喊麦的 {@code /partitions} 下发的是同一个形态。
     *
     * <p>不直接下发 {@link Partition}：Jackson 序列化 record 只认它的「组件」，
     * {@link Partition#display()} 这种额外方法不在其中，前端拿不到，于是就得自己再拼一遍
     * 「9 分 超赞」—— 那就是第二份规则了（原先只有歌曲侧有这个 record，
     * 喊麦侧手工拼 {@code Map}，2026-09-29 收成这一份）。
     *
     * @param onDisk 这一档目录<b>此刻真在磁盘上</b>没有。{@code false} 的是
     *               {@link #rows} 按 {@link #SCORES} 补齐的空档（目录名只有分数字），
     *               打分选它会在落盘时现建出来；筛选用它把空档滤掉
     *               （列一个永远空的筛选项只是干扰），打分按钮则要画出来
     */
    public record Row(String dirName, int score, String label, String display, boolean onDisk) {

        public static Row of(Partition partition) {
            return new Row(partition.dirName(), partition.score(),
                    partition.label(), partition.display(), true);
        }

        /** 磁盘上还没有的那一档：目录名只有分数字，没有评语（评语是磁盘权威，见 {@link #resolveDir}） */
        private static Row missing(int score) {
            Partition partition = new Partition("#" + score, score, "");
            return new Row(partition.dirName(), score, "", partition.display(), false);
        }
    }

    /**
     * 页面上「可选档位」的完整列表：磁盘上真有的用它的目录名与评语，没建的补一条裸分数字
     * （{@link Row#onDisk()} 为 {@code false}）。
     *
     * <p>为什么补齐：打分时缺的那一档会自动建出来（{@link #resolveDir}），
     * 可列表是照磁盘扫的，不补的话<b>页面上根本没有那个按钮</b> ——
     * 「自动建」就成了永远走不到的一行代码（有人根本没法给一首歌打 1 分，
     * 只因为从没建过 {@code #1勉强}）。
     *
     * <p>根目录不是目录时返回空列表（盘没挂 / 根配错）：那时连落盘都走不到，
     * 画一排按下去只会报错的按钮还不如一个都不画 —— 页面本来就有红字说明根不在。
     *
     * @return 按 {@link #SCORES} 的顺序（高分在前）
     */
    public static List<Row> rows(String rootPath) {
        List<Row> result = new ArrayList<>();
        if (!Files.isDirectory(Path.of(StringUtils.defaultString(rootPath)))) {
            return result;
        }
        List<Partition> existing = list(rootPath);
        for (int score : SCORES) {
            Partition partition = null;
            for (Partition candidate : existing) {
                if (candidate.score() == score) {
                    partition = candidate;
                    break;
                }
            }
            result.add(partition == null ? Row.missing(score) : Row.of(partition));
        }
        return result;
    }

    /**
     * 解析分区目录名。
     *
     * @return 分区；不是分区形态或分数不在 {@link #SCORES} 里时返回 {@code null}
     */
    public static Partition parse(String dirName) {
        if (StringUtils.isBlank(dirName)) {
            return null;
        }
        Matcher matcher = PARTITION_PATTERN.matcher(dirName.trim());
        if (!matcher.matches()) {
            return null;
        }
        int score;
        try {
            score = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        boolean valid = false;
        for (int s : SCORES) {
            if (s == score) {
                valid = true;
                break;
            }
        }
        if (!valid) {
            // 1/3/5/7/9 之外的数字开头目录不算分区
            return null;
        }
        String label = matcher.group(2).trim();
        return new Partition(dirName.trim(), score, label);
    }

    /**
     * 某个根目录下实际存在的分区，按评分降序。
     * <p>按磁盘实际存在的目录建表而不是拿评分拼名字：评语（{@code 超赞}）是人起的，
     * 代码里不该有第二份（同 {@code MangaScoreDir.scoreDirs} 的理由）。
     *
     * @return 分区列表；根目录不存在时返回空列表
     */
    public static List<Partition> list(String rootPath) {
        List<Partition> result = new ArrayList<>();
        if (StringUtils.isBlank(rootPath)) {
            return result;
        }
        // 只列目录 —— 根下的 #统计.xlsx 也以 # 开头
        File[] children = new File(rootPath).listFiles(File::isDirectory);
        if (children == null) {
            return result;
        }
        for (File child : children) {
            Partition partition = parse(child.getName());
            if (partition != null) {
                result.add(partition);
            }
        }
        result.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return result;
    }

    /**
     * 找 {@code score} 对应的分区目录。
     * <p><b>目录不存在时抛异常而不是新建</b>：这一条给「导入 / 添加文件」用 ——
     * 那时文件还没落盘，自动建会在打错字时造出一个假分区，而文件就搬进那个将来
     * 找不到的地方去了（同 {@code MangaScoreDir} 的立场，文档 8.4）。
     * <p><b>打分</b>（已经在用这个程序的人的页面上）走 {@link #resolveDir}：缺档时给一个
     * 裸分数字目录（{@code #9}），由 {@code GroupFileOps.moveAll} 落盘时建出来。
     */
    public static Path requireDir(String rootPath, int score) {
        Partition partition = find(rootPath, score);
        if (partition == null) {
            throw new IllegalStateException(missingMessage(rootPath, score));
        }
        return Path.of(rootPath, partition.dirName());
    }

    /**
     * 找 {@code score} 对应的分区目录，<b>缺这一档时不抛异常、也不建目录</b>，
     * 而是返回一个裸分数字目录（{@code #9}）—— 由调用方在真正搬文件时
     * {@code Files.createDirectories} 建出来。
     *
     * <p>建在这一刻（落盘前）而不是读列表时：预演 / 扫描阶段必须是只读的。
     * 评语（{@code 超赞}）是磁盘权威、代码里没有第二份，所以自动建出来的目录名
     * 只有分数字，人在资源管理器里改名补评语即可 —— 建目录时仍带着评语就等于
     * 在代码里写死了别档的评语（9 分是超赞、1 分是勉强，照抄一个就是错的）。
     *
     * @throws IllegalStateException 根目录不是目录（盘没挂、根配错）——
     *                               这时连往前一步都不该走，更不能建目录
     */
    public static Path resolveDir(String rootPath, int score) {
        Partition partition = find(rootPath, score);
        if (partition != null) {
            return Path.of(rootPath, partition.dirName());
        }
        if (!Files.isDirectory(Path.of(StringUtils.defaultString(rootPath)))) {
            throw new IllegalStateException(missingMessage(rootPath, score));
        }
        return Path.of(rootPath, "#" + score);
    }

    /** 根目录下 {@code score} 对应的分区；没有这一档返回 {@code null} */
    private static Partition find(String rootPath, int score) {
        for (Partition partition : list(rootPath)) {
            if (partition.score() == score) {
                return partition;
            }
        }
        return null;
    }

    /** 「找不到 N 分」的原话，两条路（requireDir / resolveDir 的根不在）共用一份 */
    private static String missingMessage(String rootPath, int score) {
        // 例子里的评语写成占位符而不是「超赞」：评语是自定的，各档不一样
        // （9 分是超赞、1 分是勉强），照抄一个别档的评语建目录就建错了
        return "在 " + rootPath + " 下找不到 " + score + " 分"
                + "的分区目录（形如 #" + score + "<评语>）。"
                + "分区目录是手工建的，缺了说明外挂盘没挂上、根目录配错了，或这一档还没建";
    }
}
