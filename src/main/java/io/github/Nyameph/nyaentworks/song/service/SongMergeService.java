package io.github.Nyameph.nyaentworks.song.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归并同作者+曲名+原曲的多个组（不同版本、不同文件类型）成一行（文档需求变更）。
 *
 * <p><b>归并键</b>：{@code (artists, title, originalTitle)} 的规范化形式，口径见
 * {@link SongNameParser#mergeKey}。版本号和文件类型不参与归并 ——
 * {@code 卡路里#1.mp4} 与 {@code 卡路里#2.mp3} 是同一个merged row的两个variants。
 *
 * <p><b>播放时切换variant</b>：前端传variant的 {@code key} 回来，后端接口仍按
 * 单个group处理（{@code /play-info} 等）。归并只在列表层面，不改单group的操作。
 */
@Service
@RequiredArgsConstructor
public class SongMergeService {

    private final SongSettingService settingService;
    private final SongTagService tagService;

    /**
     * 归并后的一行，含多个版本/文件类型的variants。
     *
     * @param mergeKey          {@code "artists|title|originalTitle"} 规范化后，前端定位用
     * @param artists           作者列表（取第一个variant的解析结果）
     * @param title             曲名（取第一个variant的）
     * @param originalTitle     原曲名（取第一个variant的；隐式原曲名统一成显式）
     * @param originalArtist    原曲作者（原唱）。只有文件名括号里写成 {@code 原曲名_原唱}
     *                          才有值，其余为 {@code null}；「修改」弹窗的三框装不下它，
     *                          由前端折进「原曲名」框（{@code SongForm} 的 {@code foldOriginal}），
     *                          丢掉就等于名字变短、{@code merge_key} 变掉、标签与原曲名级倍速全丢
     * @param variants          同作者+曲名+原曲的多个组，按「有视频 > 版本号降序 > 主名」排序
     * @param parsed            至少一个variant解析成功
     * @param parseFailedReason 若全部variant都解析失败，取第一个的原因
     * @param needsNormalize    至少一个variant需要规范化
     * @param tags              该合并条目的标签（DB-only，打标签/闸门用）
     */
    public record MergedSongRow(String mergeKey,
                                List<String> artists,
                                String title,
                                String originalTitle,
                                String originalArtist,
                                List<Variant> variants,
                                boolean parsed,
                                String parseFailedReason,
                                boolean needsNormalize,
                                List<String> tags) {
    }

    /**
     * 一个variant = 原先的一个 {@code SongGroup}。
     *
     * @param mainName         带版本号的主名（组的身份）
     * @param key              {@code "partition|mainName"} 用于前端回传定位
     * @param root             所在根目录路径
     * @param partitionName    评分分区目录名；待打分区为 {@code null}
     * @param score            评分；待打分区为 {@code null}
     * @param videoFile        视频文件名
     * @param audioFile        音频文件名
     * @param lyricFiles       歌词文件名列表
     * @param partitionDisplay 分区的显示文本（如 {@code "9 分 超赞"}）
     * @param defaultRate      组默认倍速
     * @param originalRate     原曲名级默认倍速（组级没设时回落到这一级，供列表页区别显示）
     * @param needsNormalize   这个 variant 需要补全原曲名（批量规范化与规范化页签定位用）
     * @param normalizedMainName 规范化后的主名；不需要规范化为 {@code null}
     */
    public record Variant(String mainName,
                          String key,
                          String root,
                          String partitionName,
                          Integer score,
                          String videoFile,
                          String audioFile,
                          List<String> lyricFiles,
                          String partitionDisplay,
                          BigDecimal defaultRate,
                          BigDecimal originalRate,
                          boolean needsNormalize,
                          String normalizedMainName) {
    }

    /**
     * 归并一批 {@code SongGroup}，按 {@code (artists, title, originalTitle)} 合并。
     *
     * @param groups 已经过滤、排序好的组列表（调用方负责筛选与排序）
     * @return 归并后的行列表，每行包含多个variants
     */
    public List<MergedSongRow> merge(List<SongGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }

        // 第一步：按 mergeKey 归组
        Map<String, List<SongGroup>> buckets = new LinkedHashMap<>();
        for (SongGroup g : groups) {
            String key = SongNameParser.mergeKey(g.name(), g.mainName());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }

        // 第二步：每个bucket转成一个 MergedSongRow；设置一次查完（不逐行查一遍库）
        Map<String, SongSettingService.GroupSetting> settings = settingService.loadGroupSettings(groups);
        // 原曲名级倍速一次查完，供「本身没存组级倍速、继承自原曲名」在列表页区别显示
        List<SongOriginalSetting> originals = settingService.allOriginals();
        // 标签一次查完（合并条目级，DB-only）
        Map<String, List<String>> tagsByMergeKey = tagService.listTagsBatch(buckets.keySet());
        List<MergedSongRow> result = new ArrayList<>();
        for (Map.Entry<String, List<SongGroup>> entry : buckets.entrySet()) {
            String mergeKey = entry.getKey();
            List<SongGroup> bucket = entry.getValue();
            result.add(buildMergedRow(mergeKey, bucket, settings, originals,
                    tagsByMergeKey.getOrDefault(mergeKey, List.of())));
        }
        return result;
    }

    /**
     * 把一个bucket（同mergeKey的多个group）转成一个 {@code MergedSongRow}。
     * <p>variants按「有视频 > 版本号降序 > 主名」排序，第一个variant作为代表性信息。
     */
    private MergedSongRow buildMergedRow(String mergeKey, List<SongGroup> bucket,
                                         Map<String, SongSettingService.GroupSetting> settings,
                                         List<SongOriginalSetting> originals,
                                         List<String> tags) {
        // variants排序：有视频优先 > 版本号降序 > 主名字典序
        sortVariants(bucket);

        SongGroup first = bucket.get(0);
        SongName firstName = first.name();

        // 代表性解析信息取第一个variant的
        List<String> artists = firstName != null && firstName.parsed() ? firstName.artists() : List.of();
        String title = firstName != null ? firstName.title() : "";
        // 隐式原曲名统一成显式（填充为曲名）
        String originalTitle = firstName != null && firstName.parsed()
                ? (firstName.originalExplicit() ? firstName.originalTitle() : firstName.title())
                : "";
        // 原曲作者随行下发（只有显式写成「原曲名_原唱」才有值）。「修改」弹窗的三框填不下它，
        // 前端折进「原曲名」框 —— 不下发就等于那次改名会把名字改短（同 SongName 的注释）
        String originalArtist = firstName != null && firstName.parsed()
                ? firstName.originalArtist() : null;

        // 判定parsed / needsNormalize：只要有一个variant满足，merged row就标记
        boolean anyParsed = bucket.stream().anyMatch(g -> g.name() != null && g.name().parsed());
        boolean anyNeedsNorm = bucket.stream().anyMatch(g -> g.name() != null && g.name().needsNormalize());
        String failReason = anyParsed ? null
                : (firstName != null ? firstName.parseFailedReason() : "无法解析");

        // 转换成 Variant 列表
        List<Variant> variants = new ArrayList<>();
        for (SongGroup g : bucket) {
            SongSettingService.GroupSetting setting = settings.get(g.key());
            String partDisplay = g.partitionName() != null
                    ? ScorePartition.parse(g.partitionName()).display()
                    : "待打分";
            SongName gName = g.name();
            // 组级倍速没设时，回落到的原曲名级倍速（同 resolveRate 的第 2 级）。隐式原曲名
            // 的 originalTitle 等于 title，按它查原曲名级设置与播放时回落的键完全一致
            BigDecimal originalRate = gName != null && gName.parsed()
                    ? originalRateOf(originals, gName.originalTitle(), gName.originalArtist()) : null;
            variants.add(new Variant(
                    g.mainName(),
                    g.key(),
                    g.root(),
                    g.partitionName(),
                    g.score(),
                    g.videoFile(),
                    g.audioFile(),
                    g.lyricFiles(),
                    partDisplay,
                    setting != null ? setting.defaultRate() : null,
                    originalRate,
                    gName != null && gName.needsNormalize(),
                    gName != null && gName.needsNormalize() ? gName.normalizedMainName() : null
            ));
        }

        return new MergedSongRow(mergeKey, artists, title, originalTitle, originalArtist, variants,
                anyParsed, failReason, anyNeedsNorm, tags);
    }

    /** 原曲名级默认倍速，没配过为 {@code null}。作者为空时按同名原曲取第一个（同设置匹配口径） */
    private static BigDecimal originalRateOf(List<SongOriginalSetting> originals,
                                             String originalTitle, String originalArtist) {
        SongOriginalSetting setting =
                SongSettingService.matchOriginal(originals, originalTitle, originalArtist);
        return setting == null ? null : setting.getDefaultRate();
    }

    /**
     * variant 排序：有视频 &gt; 版本号降序 &gt; 主名字典序。
     * <p>列表归并（{@link #buildMergedRow}）与同步落库（{@code SongSyncService}）共用，
     * 避免两处各写一份 —— 排序漂移会让「归并行的顺序」和「库里固化的 variant_sort」不一致。
     */
    public static void sortVariants(List<SongGroup> bucket) {
        bucket.sort(Comparator
                .comparing(SongGroup::hasVideo, Comparator.reverseOrder())
                .thenComparing(g -> extractVersion(g.mainName()), Comparator.reverseOrder())
                .thenComparing(SongGroup::mainName));
    }

    /**
     * 提取版本号。{@code "歌名#2"} → 2，{@code "歌名"} → 0（无版本号视为0）。
     */
    private static int extractVersion(String mainName) {
        SongName parsed = SongNameParser.parse(mainName);
        if (parsed.version() == null || parsed.version().isEmpty()) {
            return 0;
        }
        try {
            // 版本号可能是 "2" 或 "2ro3qc916iqp5alb"（哈希后缀），取前导数字
            String v = parsed.version().replaceAll("[^0-9].*", "");
            return v.isEmpty() ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
