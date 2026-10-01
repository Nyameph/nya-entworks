package io.github.Nyameph.nyaentworks.shout.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扫磁盘、归组（喊麦）。
 *
 * <p><b>不入库，每次现扫</b>（同歌曲列表层与「新漫画不入库」的理由）：喊麦只有几十组，
 * 扫一次是毫秒级，存一份就要维护一致性。库里两张设置表（倍速 / 标签）按需写。
 *
 * <p>一组 = <b>去扩展名后同名</b>的那一批文件（视频 / 音频 / 歌词，平铺在目录里）。
 * 与歌曲最大的不同是<b>不解析文件名</b>：主名就是全部身份 —— 没有作者/曲名/原曲名的拆解，
 * 也就没有跨主名的归并、没有规范化命名、没有版本号语义。主名里的 {@code #} 是普通字符
 * （歌曲那边它是版本号）。
 *
 * <p>{@link #groupFiles} 是纯函数：给一串文件名就能归组，测试直接调它（扫描是整组搬动的
 * 入口，这一段必须有测试兜底）。
 */
@Service
@RequiredArgsConstructor
public class ShoutGroupService {

    private final ShoutProperties properties;

    /**
     * 一组喊麦文件。
     *
     * @param root          所在根目录
     * @param partitionName 评分分区目录名；{@code null} 表示在待打分区（没有评分）
     * @param score         评分；待打分区为 {@code null}
     * @param mainName      去扩展名后的主名，组的身份，也是标签/倍速的键
     * @param videoFile     视频文件名，没有则 {@code null}
     * @param audioFile     音频文件名，没有则 {@code null}
     * @param lyricFiles    歌词文件名，可能多个
     */
    public record ShoutGroup(String root,
                             String partitionName,
                             Integer score,
                             String mainName,
                             String videoFile,
                             String audioFile,
                             List<String> lyricFiles) {

        /** 组所在目录：待打分区时 {@code partitionName} 为 null，就是根本身 */
        public Path dir() {
            return partitionName == null ? Path.of(root) : Path.of(root, partitionName);
        }

        /** 组的稳定标识，前端拿它回传（分区可能为 null，用空串占位） */
        public String key() {
            return StringUtils.defaultString(partitionName) + "|" + mainName;
        }

        public boolean hasVideo() {
            return videoFile != null;
        }

        public boolean hasAudio() {
            return audioFile != null;
        }

        /** 播放优先用视频，没有视频才用音频 */
        public String playFile() {
            return videoFile != null ? videoFile : audioFile;
        }

        /** 组内全部文件名，改评分/改名/删除都要整组处理。顺序契约见 {@link GroupFileOps#entries} */
        public List<String> allFiles() {
            return GroupFileOps.allFiles(videoFile, audioFile, lyricFiles);
        }
    }

    /** 已归档根 */
    public String archivedRoot() {
        return properties.getArchivedDir();
    }

    /** 待打分根（{@code F:\NetdiskDownload\#已压缩歌曲\喊麦}）—— 配置项本身就是完整路径 */
    public Path stagingRoot() {
        return Path.of(properties.getStagingDir());
    }

    /**
     * 扫已归档的全部组。
     *
     * @param partitionName 只看某个分区；空则全部分区
     */
    public List<ShoutGroup> listArchived(String partitionName) {
        String root = archivedRoot();
        List<ShoutGroup> result = new ArrayList<>();
        for (ScorePartition.Partition partition : ScorePartition.list(root)) {
            if (StringUtils.isNotBlank(partitionName)
                    && !partition.dirName().equals(partitionName)) {
                continue;
            }
            result.addAll(scanDir(root, partition.dirName(),
                    partition.score(), Path.of(root, partition.dirName())));
        }
        return result;
    }

    /** 扫待打分区的组。这一层没有分区，{@code partitionName} 与 {@code score} 都是 null */
    public List<ShoutGroup> listStaging() {
        Path dir = stagingRoot();
        return scanDir(null, null, null, dir);
    }

    /**
     * 扫一个任意目录并归组（不判它是不是受管根、不递归）。
     *
     * <p>「添加文件」的联动展开用：用户在受管根之外选了一个文件，同目录里同主名的
     * 那批要一起进列表。复用这里而不自己写一套归组 —— 自己写的下场是「联动进来的
     * 那批」与「扫出来的组」口径不一致（同 {@code SongGroupService#scanDirectory} 的理由）。
     *
     * <p>注意 {@link #groupFiles} 会丢掉「只有歌词没有媒体」的组 —— 调用方
     * （{@code ShoutImportService.expand}）要保留用户选中的锚点文件，别只信这里的返回。
     */
    public List<ShoutGroup> scanDirectory(Path dir) {
        return scanDir(dir.toString(), null, null, dir);
    }

    /** 扫一个目录并归组。不递归 —— 分区下的文件是平铺的 */
    private List<ShoutGroup> scanDir(String root, String partitionName,
                                     Integer score, Path dir) {
        File[] files = dir.toFile().listFiles(File::isFile);
        if (files == null) {
            return List.of();
        }
        List<String> fileNames = new ArrayList<>(files.length);
        for (File file : files) {
            fileNames.add(file.getName());
        }
        return groupFiles(fileNames, root == null ? dir.toString() : root, partitionName, score);
    }

    /**
     * 把一批文件名归成组（纯函数，不碰磁盘）。
     *
     * <p>规则：认识的扩展名才要（{@code #统计.xlsx} 之类不是喊麦文件）→ 按去扩展名的主名
     * 归一批 → 分视频 / 音频 / 歌词 → <b>只有歌词的组跳过</b>（播不了、也不该占一行）
     * → 按主名字典序排。
     *
     * @param root          组记录里的根（已归档根，或待打分根自己）
     * @param partitionName 分区名；待打分区传 {@code null}
     */
    public static List<ShoutGroup> groupFiles(List<String> fileNames, String root,
                                              String partitionName, Integer score) {
        // 主名 → 组内文件。LinkedHashMap 保住磁盘顺序，排序统一在最后做
        Map<String, List<String>> byMainName = new LinkedHashMap<>();
        for (String fileName : fileNames) {
            if (StringUtils.isBlank(fileName) || !MediaExtensions.isKnown(fileName)) {
                continue;
            }
            byMainName.computeIfAbsent(MediaExtensions.mainName(fileName), k -> new ArrayList<>())
                    .add(fileName);
        }

        List<ShoutGroup> groups = new ArrayList<>(byMainName.size());
        for (Map.Entry<String, List<String>> entry : byMainName.entrySet()) {
            String video = null;
            String audio = null;
            List<String> lyrics = new ArrayList<>();
            for (String fileName : entry.getValue()) {
                if (MediaExtensions.isVideo(fileName)) {
                    video = fileName;
                } else if (MediaExtensions.isAudio(fileName)) {
                    audio = fileName;
                } else {
                    lyrics.add(fileName);
                }
            }
            if (video == null && audio == null) {
                // 只有歌词没有媒体：播不了，也不该占一行。属于遗留的孤儿歌词
                continue;
            }
            lyrics.sort(Comparator.naturalOrder());
            groups.add(new ShoutGroup(root, partitionName, score, entry.getKey(),
                    video, audio, List.copyOf(lyrics)));
        }
        groups.sort(Comparator.comparing(g -> g.mainName().toLowerCase(Locale.ROOT)));
        return groups;
    }

    /**
     * 按主名找一组，找不到抛异常。
     * <p>前端回传的是 {@code (分区, 主名)} 而不是整组：组的内容随磁盘变，回传的那份可能
     * 已经过期，现扫一遍才是当前状态。
     */
    public ShoutGroup require(String partitionName, String mainName) {
        List<ShoutGroup> candidates = StringUtils.isBlank(partitionName)
                ? listStaging()
                : listArchived(partitionName);
        for (ShoutGroup group : candidates) {
            if (group.mainName().equals(mainName)) {
                return group;
            }
        }
        throw new IllegalStateException("找不到这一组："
                + StringUtils.defaultIfBlank(partitionName, "待打分") + " / " + mainName
                + "。多半是磁盘上已经改名或移走了，刷新一下列表");
    }
}
