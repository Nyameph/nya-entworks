package io.github.Nyameph.nyaentworks.song.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.ScoreWeight;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按原曲名统计（文档 8.6）。
 *
 * <p><b>已归档与待打分分开计数</b>：待打分的还没定分，混进去算分没意义 ——
 * 一首原曲有 5 个填词版本、其中 3 个还没打分，贝叶斯分只该按那 2 个算。
 *
 * <p>原曲名的归一化比较键复用 {@code SongNameParser.originalKey}（即漫画那套
 * NFC + trim + 大写）。NFC 这步不能省：日文假名的分解形式会被算成两个不同的原曲。
 */
@Service
@RequiredArgsConstructor
public class SongStatService {

    /** 贝叶斯平均分的最少评分数量（先验样本量）：原曲 63.8% 只有 1 个版本，取 2 让单版本保留 1/3 权重 */
    private static final int BAYESIAN_MIN = 2;

    private final SongGroupService groupService;
    private final SongSettingService settingService;

    /**
     * 一个原曲名的统计行。
     *
     * @param originalId     原曲设置的代理主键，前端回传它来配倍速 / 编辑模板
     * @param originalTitle  原曲名原文（取第一次遇到的那个写法）
     * @param artist         歌手（原唱），来自 song_original_setting，没填过为 {@code null}
     * @param archivedCount  已归档的填词版本数
     * @param stagingCount   待打分的填词版本数
     * @param bayesianScore  加权贝叶斯平均分，只计已归档的；一个都没归档时为 {@code null}
     * @param defaultRate    这个原曲名配的默认倍速；没配过为 {@code null}
     * @param hasOriginal    模板里有原曲音频
     * @param hasDemo       模板里有样例音频（MixDown / 原曲打样 / 原词打样）
     * @param hasAccompaniment  模板里有伴奏音频
     * @param hasVocals      模板里有纯人声音频
     * @param hasLyric       模板里有歌词
     * @param hasMid         模板里有 mid
     * @param hasSvp         模板里有 svp 模板
     * @param artistCheck    歌手已手动确认（扫描/搜索不再覆盖）
     * @param originalCheck  原曲文件已手动确认（扫描/搜索不再覆盖）
     * @param lyricCheck     歌词文件已手动确认（扫描/搜索不再覆盖）
     * @param svpCheck       svp 模板已手动确认（扫描不再覆盖）
     */
    public record OriginalStat(Long originalId,
                               String originalTitle,
                               String artist,
                               int archivedCount,
                               int stagingCount,
                               BigDecimal bayesianScore,
                               BigDecimal defaultRate,
                               boolean hasOriginal,
                               boolean hasDemo,
                               boolean hasAccompaniment,
                               boolean hasVocals,
                               boolean hasLyric,
                               boolean hasMid,
                               boolean hasSvp,
                               boolean artistCheck,
                               boolean originalCheck,
                               boolean lyricCheck,
                               boolean svpCheck) {

        public int totalCount() {
            return archivedCount + stagingCount;
        }
    }

    /** 累加用的可变载体，最后转成 {@link OriginalStat} */
    private static final class Accumulator {
        private final String key;       // 归组键（原曲名|作者 归一化）
        private final String title;     // 原曲名原文（取第一次遇到的写法）
        private final String artist;    // 原曲作者原文（旧格式为 null）
        private SongOriginalSetting setting; // 解析到的设置行（含 id；可能是补建出来的）
        private int archived;
        private int staging;
        private double wSum;        // 加权贝叶斯用（Σ 权重）
        private double wScoreSum;   // Σ 权重 × 分数

        private Accumulator(String key, String title, String artist) {
            this.key = key;
            this.title = title;
            this.artist = artist;
        }
    }

