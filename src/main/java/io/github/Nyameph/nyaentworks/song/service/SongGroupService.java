package io.github.Nyameph.nyaentworks.song.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扫磁盘、归组（实现说明 3.4 / 5.6）。
 *
 * <p><b>不入库，每次现扫</b>（同「新漫画不入库」的理由，文档 4.3）：这一层的评分与
 * 文件名都还在变，存一份就要维护一致性，而扫一次只要几百毫秒。库里只有两张设置表。
 *
 * <p><b>一组 = 去扩展名后同名的那一批文件</b>（文档 4.11）。它们平铺在分区目录下，
 * 没有子目录包着，所以归组只能靠名字。版本号属于组的身份 ——
 * {@code 口是心非.mp4} 与 {@code 口是心非#2.mp4} 是两组。
 */
@Service
@RequiredArgsConstructor
public class SongGroupService {

    private final SongProperties properties;

    /**
     * 一组文件。
     *
     * @param root          所在根目录
     * @param partitionName 评分分区目录名；{@code null} 表示在待打分区（没有评分）
     * @param score         评分；待打分区为 {@code null}
     * @param mainName      去扩展名后的主名，<b>含版本号</b>，组的身份
     * @param name          文件名解析结果；{@code parsed()} 为 false 表示解析失败
     * @param videoFile     视频文件名，没有则 {@code null}
     * @param audioFile     音频文件名，没有则 {@code null}
     * @param lyricFiles    歌词文件名，可能多个（同时有 lrc 与 srt）
     */
    public record SongGroup(String root,
                            String partitionName,
                            Integer score,
                            String mainName,
                            SongName name,
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

        /** 组内全部文件名，改评分/改名/删除都要整组处理（文档 4.11）。顺序契约见 {@link GroupFileOps#entries} */
        public List<String> allFiles() {
            return GroupFileOps.allFiles(videoFile, audioFile, lyricFiles);
        }
    }

    /** 已归档根 */
    public String archivedRoot() {
        return properties.getSongDir();
    }

    /**
     * 待打分根 —— 配置项本身就是完整路径（{@code F:\NetdiskDownload\#已压缩歌曲\歌曲}
     * 那一层），本模块不再往它下面拼子目录。
     */
    public Path stagingRoot() {
        return Path.of(properties.getStagingDir());
    }

    /**
     * 扫已归档的全部组。
     *
     * @param partitionName 只看某个分区；空则全部分区
     */
    public List<SongGroup> listArchived(String partitionName) {
        String root = archivedRoot();
        List<SongGroup> result = new ArrayList<>();
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
    public List<SongGroup> listStaging() {
        Path dir = stagingRoot();
        return scanDir(dir.toString(), null, null, dir);
    }

    /**
     * 扫任意一个目录并归组（「添加文件」弹窗的联动展开用）。不判分区、不读库、不写任何东西。
     * <p>口径与 {@link #listStaging()} 逐字相同 —— 就是同一个 {@link #scanDir}。
     * 复用它是为了「联动进来的恰好是后端会认成同一组的那批」，自己写第二套归组就错了。
     * <p>注意 {@code scanDir} 会丢掉「只有歌词没有媒体」的组 —— 调用方（import-expand）
     * 要保留用户选中的锚点文件，别只信这里的返回（见 SongImportService.expand 的注释）。
     */
    public List<SongGroup> scanDirectory(Path dir) {
        return scanDir(dir.toString(), null, null, dir);
    }

    /**
     * 扫一个目录并归组。
     * <p>不递归 —— 分区下的文件是平铺的（文档 4.11），递归只会把偶尔出现的子目录
     * 里的文件混进来，而它们的分区归属并不明确。
     */
    private List<SongGroup> scanDir(String root, String partitionName,
                                    Integer score, Path dir) {
        File[] files = dir.toFile().listFiles(File::isFile);
        if (files == null) {
            return List.of();
        }
        // 主名 → 组内文件。LinkedHashMap 保住磁盘顺序，排序统一在最后做
        Map<String, List<String>> byMainName = new LinkedHashMap<>();
        for (File file : files) {
            String fileName = file.getName();
            if (!SongNameParser.isKnown(fileName)) {
                // #统计.xlsx、#评分标准 & 计划.txt 之类，不是歌曲文件
                continue;
            }
            byMainName.computeIfAbsent(SongNameParser.mainName(fileName), k -> new ArrayList<>())
                    .add(fileName);
        }

        List<SongGroup> groups = new ArrayList<>(byMainName.size());
        for (Map.Entry<String, List<String>> entry : byMainName.entrySet()) {
            String mainName = entry.getKey();
            String video = null;
            String audio = null;
            List<String> lyrics = new ArrayList<>();
            for (String fileName : entry.getValue()) {
                if (SongNameParser.isVideo(fileName)) {
                    video = fileName;
                } else if (SongNameParser.isAudio(fileName)) {
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
            // 解析失败不丢组：播放和打分不依赖解析结果（同 SongNameParser.parse 的立场）
            SongName name = SongNameParser.parse(mainName);
            groups.add(new SongGroup(root, partitionName, score,
                    mainName, name, video, audio, List.copyOf(lyrics)));
        }
        groups.sort(Comparator.comparing(g -> g.mainName().toLowerCase(Locale.ROOT)));
        return groups;
    }

    /**
     * 按 key 找一组，找不到抛异常。
     * <p>前端回传 key 而不是回传整组：组的内容随磁盘变，回传的那份可能已经过期，
     * 现扫一遍才是当前状态。
     */
    public SongGroup require(String partitionName, String mainName) {
        List<SongGroup> candidates = StringUtils.isBlank(partitionName)
                ? listStaging()
                : listArchived(partitionName);
        for (SongGroup group : candidates) {
            if (group.mainName().equals(mainName)) {
                return group;
            }
        }
        throw new IllegalStateException("找不到这一组："
                + StringUtils.defaultIfBlank(partitionName, "待打分") + " / " + mainName
                + "。多半是磁盘上已经改名或移走了，刷新一下列表");
    }
}