    /**
     * 全部原曲名的统计（文档 8.3）。
     */
    public List<OriginalStat> listOriginals() {
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        double globalWSum = 0;
        double globalWScoreSum = 0;

        // 已归档/待打分都按 mergeKey 归并成条目：同一首歌的多个版本（#1 / #2 / 无版本）
        // 在列表里合并成一行，统计也必须一行只算一次 —— 否则几个版本各算一次分，
        // 统计数字跟列表对不上。分数取主 variant（列表也是以它为代表）。
        for (SongGroup main : mergeMainVariants(groupService.listArchived(null))) {
            Accumulator acc = accumulatorFor(byKey, main);
            if (acc == null) {
                continue;
            }
            acc.archived++;
            if (main.score() != null) {
                int s = main.score();
                double w = ScoreWeight.weight(s);
                acc.wSum += w;
                acc.wScoreSum += w * s;
                globalWSum += w;
                globalWScoreSum += w * s;
            }
        }
        for (SongGroup main : mergeMainVariants(groupService.listStaging())) {
            Accumulator acc = accumulatorFor(byKey, main);
            if (acc != null) {
                acc.staging++;
            }
        }

        // 全局均值 C = 全部已归档有分条目的加权平均分（每个条目算一次）
        BigDecimal globalAvg = globalWSum == 0 ? null
                : BigDecimal.valueOf(globalWScoreSum)
                .divide(BigDecimal.valueOf(globalWSum), 6, RoundingMode.HALF_UP);

        List<SongOriginalSetting> settings = settingService.allOriginals();

        // 需求：所有「有已归档歌曲的原曲」都要在 song_original_setting 里有记录，没有则补建。
        // 补建出来的这一条本次也参与展示（模板列默认全无、倍速默认 null）。每条累加器解析并
        // 绑上它的设置行（旧格式按同名原曲取第一个，新格式按 原曲名+作者 精确找）。
        // 前端编辑/存倍速都按代理主键 originalId 定位，故待打分-only 的原曲也得补一条，
        // 否则它的 id 为空、点「编辑」无从定位。
        Set<Long> attachedIds = new HashSet<>();
        for (Accumulator acc : byKey.values()) {
            SongOriginalSetting s = SongSettingService.matchOriginal(settings, acc.title, acc.artist);
            if (s == null) {
                s = settingService.ensureOriginal(acc.title, acc.artist);
            }
            acc.setting = s;
            if (s != null && s.getId() != null) {
                attachedIds.add(s.getId());
            }
        }

        // 需求：song_original_setting 里的原曲都要展示 —— 即使磁盘上已没有它的歌
        // （模板目录还在、但歌已删除或搬走的原曲）。这些行 archived/staging 都是 0，不评分。
        for (SongOriginalSetting s : settings) {
            if (s.getId() != null && attachedIds.contains(s.getId())) {
                continue;
            }
            String key = statKey(s.getRawName(), s.getArtist());
            if (byKey.containsKey(key)) {
                continue; // 已被同名原曲的旧格式累加器借走，不另起一行
            }
            Accumulator acc = new Accumulator(key,
                    StringUtils.isNotBlank(s.getRawName()) ? s.getRawName() : String.valueOf(s.getId()),
                    s.getArtist());
            acc.setting = s;
            byKey.put(key, acc);
        }

        List<OriginalStat> result = new ArrayList<>(byKey.size());
        for (Accumulator acc : byKey.values()) {
            SongOriginalSetting setting = acc.setting;
            BigDecimal bayesian = acc.wSum == 0 || globalAvg == null ? null
                    : bayesian(globalAvg, acc.wScoreSum, acc.wSum);
            result.add(new OriginalStat(
                    setting == null ? null : setting.getId(),
                    acc.title,
                    setting == null ? null : setting.getArtist(),
                    acc.archived, acc.staging,
                    bayesian,
                    setting == null ? null : setting.getDefaultRate(),
                    setting != null && setting.getOriginalFileName() != null,
                    setting != null && setting.getDemoFileName() != null,
                    setting != null && setting.getAccompanimentFileName() != null,
                    setting != null && setting.getVocalsFileName() != null,
                    setting != null && setting.getLyricFileName() != null,
                    setting != null && setting.getMidFileName() != null,
                    setting != null && setting.getSvpFileName() != null,
                    setting != null && Boolean.TRUE.equals(setting.getArtistCheck()),
                    setting != null && Boolean.TRUE.equals(setting.getOriginalCheck()),
                    setting != null && Boolean.TRUE.equals(setting.getLyricCheck()),
                    setting != null && Boolean.TRUE.equals(setting.getSvpCheck())));
        }
        // 版本数多的在前 —— 「哪首原曲被填得最多」是这一页最想看的
        result.sort(Comparator.comparingInt(OriginalStat::totalCount).reversed()
                .thenComparing(OriginalStat::originalTitle));
        return result;
    }

    /**
     * 按归并键把组归并成条目，返回每个条目的主 variant。
     * 与列表页 {@code /merged-groups} 同一口径（版本号与文件类型不参与归并）；
     * 解析失败的组各自成键，不会互相合并。
     */
    private static List<SongGroup> mergeMainVariants(List<SongGroup> groups) {
        Map<String, List<SongGroup>> buckets = new LinkedHashMap<>();
        for (SongGroup g : groups) {
            buckets.computeIfAbsent(SongNameParser.mergeKey(g.name(), g.mainName()),
                    k -> new ArrayList<>()).add(g);
        }
        List<SongGroup> mains = new ArrayList<>(buckets.size());
        for (List<SongGroup> bucket : buckets.values()) {
            SongMergeService.sortVariants(bucket);
            mains.add(bucket.get(0));
        }
        return mains;
    }

    /**
     * @return 该组对应的累加器；解析失败的组返回 {@code null}（没有原曲名可归）
     */
    private static Accumulator accumulatorFor(Map<String, Accumulator> byKey, SongGroup group) {
        SongName name = group.name();
        if (name == null || !name.parsed()) {
            // 解析失败的不统计，但它们在歌曲页有单独一组（文档 8.1）：
            // 那里能看到原文与失败原因，这里凑进来只会污染统计
            return null;
        }
        String key = statKey(name.originalTitle(), name.originalArtist());
        return byKey.computeIfAbsent(key,
                k -> new Accumulator(k, name.originalTitle(), name.originalArtist()));
    }

    /** 原曲的归组键：{@code originalKey(原曲名)|originalKey(原曲作者)}，作者为空时后半段为空 */
    private static String statKey(String title, String artist) {
        String t = StringUtils.defaultString(SongNameParser.originalKey(title));
        String a = artist == null ? "" : StringUtils.defaultString(SongNameParser.originalKey(artist));
        return t + "|" + a;
    }

    /** 加权贝叶斯平均分 = (C × m + Σw·s) / (m + Σw) */
    private static BigDecimal bayesian(BigDecimal globalAvg, double wScoreSum, double wSum) {
        BigDecimal m = BigDecimal.valueOf(BAYESIAN_MIN);
        return globalAvg.multiply(m).add(BigDecimal.valueOf(wScoreSum))
                .divide(BigDecimal.valueOf(wSum).add(m), 1, RoundingMode.HALF_UP);
    }
}
